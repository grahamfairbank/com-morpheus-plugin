package io.github.grahamfairbank.commorpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/**
 * Plugin web routes. The probe routes are harmless diagnostics: they prove that the browser can reach a
 * plugin route on the target VME before phase 2's buttons depend on it. It reads and changes nothing.
 *
 * Note: VME's Dispatcher looks routes up in Plugin.getControllers(), which registerProvider() doesn't
 * fill, so the plugin also passes this controller to setControllers().
 */
@Slf4j
class ComController implements PluginController {

	static final String PROBE = '/comMorpheus/probe'
	static final String CAPS = '/comMorpheus/caps'
	static final String ACTION = '/comMorpheus/action'
	static final String JOB = '/comMorpheus/job'

	ComMorpheusPlugin plugin
	MorpheusContext morpheus

	ComController(ComMorpheusPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override String getCode() { 'com-morpheus-controller' }
	@Override String getName() { 'com-morpheus routes' }
	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }

	// Statically compiled: dynamic Permission/Route resolution has thrown MissingPropertyException inside
	// Morpheus for other plugins (lesson from github.com/builtbyfood/gfs2-rescue).
	@groovy.transform.CompileStatic
	@Override
	List<Route> getRoutes() {
		List<Permission.AccessType> read = Collections.singletonList(Permission.AccessType.read)
		List<Route> routes = new ArrayList<Route>()
		routes.add(Route.build(PROBE, 'probe', Permission.build(ComMorpheusPlugin.PERMISSION_CODE, read)))
		// Phase 2. Every route needs a permission: Morpheus 9.0.1 never matches a route with none (404).
		// The Dispatcher only checks that the permission key exists, so the methods check levels themselves.
		List<Permission.AccessType> full = Collections.singletonList(Permission.AccessType.full)
		// /caps answers only to roles that have the actions permission at all; others get 404 and no buttons.
		routes.add(Route.build(CAPS, 'caps', Permission.build(ComMorpheusPlugin.ACTIONS_PERMISSION, full)))
		routes.add(Route.build(ACTION, 'action', Permission.build(ComMorpheusPlugin.ACTIONS_PERMISSION, full)))
		routes.add(Route.build(JOB, 'jobStatus', Permission.build(ComMorpheusPlugin.ACTIONS_PERMISSION, full)))
		return routes
	}

	JsonResponse probe(ViewModel<Map> model) { answer(model, 'probe') }

	// ---- phase 2 actions -------------------------------------------------------------------------

	/** Whether this user may see the action buttons. */
	JsonResponse caps(ViewModel<Map> model) {
		Map bad = requestCheck(model)
		if (bad) return json(403, bad)
		if (!plugin.service.allowRoute(username(model))) return json(429, TOO_MANY)
		ComSettings s = plugin.loadSettings()
		boolean canAct = s.configured && s.enableActions && level(model, ComMorpheusPlugin.ACTIONS_PERMISSION) == 'full'
		return json(200, [ok: true, canAct: canAct])
	}

	/** POST serverId, action (uidOn | uidOff | collectInventory | iloReset): starts a COM job. */
	JsonResponse action(ViewModel<Map> model) {
		Map denied = guard(model)
		if (denied) return json(denied.is(TOO_MANY) ? 429 : 403, denied)
		Map host = host(model)
		if (host.error) return json((int) host.status, [ok: false, error: host.error])
		if (!plugin.service.validToken(((HostFacts) host.facts).id, param(model, 'token'))) return json(403, [ok: false, error: 'Request refused: reopen the HPE COM tab and try again.'])
		Map r = plugin.service.startAction((HostFacts) host.facts, plugin.loadSettings(), param(model, 'action'), username(model))
		return json(r.ok ? 200 : 409, r)
	}

	/** POST serverId, jobId: the job's progress, only if it belongs to that host's COM server. */
	JsonResponse jobStatus(ViewModel<Map> model) {
		Map denied = guard(model)
		if (denied) return json(denied.is(TOO_MANY) ? 429 : 403, denied)
		Map host = host(model)
		if (host.error) return json((int) host.status, [ok: false, error: host.error])
		if (!plugin.service.validToken(((HostFacts) host.facts).id, param(model, 'token'))) return json(403, [ok: false, error: 'Request refused: reopen the HPE COM tab and try again.'])
		String jobId = param(model, 'jobId')
		if (!(jobId ==~ /[0-9a-fA-F-]{8,64}/)) return json(400, [ok: false, error: 'Missing or invalid jobId.'])
		Map r = plugin.service.jobStatus((HostFacts) host.facts, plugin.loadSettings(), jobId)
		return json(r.ok ? 200 : 409, r)
	}

	/**
	 * The plugin's own defense against cross-site and forged requests (0.3.1), on top of Morpheus's CSRF filter:
	 * POST only (an unknown method is refused), the X-Requested-With header that a cross-site form or image
	 * can't send, and, when the browser says where the request came from, the same origin as the appliance.
	 * Returns null when the request passes.
	 */
	static Map requestCheck(ViewModel<Map> model) {
		def req = safe { model?.request }
		if (req == null) return [ok: false, error: 'Request refused.']
		if ((safe { req.method } as String) != 'POST') return [ok: false, error: 'POST only.']
		if ((safe { req.getHeader('X-Requested-With') } as String) != 'XMLHttpRequest') return [ok: false, error: 'Request refused: missing X-Requested-With header.']
		if (!sameOrigin(req)) return [ok: false, error: 'Request refused: cross-origin.']
		return null
	}

	/**
	 * Origin (or, failing that, Referer) must name this appliance: its host name must equal the Host,
	 * X-Forwarded-Host or server name (ports ignored, as proxies add or drop :443). No header at all is allowed
	 * (X-Requested-With already rules out cross-site forms); the literal "null" origin is refused.
	 */
	static boolean sameOrigin(req) {
		String from = (safe { req.getHeader('Origin') } ?: safe { req.getHeader('Referer') }) as String
		if (!from) return true
		if (from == 'null') return false
		String fromHost
		try { fromHost = new URI(from).host?.toLowerCase() } catch (Exception e) { return false }
		if (!fromHost) return false
		Set<String> ours = [safe { req.getHeader('Host') }, safe { req.getHeader('X-Forwarded-Host') }, safe { req.serverName }]
			.findAll().collect { hostOnly(it as String) }.findAll() as Set
		return ours.contains(fromHost)
	}

	/** "a.example.com:8443" -> "a.example.com"; "[::1]:443" -> "[::1]"; first value of a comma list. */
	static String hostOnly(String h) {
		String v = h?.split(',')?.getAt(0)?.trim()?.toLowerCase()
		if (!v) return null
		if (v.startsWith('[')) return v.substring(0, v.indexOf(']') + 1)
		return v.replaceFirst(/:\d+$/, '')
	}

	/** POST from this appliance (requestCheck), actions on, full access to the actions permission. Null when allowed. */
	Map guard(ViewModel<Map> model) {
		Map bad = requestCheck(model)
		if (bad) return bad
		ComSettings s = plugin.loadSettings()
		if (!s.enableActions) return [ok: false, error: 'Actions are turned off in the plugin settings.']
		if (level(model, ComMorpheusPlugin.ACTIONS_PERMISSION) != 'full') return [ok: false, error: 'Your role needs "com-morpheus: Run HPE COM actions" at full access.']
		if (!plugin.service.allowRoute(username(model))) return TOO_MANY
		return null
	}

	/** Per-user route limit reached (0.4.3). */
	static final Map TOO_MANY = Collections.unmodifiableMap([ok: false, error: 'Too many requests. Wait a minute, then try again.'])

	/** The host named by serverId, if it exists and the user's tenant owns it (or the user is in the master tenant). */
	Map host(ViewModel<Map> model) {
		String id = param(model, 'serverId')
		if (!(id ==~ /\d{1,18}/)) return [status: 400, error: 'Missing or invalid serverId.']
		def server = safe { morpheus.services.computeServer.get(id as Long) }
		if (server == null) return [status: 404, error: 'No such host.']
		def account = safe { model?.user?.account }
		boolean master = safe { account?.masterAccount } == true
		Long mine = safe { account?.id } as Long
		Long owner = safe { server.account?.id } as Long
		if (!master && (mine == null || mine != owner)) return [status: 404, error: 'No such host.']
		HostFacts facts = HostFactsReader.facts(server, morpheus, true)
		if (!ComService.isHost(facts)) return [status: 404, error: 'No such host.']
		return [facts: facts]
	}

	static String level(ViewModel<Map> model, String code) {
		safe { model?.user?.permissions?.get(code) } as String
	}

	static String param(ViewModel<Map> model, String name) {
		String v = safe { model?.request?.getParameter(name) } as String
		return v?.trim()
	}

	static String username(ViewModel<Map> model) { (safe { model?.user?.username } ?: 'unknown') as String }

	static JsonResponse json(int status, Map data) {
		JsonResponse r = JsonResponse.of(data)
		r.status = status
		return r
	}

	private JsonResponse answer(ViewModel<Map> model, String which) {
		Map out = [ok: true, route: which, method: safe { model?.request?.method }]
		log.debug("com-morpheus: route probe ${which} ${out.method} by ${safe { model?.user?.username }}")
		return JsonResponse.of(out)
	}

	static Object safe(Closure<Object> c) {
		try { return c() } catch (Throwable ignored) { return null }
	}
}
