package io.github.grahamfairbank.commorpheus

import com.morpheusdata.core.AbstractServerTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * "HPE COM" tab on physical HPE hypervisor host pages (Infrastructure > Hosts).
 * Rendered server-side in one pass: no browser calls to COM and no plugin routes in phase 1.
 */
@Slf4j
class ComServerTabProvider extends AbstractServerTabProvider {

	ComMorpheusPlugin plugin
	MorpheusContext morpheus

	ComServerTabProvider(ComMorpheusPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }
	@Override String getCode() { 'com-morpheus-server-tab' }
	@Override String getName() { 'HPE COM' }

	@Override
	Boolean show(ComputeServer server, User user, Account account) {
		if (server?.id == null) return false
		ComSettings s = plugin.loadSettings()
		if (!s.configured || !ComMorpheusPlugin.canView(s, user)) return false
		HostFacts h = facts(server, false)
		if (!ComService.isHost(h)) {
			log.debug("com-morpheus: HPE COM tab hidden for server ${server.id}: not a hypervisor host (type ${h.typeCode})")
			return false
		}
		boolean ok = s.showOnAnyHost || ComService.isPhysicalHpe(h)
		if (!ok) log.debug("com-morpheus: HPE COM tab hidden for server ${server.id}: not a physical HPE server (${h.vendor} / ${h.product})")
		return ok
	}

	@Override
	HTMLResponse renderTemplate(ComputeServer server) {
		ComSettings s = plugin.loadSettings()
		Map model
		try {
			model = plugin.service.tab(facts(server, true), s)
		} catch (Throwable e) {
			model = [state: 'error', error: ComService.unexpected("tab render for server ${server?.id}", e)]
		}
		if (s.routeDiagnostics) {
			model = model + [routeDiagnostics: true, probeUrls: ComView.probeUrls()]
		}
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = ComView.withFlags(model)
		return getRenderer().renderTemplate('hbs/com-tab', vm)
	}

	/**
	 * Morpheus 9.0.1 can pass show() and renderTemplate() a shallow server (no type or hardware details), so
	 * always read the full record by id first. The tab never relies on the account argument, which 9.0.1 leaves null.
	 */
	HostFacts facts(ComputeServer server, boolean withInterfaces) {
		ComputeServer full = null
		try { full = morpheus.services.computeServer.get(server.id) } catch (Throwable e) { log.debug("com-morpheus: couldn't load server ${server?.id}: ${e}") }
		return HostFactsReader.facts(full ?: server, morpheus, withInterfaces)
	}
}
