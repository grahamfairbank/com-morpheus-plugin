package io.github.grahamfairbank.commorpheus

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/** A COM call that failed. The message is safe to show to users (no secrets, short). */
class ComException extends RuntimeException {
	final int status
	final Integer retryAfterSeconds

	ComException(String message, int status = 0, Integer retryAfterSeconds = null) {
		super(message)
		this.status = status
		this.retryAfterSeconds = retryAfterSeconds
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
 *
 * Endpoints and field quirks were confirmed against a live COM workspace on 2026-10-22.
 */
@Slf4j
class ComClient {

	static final String BASE = '/compute-ops-mgmt'
	static final int PAGE = 100
	static final int MAX_PAGES = 50

	final ComSettings settings
	private final ComTransport transport
	private final Closure<Long> clock

	private String token
	private long tokenExpiresAt
	private String workingTokenUrl
	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>()

	private static class CacheEntry {
		final long at
		final Object data
		CacheEntry(long at, Object data) { this.at = at; this.data = data }
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
		return listAll("${BASE}/v1/servers?select=${enc('id,name,hardware,host,state,maintenanceMode,connectionType,lastFullInventoryCollectionAt')}", ttlMs)
	}

	Map server(String id, long ttlMs) { (Map) getJson("${BASE}/v1/servers/${enc(id)}", ttlMs) }

	/** The cluster summary's server list (0.4.0): the same query as {@link #servers}. */
	List<Map> serverSummaries(long ttlMs) { servers(ttlMs) }

	/**
	 * Active (uncleared) alerts across the workspace, with only the fields needed to count them per server
	 * (0.4.0). COM documents `clearedAt eq null` and `select` for this collection, but some workspaces answer
	 * HTTP 400 to it (seen 2026-10-26, with and without the filter); callers then read alerts per server.
	 */
	List<Map> activeAlerts(long ttlMs) {
		return listAll("${BASE}/v1/alerts?filter=${enc('clearedAt eq null')}&select=${enc('id,serverId,device/id,clearedAt')}", ttlMs, 1000)
	}

	List<Map> alerts(String id, long ttlMs) { listAll("${BASE}/v1/servers/${enc(id)}/alerts", ttlMs, 1000) }

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
			synchronized (this) { token = null }
			r = send('POST', "${BASE}/v1/jobs", body)
		}
		if (r.status != 200 && r.status != 201) {
			if (r.status == 403) throw new ComException('COM refused to start the job: the API client needs a COM role that can run server jobs (HTTP 403)', 403)
			throw failure(r, 'COM job')
		}
		return (Map) new JsonSlurper().parseText(r.body)
	}

	/** Current state of a job. Never cached. */
	Map job(String jobId) { (Map) getJson("${BASE}/v1/jobs/${enc(jobId)}", 0L) }

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
		if (ttlMs <= 0) return listPages(path, ttlMs, pageSize)
		Object lock = listLocks.computeIfAbsent(path) { new Object() }
		synchronized (lock) { return listPages(path, ttlMs, pageSize) }   // a waiter then finds the pages cached
	}

	private List<Map> listPages(String path, long ttlMs, int pageSize) {
		List<Map> all = []
		int offset = 0
		for (int page = 0; page < MAX_PAGES; page++) {
			String sep = path.contains('?') ? '&' : '?'
			Map body = (Map) getJson("${path}${sep}offset=${offset}&limit=${pageSize}", ttlMs)
			List items = (List) (body?.items ?: [])
			all.addAll(items as List<Map>)
			Integer total = body?.total as Integer
			offset += items.size()
			if (items.isEmpty() || (total != null && offset >= total)) break
		}
		return all
	}

	Object getJson(String pathAndQuery, long ttlMs) {
		long now = clock.call()
		CacheEntry hit = cache[pathAndQuery]
		if (hit && ttlMs > 0 && now - hit.at < ttlMs) return hit.data

		HttpResult r = get(pathAndQuery)
		if (r.status == 401) {
			synchronized (this) { token = null }
			r = get(pathAndQuery)
		}
		if (r.status != 200) throw failure(r, "COM ${shortPath(pathAndQuery)}")
		Object data
		try {
			data = new JsonSlurper().parseText(r.body)
		} catch (Exception e) {
			throw new ComException("COM ${shortPath(pathAndQuery)} returned something that isn't JSON", r.status)
		}
		if (ttlMs > 0) cache[pathAndQuery] = new CacheEntry(now, data) // ttl 0 = never reused, so never stored
		return data
	}

	private HttpResult get(String pathAndQuery) { send('GET', pathAndQuery, null) }

	private HttpResult send(String method, String pathAndQuery, String jsonBody) {
		Map<String, String> h = ['Authorization': "Bearer ${accessToken()}".toString(), 'Accept': 'application/json']
		if (jsonBody != null) h['Content-Type'] = 'application/json'
		if (settings.tenantAcid) h['Tenant-Acid'] = settings.tenantAcid
		try {
			return transport.send(method, settings.apiUrl + pathAndQuery, h, jsonBody)
		} catch (Exception e) {
			// Full detail (proxy, DNS, TLS) goes to the log only.
			log.warn("com-morpheus: request to ${settings.apiUrl} failed: ${e}")
			throw new ComException("Couldn't reach COM at ${settings.apiUrl} (${e.class.simpleName}). Details are in the Morpheus log.")
		}
	}

	/** A valid access token, signing in if needed. */
	synchronized String accessToken() {
		long now = clock.call()
		if (token && now < tokenExpiresAt - 60_000L) return token
		if (!settings.configured) throw new ComException('The plugin has no COM client ID and secret yet')

		List<String> urls = settings.tokenUrls()
		if (workingTokenUrl && urls.contains(workingTokenUrl)) urls = [workingTokenUrl] + (urls - workingTokenUrl)
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
				try { body = (Map) new JsonSlurper().parseText(r.body) } catch (Exception ignored) { }
				if (body?.access_token) {
					token = body.access_token as String
					long life = ((body.expires_in ?: 900) as long) * 1000L
					tokenExpiresAt = now + life
					if (workingTokenUrl != url) log.info("com-morpheus: signed in to GreenLake via ${hostOf(url)}")
					workingTokenUrl = url
					return token
				}
			}
			tried << "${hostOf(url)}: HTTP ${r.status}".toString()
		}
		throw new ComException("Couldn't sign in to GreenLake with the configured client (${tried.join('; ')}). " +
			'Check the client ID and secret, or set the workspace ID for new-style workspaces.')
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
		String h = r.header('Retry-After')
		return h?.isInteger() ? h as Integer : null
	}

	private static String shortPath(String p) { p.replaceFirst('^/compute-ops-mgmt', '').replaceFirst('\\?.*$', '') }

	private static String hostOf(String url) {
		try { return new URI(url).host } catch (Exception ignored) { return url }
	}

	/** URL-encodes a path segment or value. COM server ids contain '+', which must be %2B. */
	/** Percent-encodes a path segment or query value; spaces become %20 (not '+'), as COM's OData filters expect. */
	static String enc(String s) { URLEncoder.encode(s ?: '', 'UTF-8').replace('+', '%20') }
}
