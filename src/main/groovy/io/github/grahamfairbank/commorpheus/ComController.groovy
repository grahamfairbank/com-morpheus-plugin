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
	static final String PROBE_OPEN = '/comMorpheus/probeOpen'
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
		List<Permission> none = new ArrayList<Permission>()
		List<Route> routes = new ArrayList<Route>()
		routes.add(Route.build(PROBE, 'probe', Permission.build(ComMorpheusPlugin.PERMISSION_CODE, read)))
		routes.add(Route.build(PROBE_OPEN, 'probeOpen', none))
		// Phase 2. Every route needs a permission: Morpheus 9.0.1 never matches a route with none (404).
		// The Dispatcher only checks that the permission key exists, so the methods check levels themselves.
		List<Permission.AccessType> full = Collections.singletonList(Permission.AccessType.full)
		routes.add(Route.build(CAPS, 'caps', Permission.build(ComMorpheusPlugin.PERMISSION_CODE, read)))
		routes.add(Route.build(ACTION, 'action', Permission.build(ComMorpheusPlugin.ACTIONS_PERMISSION, full)))
		routes.add(Route.build(JOB, 'jobStatus', Permission.build(ComMorpheusPlugin.ACTIONS_PERMISSION, full)))
		return routes
	}

	JsonResponse probe(ViewModel<Map> model) { answer(model, 'probe') }

	JsonResponse probeOpen(ViewModel<Map> model) { answer(model, 'probeOpen') }

	// ---- phase 2 actions -------------------------------------------------------------------------

	/** Whether this user may see the action buttons. */
	JsonResponse caps(ViewModel<Map> model) {
		ComSettings s = plugin.loadSettings()
		boolean canAct = s.configured && s.enableActions && level(model, ComMorpheusPlugin.ACTIONS_PERMISSION) == 'full'
		return json(200, [ok: true, canAct: canAct])
	}

	/** POST serverId, action (uidOn | uidOff | collectInventory | iloReset): starts a COM job. */
	JsonResponse action(ViewModel<Map> model) {
		Map denied = guard(model)
		if (denied) return json(403, denied)
		Map host = host(model)
		if (host.error) return json((int) host.status, [ok: false, error: host.error])
		Map r = plugin.service.startAction((HostFacts) host.facts, plugin.loadSettings(), param(model, 'action'), username(model))
		return json(r.ok ? 200 : 409, r)
	}

	/** POST serverId, jobId: the job's progress, only if it belongs to that host's COM server. */
	JsonResponse jobStatus(ViewModel<Map> model) {
		Map denied = guard(model)
		if (denied) return json(403, denied)
		Map host = host(model)
		if (host.error) return json((int) host.status, [ok: false, error: host.error])
		String jobId = param(model, 'jobId')
		if (!(jobId ==~ /[0-9a-fA-F-]{8,64}/)) return json(400, [ok: false, error: 'Missing or invalid jobId.'])
		Map r = plugin.service.jobStatus((HostFacts) host.facts, plugin.loadSettings(), jobId)
		return json(r.ok ? 200 : 409, r)
	}

	/** POST only, actions on, full access to the actions permission. Returns null when allowed. */
	Map guard(ViewModel<Map> model) {
		String method = safe { model?.request?.method } as String
		if (method && method != 'POST') return [ok: false, error: 'POST only.']
		ComSettings s = plugin.loadSettings()
		if (!s.enableActions) return [ok: false, error: 'Actions are turned off in the plugin settings.']
		if (level(model, ComMorpheusPlugin.ACTIONS_PERMISSION) != 'full') return [ok: false, error: 'Your role needs "com-morpheus: Run HPE COM actions" at full access.']
		return null
	}

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
		return [facts: HostFactsReader.facts(server, morpheus, true)]
	}

	static String level(ViewModel<Map> model, String code) {
		safe { model?.user?.permissions?.get(code) } as String
	}

	static String param(ViewModel<Map> model, String name) {
		String v = safe { model?.request?.getParameter(name) } as String
		if (v == null) v = safe { ((Map) model?.object)?.get(name) } as String
		return v?.trim()
	}

	static String username(ViewModel<Map> model) { (safe { model?.user?.username } ?: 'unknown') as String }

	static JsonResponse json(int status, Map data) {
		JsonResponse r = JsonResponse.of(data)
		r.status = status
		return r
	}

	private JsonResponse answer(ViewModel<Map> model, String which) {
		Map out = [ok: true, route: which, plugin: ComMorpheusPlugin.CODE,
		           method: safe { model?.request?.method }, uri: safe { model?.request?.requestURI },
		           user: safe { model?.user?.username },
		           permission: safe { model?.user?.permissions?.get(ComMorpheusPlugin.PERMISSION_CODE) },
		           at: java.time.Instant.now().toString()]
		log.info("com-morpheus: route probe ${which} ${out.method} ${out.uri} by ${out.user}")
		return JsonResponse.of(out)
	}

	static Object safe(Closure<Object> c) {
		try { return c() } catch (Throwable ignored) { return null }
	}
}
