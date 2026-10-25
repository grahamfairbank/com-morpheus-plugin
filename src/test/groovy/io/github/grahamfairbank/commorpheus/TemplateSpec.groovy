package io.github.grahamfairbank.commorpheus

import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.ViewModel
import spock.lang.Specification

/** Renders the tab template the way VME does, for every state the service can return. */
class TemplateSpec extends Specification {

	HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', getClass().classLoader)
	FakeCom fake = new FakeCom()
	ComService service = new ComService({ ComSettings s -> fake }, { 1_000L }, new DirectExecutor())
	ComSettings settings = ComSettings.fromMap([comClientId: 'id', comClientSecret: 'secret'])

	private String render(Map model) {
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = ComView.withFlags(model)
		return renderer.renderTemplate('hbs/com-tab', vm).html
	}

	private static String decoded(String html) { html.replace('&#x3D;', '=').replace('&amp;', '&').replace('&#x2F;', '/') }

	def "matched host: header links, chips and all five sub-tabs"() {
		given:
		service.tab(Fixtures.host(), settings)

		when:
		String html = decoded(render(service.tab(Fixtures.host(), settings)))

		then:
		html.contains('href="https://us-west2.compute.cloud.hpe.com/serverDetail/869121-B21+TST0000ABM"')
		html.contains('href="https://192.0.2.74/"')
		html.contains('ProLiant DL360 Gen10')
		html.contains('Match: confirmed by MAC')
		['Summary', 'Health', 'Firmware (21)', 'Support', 'Alerts (8)'].every { html.contains(">${it}</label>") }
		html.contains('Smart storage battery')
		html.contains('Disable the SNMPv1 protocol.')
		html.contains('No support')
		!html.contains('No matching server')
	}

	def "every non-matched state renders its explanation"() {
		expect:
		render(model).contains(text)

		where:
		model                                                                        | text
		[state: 'unconfigured']                                                      | 'has no COM API client yet'
		[state: 'building']                                                          | 'Reopen this tab shortly'
		[state: 'error', error: 'Couldn\'t sign in']                                 | 'Couldn&#x27;t sign in'
		[state: 'nomatch', reason: 'None of this host', hostMacs: '00:00:5E:00:00:01'] | 'No matching server'
		[state: 'notHpe', vendor: 'VMware, Inc.', product: 'VMware20,1', hostMacs: 'none'] | 'a physical HPE server'
		[state: 'conflict', candidates: [[serial: 'S1', name: 'n', model: 'm', shared: 2, url: 'https://x/serverDetail/a']]] | 'more than one COM server'
	}

	def "values are HTML-escaped"() {
		expect:
		!render([state: 'error', error: '<script>alert(1)</script>']).contains('<script>alert')
	}
}
