package io.github.grahamfairbank.commorpheus

import spock.lang.Specification

class ComServiceSpec extends Specification {

	long now = 5_000_000L
	FakeCom fake = new FakeCom()
	ComService service = new ComService({ ComSettings s -> fake }, { now }, new DirectExecutor())
	ComSettings settings = ComSettings.fromMap([comClientId: 'id', comClientSecret: 'secret'])

	def "not configured"() {
		expect:
		service.tab(Fixtures.host(), ComSettings.fromMap([:])).state == 'unconfigured'
	}

	def "physical HPE detection trims VME's trailing newlines and rejects virtual hardware"() {
		expect:
		ComService.isPhysicalHpe(new HostFacts(vendor: vendor, product: product)) == physical

		where:
		vendor          | product                   | physical
		'HPE\n'         | 'ProLiant DL360 Gen10\n'  | true
		'HP'            | 'ProLiant DL380 Gen9'     | true
		'VMware, Inc.\n'| 'VMware20,1\n'            | false
		'Morpheus\n'    | 'MVM\n'                   | false
		'HPE'           | 'Virtual Machine'         | false
		null            | null                      | false
	}

	def "first open builds the index, then the real host matches its COM server by MAC"() {
		when:
		Map first = service.tab(Fixtures.host(), settings)

		then: 'the background build ran inline here, so the index is ready'
		first.state == 'building'
		service.currentIndex().servers.size() == 6

		when:
		Map tab = service.tab(Fixtures.host(), settings)

		then:
		tab.state == 'matched'
		tab.match == 'confirmed'
		tab.comId == Fixtures.MATCHED_ID
		tab.matchWhy.contains('All 8')
		tab.alertCount == 8
		tab.notes == []
	}

	def "a com-serial tag overrides MAC matching"() {
		given:
		HostFacts h = Fixtures.host()
		h.interfaces = []
		h.tags = ['COM-Serial': 'tst0000abm']
		service.tab(h, settings)

		when:
		Map tab = service.tab(h, settings)

		then:
		tab.state == 'matched'
		tab.match == 'override'
		tab.comId == Fixtures.MATCHED_ID
	}

	def "a com-id tag shows its server straight away and starts matching (actions need it)"() {
		given:
		HostFacts h = Fixtures.host()
		h.tags = ['com-id': Fixtures.MATCHED_ID]

		expect:
		service.tab(h, settings).state == 'matched'
		service.currentIndex() != null
	}

	def "unknown MACs give a no-match explanation"() {
		given:
		HostFacts h = Fixtures.host()
		h.interfaces = [[name: 'eno1', macAddress: '00:00:5E:99:99:99']]
		service.tab(h, settings)

		when:
		Map tab = service.tab(h, settings)

		then:
		tab.state == 'nomatch'
		tab.reason.contains('6 servers')
		tab.hostMacs == '00:00:5E:99:99:99'
	}

	def "non-HPE hosts get a diagnostic state (only shown when the setting allows it)"() {
		given:
		HostFacts h = new HostFacts(vendor: 'VMware, Inc.\n', product: 'VMware20,1\n', interfaces: [[name: 'ens34', macAddress: '00:0c:29:57:0f:d3']])

		expect:
		service.tab(h, settings).state == 'notHpe'
		service.tab(h, settings).hostMacs == '00:0C:29:57:0F:D3'
	}

	def "sign-in failure becomes an error state, not an exception"() {
		given:
		fake.tokenStatus[ComSettings.LEGACY_TOKEN_URL] = 401
		service.tab(Fixtures.host(), settings)

		expect:
		service.tab(Fixtures.host(), settings).state == 'error'
		service.tab(Fixtures.host(), settings).error.contains("Couldn't sign in")
	}

	def "changing connection settings resets the client and index"() {
		given:
		service.tab(Fixtures.host(), settings)

		when:
		service.clientFor(ComSettings.fromMap([comClientId: 'other', comClientSecret: 'secret']))

		then:
		service.currentIndex() == null
	}
}
