package io.github.grahamfairbank.commorpheus

/**
 * Hardware and Storage sub-tabs from COM's server inventory (GET /v1/servers/{id}/inventory). Each
 * section is raw Redfish from the iLO wrapped as {modified, data:[...]}, so every field is optional
 * and read defensively. Layout follows the COM server page (Hardware, Storage).
 */
class ComInventoryView {

	static Map model(Map inv) {
		if (!inv) return [hasInventory: false]
		return [hasInventory: true,
		        inventoryAt : ComView.when(inv.lastFullInventoryCollectionAt),
		        hardware    : hardware(inv),
		        storage     : storage(inv)]
	}

	static List<Map> items(Map inv, String section) {
		Object s = inv?.get(section)
		Object data = s instanceof Map ? ((Map) s).data : null
		return data instanceof List ? (List<Map>) data : []
	}

	static Map hardware(Map inv) {
		Map chassis = items(inv, 'chassis')[0] ?: [:]
		Map hpe = (Map) (((Map) (chassis.Oem ?: [:])).Hpe ?: [:])

		List<Map> cpus = items(inv, 'processor').findAll { st(it) != 'Absent' }.collect { Map p ->
			Map ph = (Map) (((Map) (p.Oem ?: [:])).Hpe ?: [:])
			[socket: p.Socket ?: p.Id ?: '--', model: clean(p.Model) ?: '--',
			 maxSpeed: mhz(p.MaxSpeedMHz), ratedSpeed: mhz(ph.RatedSpeedMHz),
			 cores: p.TotalCores ?: '--', threads: p.TotalThreads ?: '--', health: health(p), level: ComView.level(health(p))]
		}

		List<Map> dimms = items(inv, 'memory')
		List<Map> populated = dimms.findAll { ((it.CapacityMiB ?: 0) as long) > 0 }
		long totalMiB = (long) populated.sum(0L) { ((it.CapacityMiB ?: 0) as long) }
		List<Map> memory = populated.collect { Map m ->
			[slot: m.DeviceLocator ?: m.Id ?: '--', size: gib(m.CapacityMiB),
			 type: [m.MemoryDeviceType, m.BaseModuleType].findAll().join(' ') ?: '--',
			 speed: m.OperatingSpeedMhz ? "${m.OperatingSpeedMhz} MT/s" : '--',
			 part: clean(m.PartNumber) ?: '--', health: health(m), level: ComView.level(health(m))]
		}

		List<Map> nics = items(inv, 'networkAdapter').collect { Map a ->
			Map c = (List) a.Controllers ? (Map) ((List) a.Controllers)[0] : [:]
			Map loc = (Map) (((Map) (c.Location ?: [:])).PartLocation ?: [:])
			Map caps = (Map) (c.ControllerCapabilities ?: [:])
			[name: clean(a.Name ?: a.Model) ?: '--', location: loc.ServiceLabel ?: '--',
			 ports: caps.NetworkPortCount ?: '--', firmware: c.FirmwarePackageVersion ?: '--']
		}

		List<Map> psus = items(inv, 'powerSupply').findAll { st(it) != 'Absent' }.collect { Map p ->
			[bay: p.MemberId != null ? "Bay ${((p.MemberId as String).isInteger() ? ((p.MemberId as int) + 1) : p.MemberId)}" : '--',
			 model: p.Model ?: '--', capacity: p.PowerCapacityWatts ? "${p.PowerCapacityWatts} W" : '--',
			 output: p.LastPowerOutputWatts != null ? "${p.LastPowerOutputWatts} W" : '--',
			 firmware: p.FirmwareVersion ?: '--', health: health(p), level: ComView.level(health(p))]
		}

		return [formFactor : hpe.FormFactor ?: '--',
		        chassisType: chassis.ChassisType ?: '--',
		        cpus       : cpus,
		        cpuSummary : cpus ? "${cpus.size()} of ${items(inv, 'processor').size()} sockets" : null,
		        memory     : memory,
		        memorySummary: dimms ? "${gib(totalMiB)} in ${populated.size()} of ${dimms.size()} slots" : null,
		        nics       : nics,
		        psus       : psus]
	}

	/** Volumes and drives: standard Redfish Storage first, HPE SmartStorage as the fallback (older iLOs). */
	static Map storage(Map inv) {
		List<Map> controllers = items(inv, 'storage').collect { Map s ->
			List<Map> ctrls = (List<Map>) (s.StorageControllers ?: s.Controllers ?: [])
			Map ctrl = ctrls ? ctrls[0] : [:]
			Map loc = (Map) (((Map) (ctrl.Location ?: [:])).PartLocation ?: [:])
			String slot = loc.LocationOrdinalValue != null ? "Slot ${loc.LocationOrdinalValue}" : (loc.ServiceLabel ?: null)
			[name: [slot, clean(ctrl.Name ?: ctrl.Model ?: s.Name)].findAll().join(' - ') ?: '--',
			 firmware: ctrl.FirmwareVersion ?: '--', health: ctrlHealth(ctrl, s), level: ComView.level(ctrlHealth(ctrl, s)),
			 volumes: ((List<Map>) (s.Volumes ?: [])).collect { Map v ->
				 Map links = (Map) (v.Links ?: [:])
				 [name: v.Name ?: v.Id ?: '--', state: v.Status instanceof Map ? (((Map) v.Status).State ?: '--') : '--',
				  capacity: bytes(v.CapacityBytes), raid: v.RAIDType ?: '--',
				  drives: links['Drives@odata.count'] ?: ((List) (links.Drives ?: [])).size(),
				  spares: links['DedicatedSpareDrives@odata.count'] ?: '--', health: health(v), level: ComView.level(health(v))]
			 },
			 drives: ((List<Map>) (s.Drives ?: [])).collect { Map d ->
				 [name: d.Name ?: d.Id ?: '--', model: clean(d.Model) ?: '--', protocol: d.Protocol ?: '--',
				  media: d.MediaType ?: '--', capacity: bytes(d.CapacityBytes), firmware: d.Revision ?: '--',
				  health: health(d), level: ComView.level(health(d))]
			 }]
		}
		if (!controllers) {
			controllers = items(inv, 'smartStorage').collect { Map a ->
				[name: [a.Location ? "Slot ${a.Location}" : null, clean(a.Model)].findAll().join(' - ') ?: '--',
				 firmware: a.FirmwareVersion instanceof Map ? (((Map) ((Map) a.FirmwareVersion).Current ?: [:]).VersionString ?: '--') : (a.FirmwareVersion ?: '--'),
				 health: health(a), level: ComView.level(health(a)),
				 volumes: ((List<Map>) (a.LogicalDrives ?: [])).collect { Map v ->
					 [name: v.LogicalDriveName ?: v.Id ?: '--', state: v.Status instanceof Map ? (((Map) v.Status).State ?: '--') : '--',
					  capacity: v.CapacityMiB ? gib(v.CapacityMiB) : '--', raid: v.Raid ? "RAID${v.Raid}" : '--',
					  drives: ((List) (v.DataDrives ?: [])).size(), spares: '--', health: health(v), level: ComView.level(health(v))]
				 },
				 drives: ((List<Map>) (a.PhysicalDrives ?: [])).collect { Map d ->
					 [name: d.Location ?: d.Id ?: '--', model: clean(d.Model) ?: '--', protocol: d.InterfaceType ?: '--',
					  media: d.MediaType ?: '--', capacity: d.CapacityGB ? "${d.CapacityGB} GB" : '--',
					  firmware: d.FirmwareVersion instanceof Map ? (((Map) ((Map) d.FirmwareVersion).Current ?: [:]).VersionString ?: '--') : '--',
					  health: health(d), level: ComView.level(health(d))]
				 }]
			}
		}
		return [controllers: controllers, hasStorage: !controllers.isEmpty()]
	}

	// ---- formatting -----------------------------------------------------------------------------

	static String health(Map m) {
		Object s = m?.Status
		return s instanceof Map ? (((Map) s).Health ?: ((Map) s).State ?: '--') : '--'
	}

	/** The controller's own health; the Storage subsystem object often has only a State. */
	static String ctrlHealth(Map ctrl, Map storage) {
		String h = health(ctrl)
		return h != '--' && h != 'Enabled' ? h : health(storage)
	}

	static String st(Map m) {
		Object s = m?.Status
		return s instanceof Map ? (String) ((Map) s).State : null
	}

	static String clean(Object v) { v == null ? null : v.toString().trim() ?: null }

	static String mhz(Object v) { v ? "${v} MHz" : '--' }

	static String gib(Object mib) {
		if (!mib) return '--'
		BigDecimal g = (mib as BigDecimal) / 1024
		return g >= 1024 ? "${(g / 1024).setScale(2, BigDecimal.ROUND_HALF_UP).stripTrailingZeros().toPlainString()} TiB" :
			"${g.setScale(2, BigDecimal.ROUND_HALF_UP).stripTrailingZeros().toPlainString()} GiB"
	}

	static String bytes(Object b) {
		if (!b) return '--'
		return gib((b as BigDecimal) / (1024 * 1024))
	}
}
