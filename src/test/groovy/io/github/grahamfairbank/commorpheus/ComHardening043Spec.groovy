package io.github.grahamfairbank.commorpheus

import groovy.json.JsonOutput
import spock.lang.Specification

/** 0.5.0 hardening from the 2026-10-31 security review: limits, caching, tokens, iLO links, groups. */
class ComHardening043Spec extends Specification {

	FakeCom fake = new FakeCom()
	long now = 1_000L
	ComService service = new ComService({ ComSettings s -> fake }, { now }, new DirectExecutor())
	static final ComSettings ON = ComSettings.fromMap([comClientId: 'id', comClientSecret: 'secret', enableActions: 'on'])

	// ---- M1: settings outside HPE --------------------------------------------------------------

	def "a token URL outside HPE stops the plugin before it sends the secret"() {
		given:
		ComSettings s = ComSettings.fromMap([comClientId: 'id', comClientSecret: 'secret', comTokenUrl: 'https://collector.evil.example/t'])

		when:
		Map tab = service.tab(Fixtures.host(), s)
		new ComClient(s, fake, { now }).accessToken()

		then:
		thrown(ComException)
		tab.state == 'error'
		tab.error.contains('Token URL override')
		fake.calls.every { !it.contains('evil') }
		fake.calls.every { !it.startsWith('POST') }
	}

	def "the cluster summary refuses the same settings"() {
		expect:
		service.cluster('c', [Fixtures.host()], ComSettings.fromMap([comClientId: 'i', comClientSecret: 's', comApiUrl: 'https://api.evil.example'])).state == 'error'
	}

	// ---- M2: memory ----------------------------------------------------------------------------

	def "the index keeps only MACs, not every server's inventory"() {
		when:
		service.tab(Fixtures.host(), ON)
		ComClient c = service.clientFor(ON)

		then: 'the only inventory cached is the matched host the tab showed'
		service.currentIndex().servers.size() > 1
		c.cacheSize() < service.currentIndex().servers.size() + 20
	}

	def "the response cache has a ceiling and drops expired entries first"() {
		given:
		ComClient c = new ComClient(ON, fake, { now })

		when:
		(1..(ComClient.MAX_CACHE_ENTRIES + 50)).each { int i -> c.getJson("/compute-ops-mgmt/v1/servers/s${i}/security-parameters", 60_000L) }

		then:
		c.cacheSize() <= ComClient.MAX_CACHE_ENTRIES
	}

	def "response bodies over the cap are refused"() {
		when:
		JdkComTransport.readLimited(new ByteArrayInputStream(new byte[2048]), 1024)

		then:
		thrown(ResponseTooLargeException)

		expect:
		JdkComTransport.readLimited(new ByteArrayInputStream('{"a":1}'.bytes), 1024) == '{"a":1}'
	}

	// ---- M3: per-user limits -------------------------------------------------------------------

	def "route calls are limited per user per minute"() {
		expect:
		(1..ComService.ROUTE_CALLS_PER_MINUTE).every { service.allowRoute('alice') }
		!service.allowRoute('alice')
		service.allowRoute('bob')

		when:
		now += 60_000L

		then:
		service.allowRoute('alice')
	}

	def "actions are limited per user per hour"() {
		given:
		service.tab(Fixtures.host(), ON)

		when:
		List<Map> results = (1..ComService.ACTIONS_PER_HOUR + 1).collect {
			now += ComService.ACTION_COOLDOWN_MS
			service.startAction(Fixtures.host(), ON, it % 2 ? 'uidOn' : 'uidOff', 'alice')
		}

		then:
		results.take(ComService.ACTIONS_PER_HOUR).every { it.ok }
		!results.last().ok
		results.last().error.contains('in the last hour')

		when: 'another user is not affected'
		now += ComService.ACTION_COOLDOWN_MS

		then:
		service.startAction(Fixtures.host(), ON, 'uidOn', 'bob').ok
	}

	def "an iLO can be reset at most once every 10 minutes"() {
		given:
		service.tab(Fixtures.host(), ON)

		expect:
		service.startAction(Fixtures.host(), ON, 'iloReset', 'alice').ok

		when:
		now += ComService.ACTION_COOLDOWN_MS + 1
		Map again = service.startAction(Fixtures.host(), ON, 'iloReset', 'alice')
		Map other = service.startAction(Fixtures.host(), ON, 'uidOn', 'alice')

		then:
		!again.ok
		again.error.contains('10 minutes')
		other.ok

		when:
		now += ComService.ILO_RESET_COOLDOWN_MS

		then:
		service.startAction(Fixtures.host(), ON, 'iloReset', 'alice').ok
	}

	// ---- L5: tokens expire ---------------------------------------------------------------------

	def "an action token works on its day and the next, then never again"() {
		given:
		String token = service.actionToken(109L)

		expect:
		service.validToken(109L, token)
		!service.validToken(110L, token)

		when:
		now += ComService.TOKEN_DAY_MS

		then:
		service.validToken(109L, token)

		when:
		now += ComService.TOKEN_DAY_MS

		then:
		!service.validToken(109L, token)
		service.validToken(109L, service.actionToken(109L))
	}

	// ---- L1: iLO links -------------------------------------------------------------------------

	def "iLO links only for plain addresses and host names"() {
		expect:
		ComView.iloLink(value) == link

		where:
		value                          | link
		'192.0.2.21'                   | 'https://192.0.2.21/'
		'ilo-host-a.example.com'       | 'https://ilo-host-a.example.com/'
		'fe80::1'                      | 'https://[fe80::1]/'
		'evil.example/#'               | null
		'user@evil.example'            | null
		'evil.example:8443'            | null
		'javascript:alert(1)'          | null
		'999.1.1.1.example'            | 'https://999.1.1.1.example/'
		''                             | null
		null                           | null
	}

	// ---- L8: groups in the background ----------------------------------------------------------

	def "group names come from one background map, not a COM call per group per render"() {
		given:
		FakeCom groupsFake = new FakeCom() {
			HttpResult send(String method, String url, Map<String, String> headers, String body) {
				if (url.contains('/devices')) {
					calls << "${method} ${url}".toString()
					return new HttpResult(200, JsonOutput.toJson([items: url.contains('d764acc0') ? [[serverId: Fixtures.MATCHED_ID]] : []]))
				}
				return super.send(method, url, headers, body)
			}
		}
		ComService svc = new ComService({ ComSettings s -> groupsFake }, { now }, new DirectExecutor())

		svc.tab(Fixtures.host(), ON)   // builds the index

		when:
		Map first = svc.tab(Fixtures.host(), ON)
		int deviceCalls = groupsFake.calls.count { it.contains('/devices') }
		Map second = svc.tab(Fixtures.host(), ON)

		then:
		first.summary.find { it.label == 'Group' }.value == 'group-a'
		second.summary.find { it.label == 'Group' }.value == 'group-a'
		groupsFake.calls.count { it.contains('/devices') } == deviceCalls   // the second render reads no group members
	}

	def "groupMap reads ids from id and ...Id keys only"() {
		expect:
		ComView.groupMap([[id: 'g1', name: 'One'], [id: 'g2', name: 'Two']]) { String gid ->
			gid == 'g2' ? [items: [[serverId: 'srv+1', name: 'srv+2', nested: [deviceId: 'srv+3']]]] : [items: []]
		} == ['srv+1': 'Two', 'srv+3': 'Two']
	}
}
