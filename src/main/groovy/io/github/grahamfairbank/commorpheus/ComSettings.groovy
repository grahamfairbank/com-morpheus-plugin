package io.github.grahamfairbank.commorpheus

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

/**
 * The plugin's settings (Administration > Integrations > Plugins > com-morpheus), parsed on each use,
 * plus the URLs derived from them. Free of VME classes so it can be unit tested on its own.
 */
@CompileStatic
class ComSettings {

	// Field names used in ComMorpheusPlugin's option types. VME stores settings as JSON keyed by these.
	static final String API_URL = 'comApiUrl'
	static final String CLIENT_ID = 'comClientId'
	static final String CLIENT_SECRET = 'comClientSecret'
	static final String WORKSPACE_ID = 'comWorkspaceId'
	static final String TOKEN_URL = 'comTokenUrl'
	static final String TENANT_ACID = 'comTenantAcid'
	static final String UI_URL = 'comUiUrl'
	static final String PROXY_URL = 'proxyUrl'
	static final String REQUIRE_PERMISSION = 'requirePermission'
	static final String ANY_HOST = 'showOnAnyHost'
	static final String ROUTE_DIAGNOSTICS = 'routeDiagnostics'
	static final String ENABLE_ACTIONS = 'enableActions'

	static final String DEFAULT_API_URL = 'https://us-west.api.greenlake.hpe.com'
	static final String LEGACY_TOKEN_URL = 'https://sso.common.cloud.hpe.com/as/token.oauth2'

	/**
	 * COM web UI host per API region. Only us-west was confirmed (2026-10-23, serverDetail link);
	 * the others follow the same pattern and can be overridden with the COM UI URL setting.
	 */
	static final Map<String, String> UI_BY_REGION = [
		'us-west'     : 'https://us-west2.compute.cloud.hpe.com',
		'eu-central'  : 'https://eu-central1.compute.cloud.hpe.com',
		'ap-northeast': 'https://ap-northeast1.compute.cloud.hpe.com'
	]

	final String apiUrl
	final String clientId
	final String clientSecret
	final String workspaceId
	final String tokenUrl
	final String tenantAcid
	final String uiUrl
	final String proxyUrl
	final boolean requirePermission
	final boolean showOnAnyHost
	final boolean routeDiagnostics
	final boolean enableActions

	private ComSettings(Map m) {
		apiUrl = httpsUrl(str(m[API_URL])) ?: DEFAULT_API_URL
		clientId = str(m[CLIENT_ID])
		clientSecret = str(m[CLIENT_SECRET])
		workspaceId = str(m[WORKSPACE_ID])
		tokenUrl = httpsUrl(str(m[TOKEN_URL]))
		tenantAcid = str(m[TENANT_ACID])
		uiUrl = httpsUrl(str(m[UI_URL])) ?: defaultUiUrl(apiUrl)
		proxyUrl = httpUrl(str(m[PROXY_URL]))
		requirePermission = bool(m[REQUIRE_PERMISSION])
		showOnAnyHost = bool(m[ANY_HOST])
		routeDiagnostics = bool(m[ROUTE_DIAGNOSTICS])
		enableActions = bool(m[ENABLE_ACTIONS])
	}

	/** Parses VME's settings JSON. Bad or missing JSON gives an unconfigured instance, never an exception. */
	static ComSettings fromJson(String json) {
		Map m = [:]
		if (json?.trim()) {
			try {
				Object parsed = new JsonSlurper().parseText(json)
				if (parsed instanceof Map) m = (Map) parsed
			} catch (Exception ignored) {
				// fall through with defaults
			}
		}
		return new ComSettings(m)
	}

	static ComSettings fromMap(Map m) { new ComSettings(m ?: [:]) }

	/** True when there is enough to call COM. */
	boolean isConfigured() { clientId && clientSecret }

	/**
	 * Token endpoints to try, in order: the override if set; else, with a workspace ID, the workspace issuers first
	 * and legacy SSO last (0.4.2). Since 2026-10-29 some HPE services reject legacy-SSO tokens on reads.
	 */
	List<String> tokenUrls() {
		if (tokenUrl) return [tokenUrl]
		List<String> urls = []
		if (workspaceId) {
			urls << "https://global.api.greenlake.hpe.com/authorization/v2/oauth2/${workspaceId}/token".toString()
			urls << "https://aquila-org-api.common.cloud.hpe.com/authorization/v2/oauth2/${workspaceId}/token".toString()
		}
		urls << LEGACY_TOKEN_URL
		return urls
	}

	/** COM UI page for a server. Confirmed format: <ui>/serverDetail/<id>, with the id's '+' kept as is. */
	String serverPageUrl(String comId) {
		if (!comId) return null
		return "${uiUrl}/serverDetail/${comId}".toString()
	}

	/** Same identity whenever the values that affect API calls are the same; used to reset caches. */
	String connectionKey() { [apiUrl, clientId, clientSecret, workspaceId, tokenUrl, tenantAcid, proxyUrl].join('|') }

	static String defaultUiUrl(String apiUrl) {
		String host = hostOf(apiUrl)
		String region = host?.endsWith('.api.greenlake.hpe.com') ? host.substring(0, host.indexOf('.')) : null
		return UI_BY_REGION[region] ?: UI_BY_REGION['us-west']
	}

	private static String hostOf(String url) {
		try { return url ? new URI(url).host : null } catch (Exception ignored) { return null }
	}

	private static String str(Object o) {
		String s = o?.toString()?.trim()
		return s ? s : null
	}

	private static boolean bool(Object o) {
		String s = o?.toString()?.trim()?.toLowerCase()
		return s in ['on', 'true', '1', 'yes']
	}

	/** https URL without trailing slashes, or null. */
	private static String httpsUrl(String s) {
		if (!s) return null
		String u = s.replaceAll('/+$', '')
		return u.toLowerCase().startsWith('https://') && hostOf(u) ? u : null
	}

	private static String httpUrl(String s) {
		if (!s) return null
		String u = s.replaceAll('/+$', '')
		return (u.toLowerCase().startsWith('http://') || u.toLowerCase().startsWith('https://')) && hostOf(u) ? u : null
	}
}
