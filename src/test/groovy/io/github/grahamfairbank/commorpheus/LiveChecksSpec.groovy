package io.github.grahamfairbank.commorpheus

import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.ViewModel
import spock.lang.IgnoreIf
import spock.lang.Specification

/**
 * Opt-in checks, skipped in normal builds:
 *   COM_MOCK=1      calls HPE's public COM mock server with the real JDK transport (needs internet)
 *   PREVIEW_OUT=dir writes the rendered tab for every state to dir/preview-*.html
 */
class LiveChecksSpec extends Specification {

	static final String MOCK = 'https://developer.greenlake.hpe.com/_mock/docs/greenlake/services/compute-ops-mgmt/public/openapi/compute-ops-mgmt-remote/openapi'

	@IgnoreIf({ !System.getenv('COM_MOCK') })
	def "real HTTP client against HPE's COM mock server"() {
		given: 'the mock has no sign-in endpoint, so only the token POST is faked'
		JdkComTransport real = new JdkComTransport()
		ComTransport t = { String m, String url, Map h, String b ->
			m == 'POST' ? new HttpResult(200, '{"access_token":"mock","expires_in":7200}') : real.send(m, url, h, b)
		} as ComTransport
		ComClient c = new ComClient(ComSettings.fromMap([comClientId: 'x', comClientSecret: 'y', comApiUrl: MOCK]), t)

		when:
		List servers = c.servers(0L)
		String id = servers[0].id
		Map server = c.server(id, 0L)
		c.alerts(id, 0L)
		c.security(id, 0L)
		Map inv = c.inventory(id, 0L)
		c.warranties(0L)
		c.groups(0L)

		then:
		servers
		server.id
		inv != null
	}

	@IgnoreIf({ !System.getenv('PREVIEW_OUT') })
	def "write rendered previews"() {
		given:
		File dir = new File(System.getenv('PREVIEW_OUT')); dir.mkdirs()
		HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', getClass().classLoader)
		ComService service = new ComService({ ComSettings s -> new FakeCom() }, { 1_000L }, new DirectExecutor())
		ComSettings s = ComSettings.fromMap([comClientId: 'id', comClientSecret: 'secret', enableActions: 'on'])
		service.tab(Fixtures.host(), s)
		Map<String, Map> states = [
			matched : service.tab(Fixtures.host(), s),
			nomatch : service.tab(new HostFacts(vendor: 'HPE', product: 'ProLiant DL380 Gen11', interfaces: [[name: 'eno1', macAddress: '00:00:5E:99:99:99']]), s),
			error   : [state: 'error', error: "Couldn't sign in to GreenLake with the configured client (sso.common.cloud.hpe.com: HTTP 401)."]
		]

		expect:
		states.each { String name, Map model ->
			ViewModel<Map> vm = new ViewModel<>(); vm.object = ComView.withFlags(model)
			String html = renderer.renderTemplate('hbs/com-tab', vm).html
			new File(dir, "preview-${name}.html").text = "<!doctype html><html><head><meta charset='utf-8'><title>${name}</title>" +
				"<style>body{font-family:'Helvetica Neue',Arial,sans-serif;color:#333;margin:24px;max-width:1100px}a{color:#0d7b6a}</style></head><body>${html}</body></html>"
		}
	}
}
