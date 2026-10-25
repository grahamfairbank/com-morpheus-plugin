package io.github.grahamfairbank.commorpheus

import com.morpheusdata.model.User
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.ViewModel
import spock.lang.Specification

class ComControllerSpec extends Specification {

	ComController controller = new ComController(null, null)

	def "probe routes: one behind the view permission, one open"() {
		when:
		def routes = controller.routes

		then:
		routes*.url == ['/comMorpheus/probe', '/comMorpheus/probeOpen', '/comMorpheus/caps', '/comMorpheus/action', '/comMorpheus/job']
		routes*.method == ['probe', 'probeOpen', 'caps', 'action', 'jobStatus']
		routes[0].permissions*.code == ['com-morpheus-view']
		routes[1].permissions == []
		routes[2].permissions*.code == ['com-morpheus-view']
		routes[3].permissions*.code == ['com-morpheus-actions']
		routes[4].permissions*.code == ['com-morpheus-actions']
	}

	def "route methods exist with the signature VME's dispatcher calls"() {
		expect: 'Dispatcher uses getClass().getMethod(name, ViewModel)'
		controller.routes.every { r -> controller.class.getMethod(r.method, ViewModel) != null }
	}

	def "probe answers with who called it and changes nothing"() {
		given:
		ViewModel<Map> vm = new ViewModel<>()
		vm.user = new User(username: 'alice')

		when:
		def r = controller.probe(vm)

		then:
		r.data.ok == true
		r.data.route == 'probe'
		r.data.user == 'alice'
		r.data.plugin == 'com-morpheus-plugin'
	}

	def "diagnostics panel renders every probe URL and no inline script"() {
		given:
		HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', getClass().classLoader)
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = ComView.withFlags([state: 'unconfigured', routeDiagnostics: true, probeUrls: ComView.probeUrls()])

		when:
		String html = renderer.renderTemplate('hbs/com-tab', vm).html.replace('&#x2F;', '/').replace('&#x3D;', '=')

		then: 'the script comes from the global loader, not the tab'
		ComView.probeUrls().every { html.contains("href=\"${it}\"") }
		html.contains('id="vc-diag-run"')
		!html.contains('<script')
	}

	def "global loader includes the diagnostics asset with the CSP nonce"() {
		given:
		HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', getClass().classLoader)
		renderer.registerAssetHelper('com-morpheus')
		renderer.registerNonceHelper(Stub(com.morpheusdata.core.web.MorpheusWebRequestService) { getNonceToken() >> 'n0nce' })
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = [version: '0.1.3']

		when:
		String html = renderer.renderTemplate('hbs/com-loader', vm).html.replace('&#x2F;', '/').replace('&#x3D;', '=')

		then:
		html.contains('<script nonce="n0nce" src="/assets/plugin/com-morpheus/com-morpheus.js?v=0.1.3"></script>')
	}

	def "the diagnostics asset is packaged"() {
		expect: 'asset-pipeline serves src/assets/javascript/*.js from the jar root assets/ folder; the source is checked here'
		new File('src/assets/javascript/com-morpheus.js').text.contains("'X-XSRF-TOKEN'")
	}

	def "no diagnostics panel unless the setting is on"() {
		given:
		HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', getClass().classLoader)
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = ComView.withFlags([state: 'unconfigured'])

		expect:
		!renderer.renderTemplate('hbs/com-tab', vm).html.contains('<script')
		ComSettings.fromMap([routeDiagnostics: 'on']).routeDiagnostics
		!ComSettings.fromMap([:]).routeDiagnostics
	}

	def "plugin brings its own renderer and registers its route controller"() {
		given:
		ComMorpheusPlugin plugin = new ComMorpheusPlugin()

		when:
		plugin.initialize()

		then: 'a plugin with web routes brings its own renderer'
		plugin.hasCustomRender()
		plugin.controllers.size() == 1
		plugin.controllers[0] instanceof ComController
	}
}
