package io.github.grahamfairbank.commorpheus

import spock.lang.Specification

class ComViewSpec extends Specification {

	ComSettings settings = ComSettings.fromMap([comClientId: 'x', comClientSecret: 'y'])
	Map server = (Map) Fixtures.json('com-server.json')

	Map model() {
		ComView.model(server, [
			alerts  : ((Map) Fixtures.json('com-alerts.json')).items,
			security: (Map) Fixtures.json('com-security.json'),
			warranty: ComView.warrantyFor(Fixtures.MATCHED_ID, ((Map) Fixtures.json('com-warranty.json')).items),
			bundle  : [releaseVersion: '2025.01.00.00'],
			group   : null
		], settings)
	}

	private static String value(List<Map> rows, String label) { rows.find { it.label == label }?.value }

	def "summary mirrors the COM Details panel"() {
		when:
		Map m = model()

		then:
		value(m.summary, 'State') == 'Connected'
		value(m.summary, 'Model') == 'ProLiant DL360 Gen10'
		value(m.summary, 'Serial number') == 'TST0000ABM'
		value(m.summary, 'iLO security status') == 'At risk'
		value(m.summary, 'iLO IP address') == '192.0.2.74'
		value(m.summary, 'Baseline') == '2025.01.00.00'
		value(m.summary, 'Power') == 'On'
		value(m.summary, 'UID indicator') == 'Off'
		value(m.summary, 'Connection type') == 'Direct'
		value(m.summary, 'Subscription') == 'Enhanced - expires 2027-09-06'
		value(m.summary, 'Manufacture date') == '2021-01-15'
		value(m.summary, 'Group') == '--'
		value(m.summary, 'Operating system').contains('may be out of date')
		m.summary.find { it.label == 'iLO IP address' }.href == 'https://192.0.2.74/'
	}

	def "links to the COM server page and the iLO"() {
		expect:
		model().comUrl == 'https://us-west2.compute.cloud.hpe.com/serverDetail/869121-B21+TST0000ABM'
		model().iloUrl == 'https://192.0.2.74/'
	}

	def "health lists the components COM shows, skipping ones not present"() {
		when:
		List<Map> h = model().health

		then:
		h*.label == ['Fan', 'Fan redundancy', 'Temperature', 'Power supply', 'Power supply redundancy', 'Processor',
			'Memory', 'Storage', 'Smart storage battery', 'Network link status', 'BIOS/Hardware health']
		h.every { it.level == 'ok' }
		model().overallHealth == 'OK'
	}

	def "firmware, alerts, security and support"() {
		when:
		Map m = model()

		then:
		m.firmwareCount == 21
		m.firmware[0] == [name: 'iLO 5', location: 'System Board', version: '3.22 Sep 21 2026']
		m.alertCount == 8
		m.alerts[0].created.startsWith('2026-10-22')     // newest first
		m.alerts.any { it.level == 'bad' && it.description.contains('security status') }
		m.security[0].level == 'bad'                      // at-risk items first
		m.security[0].name == 'SNMPv1'
		value(m.support, 'Support state') == 'Expired'
		value(m.support, 'Service level') == 'No support'
		value(m.support, 'Auto support case creation') == 'Enabled'
	}

	def "group name comes from the group's device list"() {
		given:
		List groups = [[id: 'g1', name: 'One'], [id: 'g2', name: 'Two']]

		expect:
		ComView.groupName('srv+1', groups) { String gid -> gid == 'g2' ? [items: [[serverId: 'srv+1']]] : [items: []] } == 'Two'
		ComView.groupName('srv+1', groups) { String gid -> [items: []] } == null
	}

	def "bundle id is taken from the URI when firmwareBundleId is null"() {
		expect:
		server.firmwareBundleId == null
		ComView.bundleId(server) == '09be5021080f4741b90bc793766c9dd7'
	}
}
