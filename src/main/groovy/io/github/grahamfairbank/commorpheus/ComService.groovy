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
				indexError = e instanceof ComException ? e.message : "${e.class.simpleName}: ${e.message}"
				log.warn("com-morpheus: index build failed: ${indexError}")
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
		if (!isPhysicalHpe(host)) {
			return [state: 'notHpe', vendor: host?.vendor?.trim() ?: '--', product: host?.product?.trim() ?: '--',
				hostMacs: MacMatcher.hostMacs(host?.interfaces).join(', ') ?: 'none']
		}
		String override = OVERRIDE_TAGS.findResult { String t -> host.tags?.find { k, v -> k?.equalsIgnoreCase(t) }?.value }
		String overrideTag = OVERRIDE_TAGS.find { String t -> host.tags?.any { k, v -> k?.equalsIgnoreCase(t) } }
		// com-id override works without the index
		if (override && overrideTag == 'com-id') return [comId: override.trim(), how: 'override', why: 'Set by the com-id tag on this host']

		clientFor(s)
		ComIndex idx = index
		if (idx == null) {
			requestIndex(s)
			return [state: indexError ? 'error' : 'building', error: indexError]
		}
		requestIndex(s) // refreshes in the background when older than INDEX_TTL_MS

		if (override) {
			String id = idx.resolveOverride(override)
			if (id) return [comId: id, how: 'override', why: "Set by the ${overrideTag} tag on this host".toString()]
			return [state: 'nomatch', reason: "The ${overrideTag} tag says '${override}', but no COM server has that ${overrideTag == 'com-serial' ? 'serial number' : 'id'}.".toString()]
		}

		Set<String> macs = MacMatcher.hostMacs(host.interfaces)
		MacMatcher.Result r = MacMatcher.match(macs, idx.macs)
		switch (r.status) {
			case MacMatcher.Status.CONFIRMED:
				return [comId: r.comId, how: 'confirmed', why: "All ${r.shared} of this host's network adapter MAC addresses match this COM server".toString()]
			case MacMatcher.Status.PROBABLE:
				return [comId: r.comId, how: 'probable', why: "${r.shared} of this host's ${r.hostMacs} network adapter MAC addresses match this COM server. To confirm it, add the tag com-serial=${idx.servers[r.comId]?.serial ?: '<serial>'} to this host.".toString()]
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
			return matched(base, clientFor(s), s, m.comId as String, m.how as String, m.why as String)
		} catch (ComException e) {
			return base + [state: 'error', error: e.message]
		} catch (Throwable e) {
			log.warn("com-morpheus: tab for host ${host?.id} failed", e)
			return base + [state: 'error', error: "${e.class.simpleName}: ${e.message}".toString()]
		}
	}

	// ---- actions (phase 2) ---------------------------------------------------------------------

	static final Map<String, String> ACTION_LABELS = [uidOn: 'UID light on', uidOff: 'UID light off',
		collectInventory: 'Collect inventory', iloReset: 'iLO reset']

	/** Starts a COM job for a host. Only on a confirmed or tag-overridden match. Never throws. */
	Map startAction(HostFacts host, ComSettings s, String action, String user) {
		if (!s.enableActions) return [ok: false, error: 'Actions are turned off in the plugin settings.']
		if (!ACTION_LABELS.containsKey(action)) return [ok: false, error: "Unknown action '${action}'.".toString()]
		try {
			Map m = resolve(host, s)
			if (!m.comId) return [ok: false, error: "This host isn't matched to a COM server (${m.state}).".toString()]
			if (m.how == 'probable') return [ok: false, error: 'The COM match for this host is only probable. Confirm it with a com-serial tag before running actions.']
			Map job = clientFor(s).createJob(action, m.comId as String)
			log.info("com-morpheus: ${user} started ${action} (job ${job?.id}) on host ${host?.id} ${host?.name} = COM ${m.comId}")
			return [ok: true, action: action, label: ACTION_LABELS[action], jobId: job?.id, state: job?.state ?: 'PENDING', comId: m.comId]
		} catch (ComException e) {
			log.warn("com-morpheus: ${user} ${action} on host ${host?.id} failed: ${e.message}")
			return [ok: false, error: e.message]
		} catch (Throwable e) {
			log.warn("com-morpheus: ${user} ${action} on host ${host?.id} failed", e)
			return [ok: false, error: "${e.class.simpleName}: ${e.message}".toString()]
		}
	}

	/** A job's progress, only if it belongs to this host's COM server. Never throws. */
	Map jobStatus(HostFacts host, ComSettings s, String jobId) {
		try {
			Map m = resolve(host, s)
			if (!m.comId) return [ok: false, error: "This host isn't matched to a COM server (${m.state}).".toString()]
			ComClient c = clientFor(s)
			Map job = c.job(jobId)
			String resourceId = ((Map) (job?.resource ?: [:])).id as String
			if (resourceId && resourceId != m.comId) return [ok: false, error: "That job isn't for this host's COM server."]
			String state = job?.state as String
			boolean done = state in ['COMPLETE', 'ERROR', 'STALLED']
			if (done && job?.resultCode == 'SUCCESS') c.invalidate(m.comId as String) // fresh data on the next tab render
			return [ok: true, jobId: jobId, state: state, resultCode: job?.resultCode, status: job?.status, done: done]
		} catch (ComException e) {
			return [ok: false, error: e.message]
		} catch (Throwable e) {
			log.warn("com-morpheus: job ${jobId} status failed", e)
			return [ok: false, error: "${e.class.simpleName}: ${e.message}".toString()]
		}
	}

	private Map matched(Map base, ComClient c, ComSettings s, String comId, String how, String why) {
		Map server
		try {
			server = c.server(comId, SERVER_TTL_MS)
		} catch (ComException e) {
			if (e.notFound) return base + [state: 'nomatch', reason: "COM has no server with id ${comId}.".toString()]
			throw e
		}
		List<String> notes = []
		Map extra = [:]
		extra.alerts = optional(notes, 'alerts') { c.alerts(comId, ALERTS_TTL_MS) }
		extra.security = optional(notes, 'iLO security') { c.security(comId, SECURITY_TTL_MS) }
		extra.warranty = optional(notes, 'warranty') { ComView.warrantyFor(comId, c.warranties(WARRANTY_TTL_MS)) }
		String bundleId = ComView.bundleId(server)
		if (bundleId) extra.bundle = optional(notes, 'firmware baseline') { c.firmwareBundle(bundleId, BUNDLE_TTL_MS) }
		Map inventory = (Map) optional(notes, 'inventory') { c.inventory(comId, ComIndex.INVENTORY_TTL_MS) }
		extra.group = optional(notes, 'group') {
			ComView.groupName(comId, c.groups(GROUPS_TTL_MS)) { String gid -> c.groupDevices(gid, GROUPS_TTL_MS) }
		}
		return base + ComView.model(server, extra, s) + [inv: ComInventoryView.model(inventory), actionsEnabled: s.enableActions && how != 'probable'] + [state: 'matched', match: how, matchWhy: why,
			matchLevel: how == 'probable' ? 'warn' : 'ok', notes: notes]
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
