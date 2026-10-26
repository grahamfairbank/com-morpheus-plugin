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

		// cluster summary: six anonymized COM servers with some variety, plus one host COM doesn't know
		List<String> ids = ((List<Map>) ((Map) Fixtures.json('com-servers.json')).items)*.id.sort()
		ComService cs = new ComService({ ComSettings x -> new PreviewCom() }, { 1_000L }, new DirectExecutor())
		List<HostFacts> hosts = ids.withIndex().collect { String id, int i ->
			id == Fixtures.MATCHED_ID ? Fixtures.host().tap { name = 'hvm-node-0' + (i + 1) } :
				new HostFacts(id: 200L + i, name: 'hvm-node-0' + (i + 1), vendor: 'HPE', product: 'ProLiant DL360 Gen10', typeCode: 'mvmHost',
					interfaces: [1, 2].collect { [name: "eno${it}".toString(), macAddress: String.format('00:00:61:00:%02X:%02X', id.hashCode() & 0xff, it)] })
		} + [new HostFacts(id: 299L, name: 'hvm-node-07', vendor: 'HPE', product: 'ProLiant DL380 Gen11', typeCode: 'mvmHost',
			interfaces: [[name: 'eno1', macAddress: '00:00:5E:99:99:99']])]
		cs.cluster('cluster-a', hosts, s)
		Map cluster = cs.cluster('cluster-a', hosts, s)
		Map maintenance = service.tab(Fixtures.host(), s) + [maintenanceOn: true]

		expect:
		[cluster: cluster].each { String name, Map model ->
			ViewModel<Map> vm = new ViewModel<>(); vm.object = ComView.withFlags(model)
			String html = renderer.renderTemplate('hbs/com-cluster', vm).html
			new File(dir, "preview-${name}.html").text = "<!doctype html><html><head><meta charset='utf-8'><title>${name}</title>" +
				"<style>body{font-family:'Helvetica Neue',Arial,sans-serif;color:#333;margin:24px;max-width:1100px}a{color:#0d7b6a}</style></head><body>${html}</body></html>"
		}
		(states + [maintenance: maintenance]).each { String name, Map model ->
			ViewModel<Map> vm = new ViewModel<>(); vm.object = ComView.withFlags(model)
			String html = renderer.renderTemplate('hbs/com-tab', vm).html
			new File(dir, "preview-${name}.html").text = "<!doctype html><html><head><meta charset='utf-8'><title>${name}</title>" +
				"<style>body{font-family:'Helvetica Neue',Arial,sans-serif;color:#333;margin:24px;max-width:1100px}a{color:#0d7b6a}</style></head><body>${html}</body></html>"
		}
	}

	/** Serves the anonymized fixtures with some variety, for the cluster screenshot. */
	static class PreviewCom extends FakeCom {
		static final Map<String, Integer> ALERTS = ['869121-B21+TST0000ABM': 2, '869121-B21+TST0000ABP': 3]
		HttpResult send(String method, String url, Map<String, String> headers, String body) {
			String path = url.replaceFirst('^https://[^/]+/compute-ops-mgmt', '').replaceFirst('\\?.*', '')
			if (method == 'GET' && path == '/v1/servers') {
				Map all = (Map) Fixtures.json('com-servers.json')
				all.items.each { Map sv ->
					if (sv.id.endsWith('ABP')) sv.hardware.health.summary = 'WARNING'
					if (sv.id.endsWith('ABR')) sv.maintenanceMode = true
				}
				return new HttpResult(200, groovy.json.JsonOutput.toJson(all + [offset: 0, count: all.items.size(), total: all.items.size()]))
			}
			def inv = (path =~ '^/v1/servers/([^/]+)/inventory$')
			if (method == 'GET' && inv.matches() && URLDecoder.decode(inv.group(1), 'UTF-8') != Fixtures.MATCHED_ID) {
				String id = URLDecoder.decode(inv.group(1), 'UTF-8')
				List macs = [1, 2].collect { String.format('00:00:61:00:%02X:%02X', id.hashCode() & 0xff, it) }
				return new HttpResult(200, groovy.json.JsonOutput.toJson([id: id, networkAdapter: [modified: 'x', data: [[Ports: macs.collect { [Ethernet: [AssociatedMACAddresses: [it]]] }]]]]))
			}
			if (method == 'GET' && path == '/v1/alerts') {
				List items = ALERTS.collectMany { String id, Integer n -> (1..n).collect { [id: "a-${id}-${it}".toString(), serverId: id, clearedAt: null] } }
				return new HttpResult(200, groovy.json.JsonOutput.toJson([offset: 0, count: items.size(), total: items.size(), items: items]))
			}
			if (method == 'GET' && path == '/v1beta2/server-warranty') {
				Map all = (Map) Fixtures.json('com-warranty.json')
				all.items.each { Map w -> w.supportState = w.deviceId.endsWith('ABS') ? 'EXPIRED' : 'ACTIVE' }
				return new HttpResult(200, groovy.json.JsonOutput.toJson(all + [offset: 0, count: all.items.size(), total: all.items.size()]))
			}
			return super.send(method, url, headers, body)
		}
	}
}
