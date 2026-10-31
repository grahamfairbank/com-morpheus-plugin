package io.github.grahamfairbank.commorpheus

import groovy.json.JsonSlurperClassic
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/** A COM call that failed. The message is safe to show to users (no secrets, short). */
class ComException extends RuntimeException {
	final int status
	final Integer retryAfterSeconds
	/** COM couldn't be reached or sign-in failed: not about one server's data. */
	final boolean outage

	ComException(String message, int status = 0, Integer retryAfterSeconds = null, boolean outage = false) {
		super(message)
		this.status = status
		this.retryAfterSeconds = retryAfterSeconds
		this.outage = outage
	}

	boolean isRateLimited() { status == 429 }
	boolean isNotFound() { status == 404 }
}

/**
 * Read-only HPE Compute Ops Management API client.
 *
 * - Signs in with OAuth2 client credentials. The token URL is auto-detected (see ComSettings.tokenUrls)
 *   and the one that worked is tried first next time. Tokens are reused until a minute before expiry.
 * - GET responses are cached per path for the TTL the caller gives.
 * - 401 drops the token and retries once; 429 raises ComException with the server's retry hint.
 * - Parsed with JsonSlurperClassic (plain HashMap/ArrayList), because parsed answers are cached and shared
 *   between threads, and the default parser's lazy maps aren't safe for that (C6).
 * - A failed sign-in or an unreachable COM is remembered for FAIL_FAST_MS: page renders then fail at once with
 *   the same message instead of each waiting out the timeouts (C3). Background work ({@link #inBackground})
 *   still tries on its own schedule.
 *
 * Endpoints and field quirks were confirmed against a live COM workspace on 2026-10-22.
 */
@Slf4j
class ComClient {

	static final String BASE = '/compute-ops-mgmt'
	static final int PAGE = 100
	/** Largest page COM accepts on any collection since 2026-10-29 (alerts were 1000; servers are 100). */
	static final int MAX_LIMIT = 500
	static final int MAX_PAGES = 50

	final ComSettings settings
	private final ComTransport transport
	private final Closure<Long> clock

	private String token
	private long tokenExpiresAt
	private String workingTokenUrl
	/** Token URLs whose tokens COM refused on a read (HTTP 401); skipped until every URL has been refused. */
	private final Set<String> refusedTokenUrls = new LinkedHashSet<>()
	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>()

	/** How long a sign-in or connection failure is remembered for page renders (C3). */
	static final long FAIL_FAST_MS = 45_000L

	private static class Failure {
		final long at
		final String message
		final int status
		Failure(long at, String message, int status) { this.at = at; this.message = message; this.status = status }
	}
	private volatile Failure signInFailure
	private volatile Failure connectFailure

	/** Set while the background index or group build runs: it isn't failed fast, so it can find COM back. */
	private static final ThreadLocal<Boolean> BACKGROUND = ThreadLocal.withInitial { false }

	/** Runs work as background work: remembered failures don't stop its calls (they're still recorded). */
	static <T> T inBackground(Closure<T> work) {
		boolean was = BACKGROUND.get()
		BACKGROUND.set(true)
		try { return work.call() } finally { BACKGROUND.set(was) }
	}

	private static boolean failFast() { !BACKGROUND.get() }

	/** Rethrows a remembered failure, if it's recent and this is a page-render call. */
	private void recentFailure(Failure f) {
		if (f != null && failFast() && clock.call() - f.at < FAIL_FAST_MS) throw new ComException(f.message, f.status, null, true)
	}

	/**
	 * Most responses kept at once (0.5.0). Expired ones are dropped first, then the oldest, so memory follows what
	 * people look at rather than the size of the COM workspace.
	 */
	static final int MAX_CACHE_ENTRIES = 1000

	private static class CacheEntry {
		final long at
		final long ttlMs
		final Object data
		CacheEntry(long at, long ttlMs, Object data) { this.at = at; this.ttlMs = ttlMs; this.data = data }
	}

	ComClient(ComSettings settings, ComTransport transport, Closure<Long> clock = { System.currentTimeMillis() }) {
		this.settings = settings
		this.transport = transport
		this.clock = clock
	}

	// ---- endpoints (all read-only) -------------------------------------------------------------

	/**
	 * Every server in the workspace (paged), with only the fields the index, matching and cluster summary need.
	 * One query for both, so a cluster summary can reuse a fresh index read and vice versa.
	 */
	List<Map> servers(long ttlMs) {
		return listAll("${BASE}/v1/servers?select=${enc(SERVER_SELECT)}".toString(), ttlMs)
	}

	/**
	 * One server's full record. Since 2026-10-29, GET /v1/servers/{id} can answer HTTP 500 for servers that COM's
	 * own console shows as healthy; on a 5xx this tries the published /v1beta2 path, then the id with a raw '+'.
	 * Throws the first error if all fail (callers can fall back to the server-list entry).
	 */
	Map server(String id, long ttlMs) {
		List<String> paths = ["${BASE}/v1/servers/${enc(id)}", "${BASE}/v1beta2/servers/${enc(id)}",
		                      "${BASE}/v1/servers/${enc(id).replace('%2B', '+')}"].collect { it.toString() }.unique()
		ComException first = null
		for (String p : paths) {
			try {
				Map m = (Map) getJson(p, ttlMs)
				if (first) log.info("com-morpheus: server ${id} read via ${shortPath(p)} after ${first.message}")
				return m
			} catch (ComException e) {
				if (first == null) first = e
				if (e.status < 500) throw first   // only a server-side failure is worth another path
			}
		}
		throw first
	}

	/** The cluster summary's server list (0.4.0): the same query as {@link #servers}. */
	List<Map> serverSummaries(long ttlMs) { servers(ttlMs) }

	/** Ids per filtered server read: 50 measured to work (2026-10-31), and it keeps the URL short. */
	static final int ID_CHUNK = 50
	static final String SERVER_SELECT = 'id,name,hardware,host,state,maintenanceMode,connectionType,lastFullInventoryCollectionAt'

	/**
	 * Only these servers, with the same fields as {@link #servers} (C4): `filter=id in ('a','b')`, 50 ids per read,
	 * each read cached for ttlMs under its own id set. Throws ComException (status 400 when COM refuses the filter;
	 * callers then fall back to the whole list).
	 */
	List<Map> serversById(Collection<String> ids, long ttlMs) {
		List<String> sorted = ((ids ?: []).findAll { it } as List<String>).unique().sort()
		List<Map> out = []
		sorted.collate(ID_CHUNK).each { List<String> chunk ->
			String filter = "id in (${chunk.collect { "'${it.replace("'", "''")}'" }.join(',')})"
			out.addAll(listAll("${BASE}/v1/servers?filter=${enc(filter)}&select=${enc(SERVER_SELECT)}".toString(), ttlMs))
		}
		return out
	}

	/**
	 * Active (uncleared) alerts across the workspace, with only the fields needed to count them per server
	 * (0.4.0). COM documents `clearedAt eq null` and `select` for this collection, but some workspaces answer
	 * HTTP 400 to it (seen 2026-10-26, with and without the filter); callers then read alerts per server.
	 */
	List<Map> activeAlerts(long ttlMs) {
		// No select: since 2026-10-29 COM refuses select=serverId,device/id ("Cannot select unknown properties"),
		// although its API reference still lists both. Callers read the server id from the full item (alertServerId).
		return listAll("${BASE}/v1/alerts?filter=${enc('clearedAt eq null')}", ttlMs, MAX_LIMIT)
	}

	List<Map> alerts(String id, long ttlMs) { listAll("${BASE}/v1/servers/${enc(id)}/alerts", ttlMs, MAX_LIMIT) }

	/** The COM server id an alert belongs to: serverId, device.id, server.id or the id in its resourceUri. */
	static String alertServerId(Map a) {
		String id = (a?.serverId ?: ((Map) (a?.device instanceof Map ? a.device : [:])).id ?: ((Map) (a?.server instanceof Map ? a.server : [:])).id) as String
		if (id) return id
		def m = ((a?.resourceUri ?: '') as String) =~ '/servers/([^/]+)/alerts'
		return m.find() ? URLDecoder.decode(m.group(1), 'UTF-8') : null
	}

	Map security(String id, long ttlMs) { (Map) getJson("${BASE}/v1/servers/${enc(id)}/security-parameters", ttlMs) }

	/** Raw-Redfish inventory. Large (about 100 KB per server), so callers cache it for hours. */
	Map inventory(String id, long ttlMs) { (Map) getJson("${BASE}/v1/servers/${enc(id)}/inventory", ttlMs) }

	/** All warranty records. The deviceId filter is ignored by COM, so callers match deviceId themselves. */
	List<Map> warranties(long ttlMs) { listAll("${BASE}/v1beta2/server-warranty", ttlMs) }

	Map firmwareBundle(String bundleId, long ttlMs) { (Map) getJson("${BASE}/v1/firmware-bundles/${enc(bundleId)}", ttlMs) }

	List<Map> groups(long ttlMs) { listAll("${BASE}/v1/groups", ttlMs) }

	/** A group's members. The list endpoint returns groups without members, so this is needed for "Group". */
	Object groupDevices(String groupId, long ttlMs) { getJson("${BASE}/v1/groups/${enc(groupId)}/devices", ttlMs) }

	// ---- jobs (the only writes; phase 2) ----------------------------------------------------------

	/** Job templates the plugin may start. Template ids are the same in every COM region. */
	static final Map<String, String> JOB_TEMPLATES = [
		uidOn           : 'a46b210a-b0c7-4223-ab43-c4c1e77e680c',
		uidOff          : 'fd837434-a2f2-4bc8-b1b4-ec068bd036aa',
		collectInventory: 'd6595f1b-84e6-4587-ade5-656e2a5ea20d',
		iloReset        : '394b492c-8d88-4e4e-a775-1a95cb5f1065',
		// COM maintenance mode: COM's own flag only (suspends automatic support cases, ServiceNow incidents and
		// email notifications for the server). Never coupled to Morpheus maintenance mode (HLD D5).
		comMaintenanceOn : '4eb92af1-1ce4-4cb0-8581-fb5a7dcdbf2b',
		comMaintenanceOff: '2798720f-b090-427d-b210-e48d33ce2f27'
	]

	/** Starts a COM job on a server. Returns the job (id, state, ...). Never cached. */
	Map createJob(String action, String comId) {
		String template = JOB_TEMPLATES[action]
		if (!template) throw new ComException("Unknown action '${action}'")
		String body = groovy.json.JsonOutput.toJson([jobTemplate: template, resourceType: 'compute-ops-mgmt/server', resourceId: comId])
		HttpResult r = send('POST', "${BASE}/v1/jobs", body)
		if (r.status == 401) {
			nextIssuer()
			r = send('POST', "${BASE}/v1/jobs", body)
		}
		if (r.status != 200 && r.status != 201) {
			if (r.status == 403) throw new ComException('COM refused to start the job: the API client needs a COM role that can run server jobs (HTTP 403)', 403)
			throw failure(r, 'COM job')
		}
		return (Map) new JsonSlurperClassic().parseText(r.body)
	}

	/** Current state of a job, reused for at most ttlMs (0 = always fresh). */
	Map job(String jobId, long ttlMs = 0L) { (Map) getJson("${BASE}/v1/jobs/${enc(jobId)}", ttlMs) }

	/** Drops cached responses whose path contains this text (e.g. a server id after an inventory refresh). */
	void invalidate(String contains) {
		String e = enc(contains)
		cache.keySet().removeAll { String k -> k.contains(contains) || k.contains(e) }
	}

	// ---- plumbing -------------------------------------------------------------------------------

	void clearCache() { cache.clear() }

	/** Paged GET of a collection: follows offset until total is reached (or MAX_PAGES as a safety stop). */
	/** One lock per collection, so viewers arriving together share one read instead of each paging it (0.4.0). */
	private final java.util.concurrent.ConcurrentHashMap<String, Object> listLocks = new java.util.concurrent.ConcurrentHashMap<>()

	List<Map> listAll(String path, long ttlMs, int pageSize = PAGE) {
		pageSize = Math.min(pageSize, MAX_LIMIT)
		if (ttlMs <= 0) return listPages(path, ttlMs, pageSize)
		Object lock = listLocks.computeIfAbsent(path) { new Object() }
		synchronized (lock) { return listPages(path, ttlMs, pageSize) }   // a waiter then finds the pages cached
	}

	/** Collections already reported as cut short at MAX_PAGES (warned once each). */
	private final Set<String> truncatedWarned = ConcurrentHashMap.newKeySet()

	private List<Map> listPages(String path, long ttlMs, int pageSize) {
		List<Map> all = []
		int offset = 0
		boolean complete = false
		Integer total = null
		for (int page = 0; page < MAX_PAGES; page++) {
			String sep = path.contains('?') ? '&' : '?'
			Map body = (Map) getJson("${path}${sep}offset=${offset}&limit=${pageSize}", ttlMs)
			List items = (List) (body?.items ?: [])
			all.addAll(items as List<Map>)
			total = body?.total as Integer
			offset += items.size()
			if (items.isEmpty() || (total != null && offset >= total)) { complete = true; break }
		}
		if (!complete) {
			String what = shortPath(path)
			if (truncatedWarned.size() < 100 && truncatedWarned.add(what)) {
				log.warn("com-morpheus: COM ${what} has more than ${MAX_PAGES} pages${total != null ? " (${total} items)" : ''}; only the first ${all.size()} are used")
			}
		}
		return all
	}

	Object getJson(String pathAndQuery, long ttlMs) {
		long now = clock.call()
		CacheEntry hit = cache[pathAndQuery]
		if (hit && ttlMs > 0 && now - hit.at < ttlMs) return hit.data

		HttpResult r = get(pathAndQuery)
		if (r.status == 401) {
			nextIssuer()
			r = get(pathAndQuery)
		}
		if (r.status != 200) throw failure(r, "COM ${shortPath(pathAndQuery)}")
		Object data
		try {
			data = new JsonSlurperClassic().parseText(r.body)
		} catch (Exception e) {
			throw new ComException("COM ${shortPath(pathAndQuery)} returned something that isn't JSON", r.status)
		}
		if (ttlMs > 0) store(pathAndQuery, new CacheEntry(now, ttlMs, data)) // ttl 0 = never reused, so never stored
		return data
	}

	int cacheSize() { cache.size() }

	private void store(String key, CacheEntry e) {
		cache[key] = e
		if (cache.size() <= MAX_CACHE_ENTRIES) return
		synchronized (cache) {
			if (cache.size() <= MAX_CACHE_ENTRIES) return
			long now = clock.call()
			cache.entrySet().removeIf { Map.Entry<String, CacheEntry> x -> now - x.value.at >= x.value.ttlMs }
			int over = cache.size() - (int) (MAX_CACHE_ENTRIES * 0.9)
			if (over > 0) {
				cache.entrySet().sort { Map.Entry<String, CacheEntry> x -> x.value.at }.take(over).each { cache.remove(it.key) }
			}
		}
	}

	private HttpResult get(String pathAndQuery) { send('GET', pathAndQuery, null) }

	private HttpResult send(String method, String pathAndQuery, String jsonBody) {
		recentFailure(connectFailure)
		Map<String, String> h = ['Authorization': "Bearer ${accessToken()}".toString(), 'Accept': 'application/json']
		if (jsonBody != null) h['Content-Type'] = 'application/json'
		if (settings.tenantAcid) h['Tenant-Acid'] = settings.tenantAcid
		HttpResult r
		try {
			r = transport.send(method, settings.apiUrl + pathAndQuery, h, jsonBody)
		} catch (ResponseTooLargeException e) {
			log.warn("com-morpheus: ${shortPath(pathAndQuery)}: ${e.message}")
			throw new ComException("COM ${shortPath(pathAndQuery)} returned more data than the plugin accepts (${e.message}).")
		} catch (Exception e) {
			if (!connectPhase(e)) {
				// A broken or truncated answer (EOF, bad gzip, reset mid-body): this call failed, COM isn't known to be down.
				log.warn("com-morpheus: ${shortPath(pathAndQuery)}: incomplete answer from COM: ${e}")
				throw new ComException("COM ${shortPath(pathAndQuery)} sent an incomplete answer (${e.class.simpleName}). Details are in the Morpheus log.")
			}
			// Full detail (proxy, DNS, TLS) goes to the log only.
			String message = "Couldn't reach COM at ${settings.apiUrl} (${e.class.simpleName}). Details are in the Morpheus log.".toString()
			Failure before = connectFailure
			if (before == null || clock.call() - before.at >= FAIL_FAST_MS) {
				log.warn("com-morpheus: request to ${settings.apiUrl} failed: ${e}; page renders fail fast for ${(int) (FAIL_FAST_MS / 1000)} s")
			} else {
				log.debug("com-morpheus: request to ${settings.apiUrl} failed again: ${e}")
			}
			connectFailure = new Failure(clock.call(), message, 0)
			throw new ComException(message, 0, null, true)
		}
		connectFailure = null   // COM answered (whatever the status), so it's reachable
		return r
	}

	/**
	 * Failures that mean COM (or the proxy) can't be reached at all, so they're remembered for FAIL_FAST_MS:
	 * connect, DNS, TLS and timeouts. Anything else (EOF, a truncated gzip) is one call's problem.
	 */
	static boolean connectPhase(Throwable e) {
		return e instanceof ConnectException || e instanceof UnknownHostException || e instanceof NoRouteToHostException ||
			e instanceof java.net.http.HttpTimeoutException || e instanceof SocketTimeoutException ||
			e instanceof javax.net.ssl.SSLException || e instanceof java.nio.channels.UnresolvedAddressException ||
			(e.cause != null && !e.cause.is(e) && connectPhase(e.cause))   // the JDK client sometimes wraps the cause
	}

	/** A valid access token, signing in if needed. */
	/**
	 * COM refused the current token (HTTP 401): drop it, and skip the token URL that issued it, so the next
	 * request signs in through the next issuer. A legacy-SSO token can be issued and still be refused on reads.
	 */
	synchronized void nextIssuer() {
		if (workingTokenUrl) {
			if (refusedTokenUrls.add(workingTokenUrl)) log.info("com-morpheus: COM refused the token from ${hostOf(workingTokenUrl)}; trying the next sign-in URL")
		}
		token = null
		workingTokenUrl = null
	}

	synchronized String accessToken() {
		long now = clock.call()
		if (token && now < tokenExpiresAt - 60_000L) return token
		if (!settings.configured) throw new ComException('The plugin has no COM client ID and secret yet')
		String problem = settings.problem()
		if (problem) throw new ComException(problem)   // never send the secret outside HPE (0.5.0)
		// Checked under the lock, so a thread that waited here behind a failed sign-in doesn't POST again (C3).
		recentFailure(signInFailure)

		List<String> urls = settings.tokenUrls()
		if (workingTokenUrl && urls.contains(workingTokenUrl)) urls = [workingTokenUrl] + (urls - workingTokenUrl)
		List<String> usable = urls - refusedTokenUrls
		if (usable) urls = usable else refusedTokenUrls.clear()   // all refused: start over
		List<String> tried = []
		for (String url : urls) {
			HttpResult r
			try {
				r = transport.send('POST', url, ['Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json'],
					"grant_type=client_credentials&client_id=${enc(settings.clientId)}&client_secret=${enc(settings.clientSecret)}".toString())
			} catch (Exception e) {
				tried << "${hostOf(url)}: ${e.class.simpleName}".toString()
				continue
			}
			if (r.status == 200) {
				Map body = null
				try { body = (Map) new JsonSlurperClassic().parseText(r.body) } catch (Exception ignored) { }
				if (body?.access_token) {
					token = body.access_token as String
					long life = ((body.expires_in ?: 900) as long) * 1000L
					tokenExpiresAt = now + life
					if (workingTokenUrl != url) log.info("com-morpheus: signed in to GreenLake via ${hostOf(url)}")
					workingTokenUrl = url
					signInFailure = null
					return token
				}
			}
			tried << "${hostOf(url)}: HTTP ${r.status}".toString()
		}
		ComException failed = new ComException("Couldn't sign in to GreenLake with the configured client (${tried.join('; ')}). " +
			'Check the client ID and secret, or set the workspace ID for new-style workspaces.', 0, null, true)
		signInFailure = new Failure(clock.call(), failed.message, 0)
		throw failed
	}

	private static ComException failure(HttpResult r, String what) {
		if (r.status == 429) {
			Integer after = retryAfter(r)
			return new ComException("${what}: rate limited by COM${after ? ", try again in ${after} s" : ''}", 429, after)
		}
		if (r.status == 403) return new ComException("${what}: the COM API client doesn't have permission (HTTP 403)", 403)
		if (r.status == 404) return new ComException("${what}: not found in COM (HTTP 404)", 404)
		// COM's response body can hold internal detail: log it, don't return it.
		log.warn("com-morpheus: ${what}: HTTP ${r.status} ${r.body?.replaceAll('\\s+', ' ')?.take(500)}")
		return new ComException("${what}: HTTP ${r.status}. Details are in the Morpheus log.", r.status)
	}

	private static Integer retryAfter(HttpResult r) {
		def m = (r.body =~ /"retryAfterSeconds"\s*:\s*(\d+)/)
		if (m.find()) return m.group(1) as Integer
		// GreenLake documents ratelimit-reset (seconds until the window resets) rather than Retry-After.
		for (String name : ['Retry-After', 'ratelimit-reset']) {
			String h = r.header(name)?.trim()
			if (h?.isInteger()) return h as Integer
		}
		return null
	}

	private static String shortPath(String p) { p.replaceFirst('^/compute-ops-mgmt', '').replaceFirst('\\?.*$', '') }

	private static String hostOf(String url) {
		try { return new URI(url).host } catch (Exception ignored) { return url }
	}

	/** URL-encodes a path segment or value. COM server ids contain '+', which must be %2B. */
	/** Percent-encodes a path segment or query value; spaces become %20 (not '+'), as COM's OData filters expect. */
	static String enc(String s) { URLEncoder.encode(s ?: '', 'UTF-8').replace('+', '%20') }
}
