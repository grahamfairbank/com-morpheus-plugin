package io.github.grahamfairbank.commorpheus

import groovy.util.logging.Slf4j

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

/** What the tab needs to know about a VME host, copied out of VME's ComputeServer. */
class HostFacts {
	Long id
	String name
	String hostname
	String vendor
	String product
	String typeCode
	Boolean vmHypervisor
	/** [name:, macAddress:] per interface */
	List<Map> interfaces = []
	/** VME tags, name -> value */
	Map<String, String> tags = [:]
}

/**
 * Builds the HPE COM tab for one host: match it to a COM server, then fetch and shape that server's data.
 * Holds the COM client and the MAC index; both are rebuilt when the connection settings change.
 * Free of VME classes (the tab provider converts ComputeServer to HostFacts).
 */
@Slf4j
class ComService {

	static final long INDEX_TTL_MS = 6L * 3600_000L
	static final long INDEX_MIN_REFRESH_MS = 10L * 60_000L
	static final long INDEX_RETRY_AFTER_ERROR_MS = 60_000L
	static final long SERVER_TTL_MS = 60_000L
	static final long ALERTS_TTL_MS = 60_000L
	static final long SECURITY_TTL_MS = 5L * 60_000L
	static final long WARRANTY_TTL_MS = 6L * 3600_000L
	static final long BUNDLE_TTL_MS = 24L * 3600_000L
	static final long GROUPS_TTL_MS = 3600_000L

	static final List<String> OVERRIDE_TAGS = ['com-id', 'com-serial']

	/**
	 * Per-load secret for action tokens (0.3.1). The tab carries a token for its host; action routes accept only
	 * that token, so markup injected elsewhere in the UI can't forge a working button. Rotates on plugin reload.
	 */
	private final byte[] tokenKey = { byte[] k = new byte[32]; new java.security.SecureRandom().nextBytes(k); k }()

	/** Tokens are for one day (0.4.3): a token works on the day it was issued and the day after, then never again. */
	static final long TOKEN_DAY_MS = 24L * 3600_000L

	String actionToken(Long hostId) { tokenFor(hostId, Math.floorDiv(clock.call(), TOKEN_DAY_MS)) }

	private String tokenFor(Long hostId, long day) {
		if (hostId == null) return null
		javax.crypto.Mac mac = javax.crypto.Mac.getInstance('HmacSHA256')
		mac.init(new javax.crypto.spec.SecretKeySpec(tokenKey, 'HmacSHA256'))
		return mac.doFinal("com-morpheus-action:${hostId}:${day}".toString().getBytes('UTF-8')).encodeHex().toString()
	}

	boolean validToken(Long hostId, String token) {
		if (hostId == null || token == null) return false
		long today = Math.floorDiv(clock.call(), TOKEN_DAY_MS)
		return [today, today - 1].any { long d ->
			java.security.MessageDigest.isEqual(tokenFor(hostId, d).getBytes('UTF-8'), token.getBytes('UTF-8'))
		}
	}

	// ---- per-user limits on the action routes (0.4.3) --------------------------------------------

	/** Route calls (caps, action, job) per user per minute. A job poll is one call every 4 s, so this allows several at once. */
	static final int ROUTE_CALLS_PER_MINUTE = 60
	/** Actions started per user per hour, across all hosts. */
	static final int ACTIONS_PER_HOUR = 20
	private final Map<String, long[]> routeWindows = new java.util.concurrent.ConcurrentHashMap<>()
	private final Map<String, long[]> actionWindows = new java.util.concurrent.ConcurrentHashMap<>()

	/** True while this user is under ROUTE_CALLS_PER_MINUTE. Counts the call. */
	boolean allowRoute(String user) { take(routeWindows, user ?: 'unknown', 60_000L, ROUTE_CALLS_PER_MINUTE) }

	/** Fixed-window counter: [window start, count] per user. */
	private boolean take(Map<String, long[]> windows, String user, long windowMs, int max) {
		long now = clock.call()
		synchronized (windows) {
			if (windows.size() > 5000) windows.entrySet().removeIf { Map.Entry<String, long[]> e -> now - e.value[0] >= windowMs }
			long[] w = windows[user]
			if (w == null || now - w[0] >= windowMs) { windows[user] = [now, 1L] as long[]; return true }
			if (w[1] >= max) return false
			w[1]++
			return true
		}
	}

	static final List<String> NOT_PHYSICAL = ['vmware', 'qemu', 'kvm', 'virtual', 'mvm', 'xen', 'hyper-v', 'bochs']

	private final Closure<ComTransport> transportFactory
	private final Closure<Long> clock
	private final ExecutorService executor
	private final AtomicBoolean building = new AtomicBoolean(false)

	private ComClient client
	private String connectionKey
	private volatile ComIndex index
	private volatile String indexError
	private volatile long indexRequestedAt

	ComService(Closure<ComTransport> transportFactory = { ComSettings s -> new JdkComTransport(s.proxyUrl) },
	           Closure<Long> clock = { System.currentTimeMillis() }, ExecutorService executor = null) {
		this.transportFactory = transportFactory
		this.clock = clock
		this.executor = executor ?: Executors.newSingleThreadExecutor({ Runnable r ->
			Thread t = new Thread(r, 'com-morpheus-index')
			t.daemon = true
			t
		} as ThreadFactory)
	}

	void shutdown() { executor.shutdownNow() }

	/** Physical HPE server: HPE/HP vendor and nothing virtual in the vendor or product name. */
	static boolean isPhysicalHpe(HostFacts h) {
		String vendor = h?.vendor?.trim()?.toLowerCase() ?: ''
		String product = h?.product?.trim()?.toLowerCase() ?: ''
		if (!(vendor in ['hpe', 'hp', 'hewlett packard enterprise', 'hewlett-packard'])) return false
		return !NOT_PHYSICAL.any { String v -> vendor.contains(v) || product.contains(v) }
	}

	/** A hypervisor host rather than a VM. */
	static boolean isHost(HostFacts h) {
		return h?.vmHypervisor == true || h?.typeCode in ['mvmHost', 'kvmHost']
	}

	synchronized ComClient clientFor(ComSettings s) {
		String key = s.connectionKey()
		if (client == null || key != connectionKey) {
			client = new ComClient(s, transportFactory(s), clock)
			connectionKey = key
			index = null
			indexError = null
			indexRequestedAt = 0L
			groupIndex = null
			groupIndexAt = 0L
		}
		return client
	}

	ComIndex currentIndex() { index }

	/** Starts an index build in the background unless one is running (or ran very recently, unless forced). */
	void requestIndex(ComSettings s, boolean force = false) {
		ComClient c = clientFor(s)
		long now = clock.call()
		if (!force && index != null && now - index.builtAt < INDEX_TTL_MS) return
		if (index != null && now - indexRequestedAt < INDEX_MIN_REFRESH_MS) return
		if (index == null && indexError && now - indexRequestedAt < INDEX_RETRY_AFTER_ERROR_MS) return
		if (!building.compareAndSet(false, true)) return
		indexRequestedAt = now
		executor.submit {
			try {
				ComIndex built = ComIndex.build(c, clock.call())
				if (c.is(client)) { index = built; indexError = null }
			} catch (Throwable e) {
				indexError = e instanceof ComException ? e.message : unexpected('index build', e)
				if (e instanceof ComException) log.warn("com-morpheus: index build failed: ${indexError}")
			} finally {
				building.set(false)
			}
		}
	}

	/**
	 * Which COM server a host is. Returns [comId:, how: confirmed|probable|override, why:] when matched,
	 * otherwise [state: building|error|nomatch|conflict|notHpe|unconfigured, ...] for the template.
	 * The tab and the action routes both use this, so actions never trust an id from the browser.
	 */
	Map resolve(HostFacts host, ComSettings s) {
		if (!s.configured) return [state: 'unconfigured']
		if (s.problem()) return [state: 'error', error: s.problem()]
		if (!isPhysicalHpe(host)) {
			return [state: 'notHpe', vendor: host?.vendor?.trim() ?: '--', product: host?.product?.trim() ?: '--',
				hostMacs: MacMatcher.hostMacs(host?.interfaces).join(', ') ?: 'none']
		}
		String override = OVERRIDE_TAGS.findResult { String t -> host.tags?.find { k, v -> k?.equalsIgnoreCase(t) }?.value }
		String overrideTag = OVERRIDE_TAGS.find { String t -> host.tags?.any { k, v -> k?.equalsIgnoreCase(t) } }
		// A tag decides what the tab SHOWS. For ACTIONS it must be backed by this host's own hardware (see
		// corroborate): a user who can edit tags on their own host must not be able to aim a job at a server
		// they don't own (0.3.1 security fix).
		if (override && overrideTag == 'com-id') {
			String id = override.trim()
			clientFor(s)
			ComIndex idx0 = index
			if (idx0 == null) requestIndex(s)
			return [comId: id, how: 'override', why: 'Set by the com-id tag on this host'] + corroborate(host, idx0, id)
		}

		clientFor(s)
		ComIndex idx = index
		if (idx == null) {
			requestIndex(s)
			return [state: indexError ? 'error' : 'building', error: indexError]
		}
		requestIndex(s) // refreshes in the background when older than INDEX_TTL_MS

		if (override) {
			String id = idx.resolveOverride(override)
			if (id) return [comId: id, how: 'override', why: "Set by the ${overrideTag} tag on this host".toString()] + corroborate(host, idx, id)
			return [state: 'nomatch', reason: "The ${overrideTag} tag says '${override}', but no COM server has that ${overrideTag == 'com-serial' ? 'serial number' : 'id'}.".toString()]
		}

		Set<String> macs = MacMatcher.hostMacs(host.interfaces)
		MacMatcher.Result r = MacMatcher.match(macs, idx.macs)
		switch (r.status) {
			case MacMatcher.Status.CONFIRMED:
				return [comId: r.comId, how: 'confirmed', why: "All ${r.shared} of this host's network adapter MAC addresses match this COM server".toString(), actionable: true]
			case MacMatcher.Status.PROBABLE:
				return [comId: r.comId, how: 'probable', why: "${r.shared} of this host's ${r.hostMacs} network adapter MAC addresses match this COM server. To confirm it, add the tag com-serial=${idx.servers[r.comId]?.serial ?: '<serial>'} to this host.".toString(),
					actionable: false, actionWhy: 'The COM match for this host is only probable. Confirm it with a com-serial tag that matches this host before running actions.']
			case MacMatcher.Status.CONFLICT:
				return [state: 'conflict', candidates: r.candidates.collect { String id, Integer n ->
					Map sv = idx.servers[id] ?: [:]
					[id: id, serial: sv.serial ?: '--', name: sv.name ?: '--', model: sv.model ?: '--', shared: n, url: s.serverPageUrl(id)]
				}]
			default:
				if (clock.call() - idx.builtAt > INDEX_MIN_REFRESH_MS) requestIndex(s, true)
				return [state: 'nomatch', hostMacs: macs.join(', ') ?: 'none',
					reason: r.status == MacMatcher.Status.NO_MACS ?
						'Morpheus has no globally unique network adapter MAC addresses for this host.' :
						"None of this host's ${macs.size()} network adapter MAC addresses appear in the inventory of the ${idx.servers.size()} servers in COM.".toString(),
					indexed: idx.servers.size(), indexAt: ComView.when(java.time.Instant.ofEpochMilli(idx.builtAt).toString())]
		}
	}

	/** The template model for a host's tab. Never throws: problems become a "state" the template explains. */
	Map tab(HostFacts host, ComSettings s) {
		Map base = [hostName: host?.name, comUiUrl: s.uiUrl, hostId: host?.id]
		try {
			Map m = resolve(host, s)
			if (!m.comId) return base + m
			Map tab = matched(base, clientFor(s), s, m.comId as String, m.how as String, m.why as String)
			if (tab.state == 'matched') {
				tab.actionsEnabled = s.enableActions && m.actionable == true
				if (tab.actionsEnabled) tab.actionToken = actionToken(host?.id)
				if (s.enableActions && m.actionable != true && m.actionWhy) tab.notes = ((List) (tab.notes ?: [])) + [m.actionWhy]
			}
			return tab
		} catch (ComException e) {
			return base + [state: 'error', error: e.message]
		} catch (Throwable e) {
			return base + [state: 'error', error: unexpected("tab for host ${host?.id}", e)]
		}
	}

	// ---- cluster summary (0.4.0, read-only) ----------------------------------------------------

	static final long SUMMARY_TTL_MS = 60_000L

	/**
	 * One row per hypervisor host of a cluster, from the same matching as the host tab, plus three bulk COM
	 * reads shared by all rows (server list, alerts, warranty), so a cluster costs the same COM calls as one host.
	 * Read-only: no actions, no tokens. Never throws.
	 */
	Map cluster(String clusterName, List<HostFacts> hosts, ComSettings s) {
		Map base = [clusterName: clusterName, comUiUrl: s.uiUrl]
		if (!s.configured) return base + [state: 'unconfigured']
		if (s.problem()) return base + [state: 'error', error: s.problem()]
		List<HostFacts> hvs = (hosts ?: []).findAll { isHost(it) }.sort { (it.name ?: '').toLowerCase() }
		if (!hvs) return base + [state: 'empty']
		try {
			List<Map> rows = hvs.collect { HostFacts h -> [host: h, m: resolve(h, s)] }.collect { Map x -> clusterRow((HostFacts) x.host, (Map) x.m) }
			if (rows.every { it.status == 'building' }) return base + [state: 'building']
			ComClient c = clientFor(s)
			List<String> notes = []
			Map<String, Map> servers = ((List<Map>) (optional(notes, 'server list') { c.serverSummaries(SUMMARY_TTL_MS) } ?: []))
				.collectEntries { [(it.id as String): it] }
			List<String> comIds = rows.findAll { it.comId && servers[it.comId as String] }.collect { it.comId as String }.unique()
			Map<String, Integer> alertCounts = activeAlertCounts(c, comIds, notes)   // null = unknown
			Map<String, Map> warranty = ((List<Map>) (optional(notes, 'warranty') { c.warranties(WARRANTY_TTL_MS) } ?: []))
				.collectEntries { [(it.deviceId as String): it] }
			rows.each { Map r ->
				if (!r.comId) return
				Map sv = servers[r.comId as String]
				if (!sv) { r.status = 'missing'; r.statusText = 'Not in COM'; r.statusLevel = 'warn'; return }
				Map hw = (Map) (sv.hardware ?: [:])
				Map health = (Map) (hw.health ?: [:])
				Map w = warranty[r.comId as String]
				Integer alerts = alertCounts == null ? null : (alertCounts[r.comId as String] ?: 0)
				r.putAll([serial: hw.serialNumber ?: '--', model: hw.model ?: '--',
				          health: ComView.words(health.summary), healthLevel: ComView.level(health.summary),
				          power: ComView.words(hw.powerState), powerLevel: hw.powerState == 'ON' ? 'ok' : null,
				          alerts: alerts == null ? '--' : alerts, alertsLevel: alerts == null ? null : (alerts ? 'warn' : 'ok'),
				          support: w ? ComView.words(w.supportState) : '--', supportLevel: w ? ComView.level(w.supportState) : null,
				          maintenance: sv.maintenanceMode == true ? 'Yes' : 'No', maintenanceLevel: sv.maintenanceMode == true ? 'warn' : null,
				          connected: ((Map) (sv.state ?: [:])).connected != false,
				          comUrl: s.serverPageUrl(r.comId as String)])
			}
			List<Map> matched = rows.findAll { it.serial }
			Map totals = [hosts: rows.size(), matched: matched.size(),
			              ok: matched.count { it.healthLevel == 'ok' }, warn: matched.count { it.healthLevel == 'warn' },
			              bad: matched.count { it.healthLevel == 'bad' },
			              alertsKnown: alertCounts != null,
			              withAlerts: matched.count { it.alerts instanceof Integer && it.alerts > 0 },
			              alerts: matched.sum(0) { it.alerts instanceof Integer ? it.alerts : 0 }, maintenance: matched.count { it.maintenance == 'Yes' },
			              supportIssues: matched.count { it.supportLevel == 'bad' || it.supportLevel == 'warn' },
			              unmatched: rows.size() - matched.size()]
			return base + [state: 'summary', rows: rows, totals: totals, notes: notes]
		} catch (ComException e) {
			return base + [state: 'error', error: e.message]
		} catch (Throwable e) {
			return base + [state: 'error', error: unexpected("cluster summary for ${clusterName}", e)]
		}
	}

	/** The Morpheus side of a cluster row: which COM server, or why none. */
	static Map clusterRow(HostFacts h, Map m) {
		Map r = [hostId: h.id, hostName: h.name ?: h.hostname ?: "host ${h.id}".toString(),
		         hostUrl: h.id != null ? "/infrastructure/servers/${h.id}".toString() : null, comId: m.comId]
		if (m.comId) {
			r.status = 'matched'
			r.statusText = m.how == 'confirmed' ? 'Confirmed' : (m.how == 'override' ? 'Set by tag' : 'Probable')
			r.statusLevel = m.how == 'probable' ? 'warn' : 'ok'
		} else {
			r.status = (m.state ?: 'nomatch') as String
			r.statusText = [notHpe: 'Not an HPE server', building: 'Matching…', conflict: 'Several COM servers',
			                nomatch: 'No COM match', error: 'Error', unconfigured: 'Not configured'][r.status] ?: (r.status as String)
			r.statusLevel = r.status in ['notHpe', 'building'] ? null : 'warn'
		}
		return r
	}

	/**
	 * Whether a tag override may be used for ACTIONS: the tagged COM server must be in the index, share at
	 * least one of this host's globally unique MAC addresses, and no other COM server may have any of them.
	 */
	static Map corroborate(HostFacts host, ComIndex idx, String comId) {
		if (idx == null) return [actionable: false, actionWhy: 'Matching hosts to COM is still in progress. Try again in a minute.']
		if (!idx.servers.containsKey(comId)) return [actionable: false, actionWhy: "The tagged COM server isn't in this workspace's server list, so actions are disabled."]
		Set<String> macs = MacMatcher.hostMacs(host?.interfaces)
		Set<String> theirs = idx.macs[comId] ?: ([] as Set)
		int shared = (int) macs.count { String m -> theirs.contains(m) }
		boolean elsewhere = idx.macs.any { String id, Set<String> v -> id != comId && v && macs.any { String m -> v.contains(m) } }
		if (shared > 0 && !elsewhere) return [actionable: true, corroboratedBy: shared]
		return [actionable: false, actionWhy: "Actions on a tag-matched host need the tag to agree with the host's hardware: at least one of its network adapter MAC addresses must be in that COM server's inventory, and none in another server's. Collect inventory in COM, or remove the tag."]
	}

	// ---- actions (phase 2) ---------------------------------------------------------------------

	static final Map<String, String> ACTION_LABELS = [uidOn: 'UID light on', uidOff: 'UID light off',
		collectInventory: 'Collect inventory', iloReset: 'iLO reset',
		comMaintenanceOn: 'Enter COM maintenance', comMaintenanceOff: 'Exit COM maintenance']

	/** Minimum time between two actions on the same COM server (0.3.1: no tight loops of iLO resets). */
	static final long ACTION_COOLDOWN_MS = 30_000L
	/** Minimum time between two iLO resets of the same server (0.4.3). */
	static final long ILO_RESET_COOLDOWN_MS = 10L * 60_000L
	private final Map<String, Long> lastIloResetAt = new java.util.concurrent.ConcurrentHashMap<>()
	private final Map<String, Long> lastActionAt = new java.util.concurrent.ConcurrentHashMap<>()

	/** Starts a COM job for a host. Only on an actionable match (confirmed, or a hardware-backed tag). Never throws. */
	Map startAction(HostFacts host, ComSettings s, String action, String user) {
		if (!s.enableActions) return [ok: false, error: 'Actions are turned off in the plugin settings.']
		if (!ACTION_LABELS.containsKey(action)) return [ok: false, error: "Unknown action '${action}'.".toString()]
		if (!isHost(host)) return [ok: false, error: "Actions are only for hypervisor hosts."]
		String cooling = null
		try {
			Map m = resolve(host, s)
			if (!m.comId) return [ok: false, error: "This host isn't matched to a COM server (${m.state}).".toString()]
			if (m.actionable != true) return [ok: false, error: (m.actionWhy ?: 'Actions are not allowed for this match.') as String]
			String comId = m.comId as String
			long now = clock.call()
			synchronized (lastActionAt) {
				Long last = lastActionAt[comId]
				if (last != null && now - last < ACTION_COOLDOWN_MS) {
					return [ok: false, error: "An action was started on this server less than ${(int) (ACTION_COOLDOWN_MS / 1000)} seconds ago. Wait for it to finish, then try again.".toString()]
				}
				Long lastReset = lastIloResetAt[comId]
				if (action == 'iloReset' && lastReset != null && now - lastReset < ILO_RESET_COOLDOWN_MS) {
					return [ok: false, error: "This server's iLO was reset less than ${(int) (ILO_RESET_COOLDOWN_MS / 60_000L)} minutes ago. Wait, then try again.".toString()]
				}
				if (!take(actionWindows, user ?: 'unknown', 3600_000L, ACTIONS_PER_HOUR)) {
					log.warn("com-morpheus: ${user} reached ${ACTIONS_PER_HOUR} actions in an hour; refused ${action} on host ${host?.id}")
					return [ok: false, error: "You've started ${ACTIONS_PER_HOUR} actions in the last hour, the most the plugin allows. Try again later.".toString()]
				}
				lastActionAt[comId] = now
				if (action == 'iloReset') lastIloResetAt[comId] = now
				cooling = comId
			}
			String already = maintenanceAlready(action, comId, s)   // after the cooldown, so repeated clicks don't each read COM
			if (already) {
				lastActionAt.remove(comId)   // a refused no-op doesn't use up the cooldown (it has used one of the hour's actions)
				return [ok: false, error: already]
			}
			if (m.how == 'override') log.warn("com-morpheus: ${user} is acting on host ${host?.id} ${host?.name} through a tag override (${m.why}), confirmed by ${m.corroboratedBy} MAC address(es)")
			Map job = clientFor(s).createJob(action, comId)
			String started = "com-morpheus: ${user} started ${action} (job ${job?.id}) on host ${host?.id} ${host?.name} = COM ${m.comId}"
			if (action == 'comMaintenanceOn') log.warn("${started}: COM won't open support cases or send notifications for this server until maintenance ends")
			else log.info(started)
			return [ok: true, action: action, label: ACTION_LABELS[action], jobId: job?.id, state: job?.state ?: 'PENDING', comId: m.comId]
		} catch (ComException e) {
			if (cooling) { lastActionAt.remove(cooling); if (action == 'iloReset') lastIloResetAt.remove(cooling) } // COM refused it: allow a retry straight away
			log.warn("com-morpheus: ${user} ${action} on host ${host?.id} failed: ${e.message}")
			return [ok: false, error: e.message]
		} catch (Throwable e) {
			if (cooling) { lastActionAt.remove(cooling); if (action == 'iloReset') lastIloResetAt.remove(cooling) }
			return [ok: false, error: unexpected("${user} ${action} on host ${host?.id}", e)]
		}
	}

	/**
	 * For COM maintenance actions: re-reads the server uncached and refuses a change to the state it's already in,
	 * so a button left over from an older page can't do the opposite of what it says. Null when the action may go ahead.
	 */
	private String maintenanceAlready(String action, String comId, ComSettings s) {
		if (!(action in ['comMaintenanceOn', 'comMaintenanceOff'])) return null
		ComClient c = clientFor(s)
		Map sv
		try {
			sv = c.server(comId, 0L)
		} catch (ComException e) {
			if (e.notFound) throw e
			sv = listEntry(c, comId, 0L)   // the list has maintenanceMode too
			if (sv == null) throw e
		}
		boolean on = sv?.maintenanceMode == true
		if (action == 'comMaintenanceOn' && on) return 'This server is already in COM maintenance mode. Reload the page.'
		if (action == 'comMaintenanceOff' && !on) return "This server isn't in COM maintenance mode. Reload the page."
		return null
	}

	/** When COM last refused the workspace alerts query (HTTP 400); -1 = never. Per-server reads are used for a while after. */
	private volatile long bulkAlertsRefusedAt = -1L
	static final long BULK_ALERTS_RETRY_MS = 6L * 3600_000L

	/**
	 * Active alerts per COM server id, for the cluster summary. Tries one workspace-wide query; if COM refuses it
	 * (400), reads each matched server's own alerts (the host tab's endpoint, sharing its cache) and doesn't try the
	 * workspace query again for six hours. Returns null, with a note, when alerts can't be read at all.
	 */
	private Map<String, Integer> activeAlertCounts(ComClient c, List<String> comIds, List<String> notes) {
		long now = clock.call()
		if (bulkAlertsRefusedAt < 0 || now - bulkAlertsRefusedAt >= BULK_ALERTS_RETRY_MS) {
			try {
				Map<String, Integer> counts = [:]
				c.activeAlerts(SUMMARY_TTL_MS).each { Map a ->
					String sid = ComClient.alertServerId(a)
					if (sid && activeAlert(a)) counts[sid] = (counts[sid] ?: 0) + 1
				}
				return counts
			} catch (ComException e) {
				if (e.status != 400) { notes << "Couldn't load alerts: ${e.message}".toString(); return null }
				bulkAlertsRefusedAt = now
				log.info("com-morpheus: COM refused the workspace alerts query (${e.message}); reading alerts per server for the next 6 hours")
			}
		}
		try {
			Map<String, Integer> counts = [:]
			comIds.each { String id -> counts[id] = c.alerts(id, ALERTS_TTL_MS).count { Map a -> activeAlert(a) } as Integer }
			return counts
		} catch (ComException e) {
			notes << "Couldn't load alerts: ${e.message}".toString()
			return null
		}
	}

	private static boolean activeAlert(Map a) { !a.clearedAt && a.cleared != true }

	/** Logs an unexpected error in full and returns a short message with a reference to find it in the log. */
	static String unexpected(String what, Throwable e) {
		String ref = Long.toHexString(Double.doubleToLongBits(Math.random())).take(8)
		log.warn("com-morpheus: ${what} failed (ref ${ref})", e)
		return "Unexpected error (ref ${ref}). Details are in the Morpheus log.".toString()
	}

	static final String NO_SUCH_JOB = 'No such job for this host.'
	/** Job status is reused for a few seconds (0.4.3), so a tight poll loop costs COM one call per interval. */
	static final long JOB_TTL_MS = 3_000L

	/** A job's progress, only if it belongs to this host's COM server. Never throws. */
	Map jobStatus(HostFacts host, ComSettings s, String jobId) {
		try {
			if (!isHost(host)) return [ok: false, error: NO_SUCH_JOB]
			Map m = resolve(host, s)
			if (!m.comId || m.actionable != true) return [ok: false, error: NO_SUCH_JOB]
			ComClient c = clientFor(s)
			Map job
			try {
				job = c.job(jobId, JOB_TTL_MS)
			} catch (ComException e) {
				if (e.notFound) return [ok: false, error: NO_SUCH_JOB]
				throw e
			}
			// The job must name this host's COM server: a missing or different resource gets the same answer.
			String resourceId = ((Map) (job?.resource ?: [:])).id as String
			if (resourceId != m.comId) return [ok: false, error: NO_SUCH_JOB]
			String state = job?.state as String
			boolean done = state in ['COMPLETE', 'ERROR', 'STALLED']
			if (done && job?.resultCode == 'SUCCESS') c.invalidate(m.comId as String) // fresh data on the next tab render
			return [ok: true, jobId: jobId, state: state, resultCode: job?.resultCode, status: job?.status, done: done]
		} catch (ComException e) {
			return [ok: false, error: e.message]
		} catch (Throwable e) {
			return [ok: false, error: unexpected("job ${jobId} status", e)]
		}
	}

	private Map matched(Map base, ComClient c, ComSettings s, String comId, String how, String why) {
		Map server
		List<String> notes = []
		try {
			server = c.server(comId, SERVER_TTL_MS)
		} catch (ComException e) {
			if (e.notFound) return base + [state: 'nomatch', reason: "COM has no server with id ${comId}.".toString()]
			server = listEntry(c, comId, SERVER_TTL_MS)
			if (server == null) throw e
			// Show what the server list has rather than nothing (0.4.2: COM answered 500 for single servers).
			notes << "COM couldn't return this server's full record (${e.message}), so some fields come from COM's server list and others show --.".toString()
			log.warn("com-morpheus: single-server read failed for ${comId}; showing the server-list entry instead: ${e.message}")
		}
		Map extra = [:]
		extra.alerts = optional(notes, 'alerts') { c.alerts(comId, ALERTS_TTL_MS) }
		extra.security = optional(notes, 'iLO security') { c.security(comId, SECURITY_TTL_MS) }
		extra.warranty = optional(notes, 'warranty') { ComView.warrantyFor(comId, c.warranties(WARRANTY_TTL_MS)) }
		String bundleId = ComView.bundleId(server)
		if (bundleId) extra.bundle = optional(notes, 'firmware baseline') { c.firmwareBundle(bundleId, BUNDLE_TTL_MS) }
		Map inventory = (Map) optional(notes, 'inventory') { c.inventory(comId, ComIndex.INVENTORY_TTL_MS) }
		extra.group = groupOf(c, comId)
		return base + ComView.model(server, extra, s) + [inv: ComInventoryView.model(inventory), actionsEnabled: false] + [state: 'matched', match: how, matchWhy: why,
			matchLevel: how == 'probable' ? 'warn' : 'ok', notes: notes]
	}

	private volatile Map<String, String> groupIndex
	private volatile long groupIndexAt
	private final AtomicBoolean groupsBuilding = new AtomicBoolean(false)

	/**
	 * The server's COM group name from a device -> group map rebuilt hourly in the background (0.4.3). Reading
	 * every group's members during a page render cost one COM call per group. Null until the first build is done.
	 */
	String groupOf(ComClient c, String comId) {
		long now = clock.call()
		if ((groupIndex == null || now - groupIndexAt >= GROUPS_TTL_MS) && groupsBuilding.compareAndSet(false, true)) {
			try {
				executor.submit {
					try {
						Map<String, String> built = ComView.groupMap(c.groups(GROUPS_TTL_MS)) { String gid -> c.groupDevices(gid, 0L) }
						if (c.is(client)) { groupIndex = built; groupIndexAt = clock.call() }
					} catch (Throwable e) {
						log.warn("com-morpheus: couldn't read COM groups: ${e instanceof ComException ? e.message : e.class.simpleName}")
						if (c.is(client)) { groupIndex = groupIndex ?: [:]; groupIndexAt = clock.call() - GROUPS_TTL_MS + 60_000L }   // retry in a minute
					} finally {
						groupsBuilding.set(false)
					}
				}
			} catch (Throwable e) {
				groupsBuilding.set(false)
			}
		}
		return groupIndex?.get(comId)
	}

	/** A server's entry from COM's server list (id, name, hardware, state, maintenanceMode, ...), or null. */
	private static Map listEntry(ComClient c, String comId, long ttlMs) {
		try {
			return (Map) c.servers(ttlMs).find { it.id == comId }
		} catch (ComException ignored) {
			return null
		}
	}

	private static Object optional(List<String> notes, String what, Closure<Object> fetch) {
		try {
			return fetch()
		} catch (ComException e) {
			notes << "Couldn't load ${what}: ${e.message}".toString()
			return null
		}
	}
}
