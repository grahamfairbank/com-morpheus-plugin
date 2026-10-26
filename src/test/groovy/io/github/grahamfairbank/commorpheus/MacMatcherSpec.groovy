package io.github.grahamfairbank.commorpheus

import spock.lang.Specification

class MacMatcherSpec extends Specification {

	def "normalizes and classifies MACs"() {
		expect:
		MacMatcher.normalize(raw) == norm
		MacMatcher.isGloballyUnique(raw) == global

		where:
		raw                 | norm                | global
		'00:00:5e:44:b5:f0' | '00:00:5E:44:B5:F0' | true
		'00-00-5E-44-B5-F0' | '00:00:5E:44:B5:F0' | true
		'aa:00:00:7e:ce:1d' | 'AA:00:00:7E:CE:1D' | false   // bond: locally administered
		'fe:54:00:00:00:01' | 'FE:54:00:00:00:01' | false   // VM tap: locally administered
		'01:00:5e:00:00:01' | '01:00:5E:00:00:01' | false   // multicast
		'00:00:00:00:00:00' | '00:00:00:00:00:00' | false
		'not a mac'         | null                | false
	}

	def "host MACs from VME skip bonds, bridges, OVS and virtual interfaces"() {
		when:
		Set<String> macs = MacMatcher.hostMacs(Fixtures.host().interfaces)

		then: 'the 8 factory NIC MACs of the physical host, including both bonded ports'
		macs.size() == 8
		macs.contains('00:00:5F:26:BC:30')   // ens1f0np0, bonded: VME keeps its factory MAC
		!macs.any { it.startsWith('AA:00:00') } // bond0
		!macs.contains('F6:00:00:F8:31:4E')     // mgmt bridge
	}

	def "inventory MACs come from anywhere in COM's raw Redfish adapter data"() {
		when:
		Set<String> macs = MacMatcher.inventoryMacs(((Map) Fixtures.json('com-inventory-nics.json')).networkAdapter)

		then: 'the same 8 factory MACs; the bond MAC COM also lists is ignored'
		macs == MacMatcher.hostMacs(Fixtures.host().interfaces)
	}

	def "matches the real host to its COM server as confirmed"() {
		given:
		Set<String> host = MacMatcher.hostMacs(Fixtures.host().interfaces)
		Map<String, Set<String>> com = [
			(Fixtures.MATCHED_ID): MacMatcher.inventoryMacs(((Map) Fixtures.json('com-inventory-nics.json')).networkAdapter),
			'other-1'            : ['00:00:61:00:00:01'] as Set,
			'other-2'            : [] as Set
		]

		when:
		def r = MacMatcher.match(host, com)

		then:
		r.status == MacMatcher.Status.CONFIRMED
		r.comId == Fixtures.MATCHED_ID
		r.shared == 8
	}

	def "partial, single-MAC, conflicting and missing matches"() {
		expect:
		MacMatcher.match(host as Set, com).status == status

		where:
		host                       | com                                                  | status
		['00:00:5E:00:00:01', '00:00:5E:00:00:02'] | [a: ['00:00:5E:00:00:01'] as Set]      | MacMatcher.Status.PROBABLE
		['00:00:5E:00:00:01']      | [a: ['00:00:5E:00:00:01'] as Set]                    | MacMatcher.Status.PROBABLE
		['00:00:5E:00:00:01', '00:00:5E:00:00:02'] | [a: ['00:00:5E:00:00:01'] as Set, b: ['00:00:5E:00:00:02'] as Set] | MacMatcher.Status.CONFLICT
		['00:00:5E:00:00:09']      | [a: ['00:00:5E:00:00:01'] as Set]                    | MacMatcher.Status.NONE
		[]                         | [a: ['00:00:5E:00:00:01'] as Set]                    | MacMatcher.Status.NO_MACS
	}
}
