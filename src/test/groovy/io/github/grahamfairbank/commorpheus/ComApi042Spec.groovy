package io.github.grahamfairbank.commorpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import spock.lang.Specification

/** 0.4.2: COM API changes of 2026-10-29 (page cap, alerts select, single-server 500, sign-in order) and the 9.0.1 shallow server. */
class ComApi042Spec extends Specification {

	FakeCom fake = new FakeCom()
	ComService service = new ComService({ ComSettings s -> fake }, { 1_000L }, new DirectExecutor())
	static final ComSettings ON = ComSettings.fromMap([comClientId: 'id', comClientSecret: 'secret', enableActions: 'on'])
	static final String WS = '0123456789abcdef0123456789abcdef'
	static final String GLOBAL = "https://global.api.greenlake.hpe.com/authorization/v2/oauth2/${WS}/token".toString()

	def "sign-in: workspace issuers first, legacy SSO last; override alone"() {
		expect:
		ComSettings.fromMap([comClientId: 'i', comClientSecret: 's', comWorkspaceId: WS]).tokenUrls() ==
			[GLOBAL, "https://aquila-org-api.common.cloud.hpe.com/authorization/v2/oauth2/${WS}/token".toString(), ComSettings.LEGACY_TOKEN_URL]
		ComSettings.fromMap([comClientId: 'i', comClientSecret: 's']).tokenUrls() == [ComSettings.LEGACY_TOKEN_URL]
		ComSettings.fromMap([comClientId: 'i', comClientSecret: 's', comWorkspaceId: WS, comTokenUrl: 'https://t.example.com/token']).tokenUrls() == ['https://t.example.com/token']
	}

	def "a token COM refuses on reads (401) moves sign-in on to the next issuer"() {
		given:
		ComSettings s = ComSettings.fromMap([comClientId: 'i', comClientSecret: 's', comWorkspaceId: WS])
		fake.refusedTokenUrl = GLOBAL
		ComClient c = new ComClient(s, fake, { 1_000L })

		when:
		Map sv = c.server(Fixtures.MATCHED_ID, 0L)
		List<String> posts = fake.calls.findAll { it.startsWith('POST') }
		c.server(Fixtures.MATCHED_ID, 0L)

		then:
		sv.id == Fixtures.MATCHED_ID
		posts[0] == "POST ${GLOBAL}".toString()
		posts[1].contains('aquila-org-api')                                         // next issuer after the 401
		fake.calls.count { it.startsWith('POST') } == 2                             // and it's kept afterwards
	}

	def "no page asks COM for more than 500 items"() {
		when:
		service.tab(Fixtures.host(), ON)
		Map tab = service.tab(Fixtures.host(), ON)
		service.cluster('c', [Fixtures.host()], ON)
		Map cl = service.cluster('c', [Fixtures.host()], ON)

		then:
		tab.state == 'matched'
		!tab.notes.any { it.contains('alerts') }
		cl.state == 'summary' && cl.notes == [] && cl.totals.alertsKnown
		fake.calls.findAll { it.contains('limit=') }.every { (it =~ /limit=(\d+)/)[0][1] as int <= 500 }
	}

	def "workspace alerts: no select; the server id comes from the full item"() {
		when:
		service.cluster('c', [Fixtures.host()], ON)
		Map m = service.cluster('c', [Fixtures.host()], ON)
		String q = fake.calls.find { it.contains('/v1/alerts?') }

		then:
		!q.contains('select=')
		q.contains('filter=clearedAt%20eq%20null')
		m.rows[0].alerts == 8
		fake.calls.count { it.contains('/v1/alerts?') } == 1      // accepted: no per-server fallback needed
	}

	def "alert server id: serverId, device.id, server.id or resourceUri"() {
		expect:
		ComClient.alertServerId(alert) == id

		where:
		alert                                                                                        | id
		[serverId: 'A+1']                                                                            | 'A+1'
		[device: [id: 'B+2']]                                                                        | 'B+2'
		[server: [id: 'C+3']]                                                                        | 'C+3'
		[resourceUri: '/compute-ops-mgmt/v1/servers/D%2B4/alerts/9a']                                | 'D+4'
		[id: 'x']                                                                                    | null
	}

	def "single server: a 500 on /v1 is read from /v1beta2"() {
		given:
		fake.serverReadStatus.v1 = 500
		service.tab(Fixtures.host(), ON)

		when:
		Map tab = service.tab(Fixtures.host(), ON)

		then:
		tab.state == 'matched'
		!tab.notes.any { it.contains('full record') }
		fake.calls.any { it.contains('/v1beta2/servers/869121-B21%2BTST0000ABM') }
	}

	def "single server: when every path fails, the tab shows the server-list entry with a note"() {
		given:
		fake.serverReadStatus.putAll(v1: 500, v1beta2: 500, raw: 500)
		service.tab(Fixtures.host(), ON)

		when:
		Map tab = service.tab(Fixtures.host(), ON)

		then:
		tab.state == 'matched'
		tab.serial == 'TST0000ABM'
		tab.notes.any { it.contains("couldn't return this server's full record") }
		tab.actionsEnabled
	}

	def "single server: a 404 still means no such server (no fallback)"() {
		given:
		fake.serverReadStatus.v1 = 404
		service.tab(Fixtures.host(), ON)

		expect:
		service.tab(Fixtures.host(), ON).state == 'nomatch'
		!fake.calls.any { it.contains('/v1beta2/servers/') }
	}

	def "COM maintenance check falls back to the server list when single reads fail"() {
		given:
		fake.serverReadStatus.putAll(v1: 500, v1beta2: 500, raw: 500)
		service.tab(Fixtures.host(), ON)

		expect:
		service.startAction(Fixtures.host(), ON, 'comMaintenanceOn', 'a').ok
		service.startAction(Fixtures.host(), ON, 'comMaintenanceOff', 'b').error.contains('less than 30 seconds')
	}

	def "host tab: Morpheus 9.0.1 passes a shallow server and no account; the tab loads the full record"() {
		given:
		ComputeServer full = new ComputeServer(name: 'host-02', hardwareProductVendor: 'HPE', hardwareProductName: 'ProLiant DL360 Gen10',
			computeServerType: new ComputeServerType(code: 'mvmHost'))
		full.id = 109L
		ComputeServer shallow = new ComputeServer(); shallow.id = 109L
		ComMorpheusPlugin plugin = new ComMorpheusPlugin() { @Override ComSettings loadSettings() { ON } }
		MorpheusContext ctx = Stub(MorpheusContext) {
			getServices() >> Stub(MorpheusServices) {
				getComputeServer() >> Stub(MorpheusSynchronousComputeServerService) { get(109L) >> full }
			}
		}
		ComServerTabProvider p = new ComServerTabProvider(plugin, ctx)

		expect:
		p.show(shallow, null, null)
		p.facts(shallow, false).vendor == 'HPE'
	}
}
