package io.github.grahamfairbank.commorpheus

import spock.lang.Specification

/** Hardware and Storage from the (anonymized) inventory of the reference DL360 Gen10. */
class ComInventoryViewSpec extends Specification {

	Map inv = (Map) Fixtures.json('com-inventory.json')

	def "hardware mirrors the COM Hardware page"() {
		when:
		Map hw = ComInventoryView.hardware(inv)

		then:
		hw.formFactor == '1U'
		hw.chassisType == 'RackMount'
		hw.cpus.size() == 2
		hw.cpus[0].model == 'Intel(R) Xeon(R) Silver 4210 CPU @ 2.20GHz'
		hw.cpus[0].maxSpeed == '4000 MHz'
		hw.cpus[0].ratedSpeed == '2200 MHz'
		hw.cpus[0].cores == 10
		hw.cpus[0].threads == 20
		hw.cpuSummary == '2 of 2 sockets'
		hw.memory.size() == 12
		hw.memorySummary == '192 GiB in 12 of 24 slots'
		hw.memory[0] == [slot: 'PROC 1 DIMM 1', size: '16 GiB', type: 'DDR4 RDIMM', speed: '2400 MT/s', part: 'HMA82GR7DJR4N-XN', health: 'OK', level: 'ok']
		hw.nics.size() == 3
		hw.nics[0].location == 'Embedded ALOM'
		hw.nics[0].firmware == '11.1.7'
		hw.psus*.model == ['865408-B21', '865408-B21']
		hw.psus[0].capacity == '500 W'
	}

	def "storage lists the controller, its RAID1 volume and both drives"() {
		when:
		Map st = ComInventoryView.storage(inv)
		Map c = st.controllers[0]

		then:
		st.hasStorage
		c.name.contains('HPE Smart Array P408i-a SR Gen10')
		c.health == 'OK'
		c.volumes[0].name == 'VOLUME-TEST-1'
		c.volumes.size() == 1
		c.volumes[0].raid == 'RAID1'
		c.volumes[0].drives == 2
		c.volumes[0].state == 'Enabled'
		c.volumes[0].capacity ==~ /4\d\d(\.\d+)? GiB/
		c.drives.size() == 2
		c.drives[0].model == 'MK000480GWSSC'
	}

	def "falls back to SmartStorage when Redfish Storage is missing"() {
		given:
		Map old = new LinkedHashMap(inv)
		old.remove('storage')

		when:
		Map c = ComInventoryView.storage(old).controllers[0]

		then:
		c.volumes[0].raid == 'RAID1'
		c.drives.size() == 2
		c.drives[0].capacity == '480 GB'
	}

	def "no inventory gives an explicit empty model"() {
		expect:
		ComInventoryView.model(null) == [hasInventory: false]
		!ComInventoryView.storage([:]).hasStorage
	}

	def "sizes"() {
		expect:
		ComInventoryView.gib(16384) == '16 GiB'
		ComInventoryView.gib(196608) == '192 GiB'
		ComInventoryView.bytes(480036847616L) == '447.07 GiB'
		ComInventoryView.gib(4194304) == '4 TiB'
	}
}
