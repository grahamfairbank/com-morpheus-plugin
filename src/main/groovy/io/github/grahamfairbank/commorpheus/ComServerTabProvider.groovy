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
		if (!ComService.isHost(h)) return false
		return s.showOnAnyHost || ComService.isPhysicalHpe(h)
	}

	@Override
	HTMLResponse renderTemplate(ComputeServer server) {
		ComSettings s = plugin.loadSettings()
		Map model
		try {
			model = plugin.service.tab(facts(server, true), s)
		} catch (Throwable e) {
			log.warn("com-morpheus: tab render failed for server ${server?.id}", e)
			model = [state: 'error', error: "${e.class.simpleName}: ${e.message}".toString()]
		}
		if (s.routeDiagnostics) {
			model = model + [routeDiagnostics: true, probeUrls: ComView.probeUrls()]
		}
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = ComView.withFlags(model)
		return getRenderer().renderTemplate('hbs/com-tab', vm)
	}

	HostFacts facts(ComputeServer server, boolean withInterfaces) { HostFactsReader.facts(server, morpheus, withInterfaces) }
}
