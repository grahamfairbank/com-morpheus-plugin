package io.github.grahamfairbank.commorpheus

/**
 * Turns COM API JSON into the rows the tab template shows. The layout mirrors the COM server page
 * (Summary, Health, Firmware, Support, Alerts). Pure functions over maps, so it's unit tested with
 * fixtures taken from a live COM workspace.
 */
class ComView {

	/** COM enum values that don't read well after the generic clean-up. */
	static final Map<String, String> WORDS = [
		NOSUPPORT: 'No support', RISK: 'At risk', OK: 'OK', ON: 'On', OFF: 'Off', LIT: 'On', BLINKING: 'Blinking',
		DIRECT: 'Direct', PROLIANT: 'ProLiant', NOT_PRESENT: 'Not present'
	]

	/** Health components in the order the COM UI lists them, with COM's labels. */
	static final List<List<String>> HEALTH = [
		['fans', 'Fan'], ['fanRedundancy', 'Fan redundancy'], ['temperature', 'Temperature'],
		['powerSupplies', 'Power supply'], ['powerSupplyRedundancy', 'Power supply redundancy'],
		['processor', 'Processor'], ['memory', 'Memory'], ['storage', 'Storage'],
		['smartStorage', 'Smart storage battery'], ['network', 'Network link status'], ['bios', 'BIOS/Hardware health'],
		['liquidCooling', 'Liquid cooling'], ['liquidCoolingRedundancy', 'Liquid cooling redundancy'], ['airFilter', 'Air filter']
	]

	static String words(Object v) {
		if (v == null || v.toString().trim() == '') return '--'
		String s = v.toString().trim()
		if (WORDS[s]) return WORDS[s]
		if (s ==~ /[A-Z0-9_]+/) {
			String w = s.toLowerCase().replace('_', ' ')
			return w.substring(0, 1).toUpperCase() + w.substring(1)
		}
		return s
	}

	/** Status color class for a health/security/support value. */
	static String level(Object v) {
		String s = v?.toString()?.toUpperCase() ?: ''
		if (s in ['OK', 'REDUNDANT', 'ACTIVE']) return 'ok'
		if (s in ['WARNING', 'DEGRADED', 'NOT_REDUNDANT', 'EXPIRING', 'MEDIUM', 'IGNORED']) return 'warn'
		if (s in ['CRITICAL', 'FAILED', 'RISK', 'EXPIRED', 'HIGH', 'NOSUPPORT']) return 'bad'
		return 'muted'
	}

	/** 2026-10-01T03:26:41.688+00:00 -> 2026-10-01 03:26 UTC; a plain date stays a date. */
	static String when(Object v) {
		String s = v?.toString()
		if (!s) return '--'
		def m = s =~ /^(\d{4}-\d{2}-\d{2})(?:T(\d{2}:\d{2}))?/
		if (!m.find()) return s
		return m.group(2) ? "${m.group(1)} ${m.group(2)} UTC" : m.group(1)
	}

	/** Date part only (2027-09-06T10:52:00Z -> 2027-09-06). */
	static String day(Object v) {
		String w = when(v)
		return w.length() >= 10 && w ==~ /\d{4}-\d{2}-\d{2}.*/ ? w.substring(0, 10) : w
	}

	/** Bundle id from a firmwareBundleUri (COM leaves firmwareBundleId null). */
	static String bundleId(Map server) {
		String id = server?.firmwareBundleId as String
		if (id) return id
		String uri = server?.firmwareBundleUri as String
		return uri ? uri.substring(uri.lastIndexOf('/') + 1) : null
	}

	static List<Map> activeAlerts(List<Map> alerts) {
		return (alerts ?: []).findAll { !it.clearedAt }
			.sort { a, b -> (b.createdAt ?: '') <=> (a.createdAt ?: '') }
			.collect { Map a ->
				[severity: words(a.severity), level: level(a.severity), created: when(a.createdAt),
				 category: a.category ?: '--', description: a.description ?: a.message ?: '--',
				 resolution: a.resolution ?: null]
			}
	}

	/** Security items, at-risk ones first. COM returns them as IloSecurityParams (capital I), not as documented. */
	static List<Map> securityItems(Map security) {
		List items = (List) (security?.IloSecurityParams ?: security?.iloSecurityParams ?: [])
		return items.collect { Map p ->
			[name: p.name, status: words(p.securityStatus), level: level(p.securityStatus), state: words(p.state),
			 description: p.description, action: p.recommendedAction, ignored: p.ignore == true]
		}.sort { a, b -> (a.level == 'bad' ? 0 : a.level == 'warn' ? 1 : 2) <=> (b.level == 'bad' ? 0 : b.level == 'warn' ? 1 : 2) }
	}

	static Map warrantyFor(String comId, List<Map> warranties) {
		return (warranties ?: []).find { (it.deviceId as String) == comId }
	}

	/**
	 * https link to an iLO, only for a plain IPv4/IPv6 address or DNS host name as reported to COM (0.5.0).
	 * Anything else (paths, user info, ports, odd characters) is shown as text without a link.
	 */
	static String iloLink(Object value) {
		String v = value?.toString()?.trim()
		if (!v) return null
		if (v ==~ /(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}/) return "https://${v}/".toString()
		if (v.contains(':') && v ==~ /[0-9A-Fa-f:]{2,39}/) return "https://[${v}]/".toString()
		if (v.length() <= 253 && v ==~ /(?i)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*/) return "https://${v}/".toString()
		return null
	}

	/**
	 * Device id -> group name for every COM group (0.5.0: built once in the background, not per page view).
	 * Ids are the string values of "id"/"...Id" keys in each group's device list. A group whose devices can't be read is skipped.
	 */
	static Map<String, String> groupMap(List<Map> groups, Closure<Object> devicesOf) {
		Map<String, String> out = [:]
		for (Map g : (groups ?: [])) {
			Object devices
			try { devices = devicesOf(g.id as String) } catch (Exception ignored) { continue }
			Set<String> ids = new LinkedHashSet<>()
			collectIds(devices, ids)
			ids.each { String id -> if (!out.containsKey(id)) out[id] = g.name as String }
		}
		return out
	}

	private static void collectIds(Object o, Set<String> ids) {
		if (o instanceof Map) {
			((Map) o).each { k, v ->
				if (v instanceof String && (k == 'id' || (k as String).endsWith('Id'))) ids << (String) v
				else collectIds(v, ids)
			}
		} else if (o instanceof List) {
			((List) o).each { collectIds(it, ids) }
		}
	}

	/** Name of the COM group whose device list names this server, or null. */
	static String groupName(String comId, List<Map> groups, Closure<Object> devicesOf) {
		return groupMap(groups, devicesOf)[comId]
	}

	/**
	 * The whole tab model for a matched server.
	 * @param extra optional pieces fetched separately: alerts, security, warranty, bundle, group
	 */
	static Map model(Map server, Map extra, ComSettings settings) {
		Map hw = (Map) (server?.hardware ?: [:])
		Map bmc = (Map) (hw.bmc ?: [:])
		Map state = (Map) (server?.state ?: [:])
		Map host = (Map) (server?.host ?: [:])
		Map health = (Map) (hw.health ?: [:])
		List<Map> alerts = activeAlerts((List<Map>) extra.alerts)
		Map security = (Map) extra.security
		Map warranty = (Map) extra.warranty
		Map bundle = (Map) extra.bundle
		String comId = server?.id as String

		String os = [host.osName, host.osVersion].findAll().join(' ') ?: null
		String subscription = state.subscriptionTier ? words(state.subscriptionTier) +
			(state.subscriptionExpiresAt ? " - expires ${day(state.subscriptionExpiresAt)}" : '') : words(state.subscriptionState)

		List<Map> summary = [
			row('State', state.connected == true ? 'Connected' : (state.connected == false ? 'Disconnected' : '--'),
				state.connected == true ? 'ok' : (state.connected == false ? 'bad' : null)),
			row('Alerts', alerts ? "${alerts.size()} active" : 'None', alerts ? 'warn' : 'ok'),
			row('Group', extra.group ?: '--'),
			row('Connection type', words(server?.connectionType)),
			row('Model', hw.model),
			row('Serial number', hw.serialNumber),
			row('iLO security status', security ? words(security.overallSecurityStatus) : '--', security ? level(security.overallSecurityStatus) : null),
			row('iLO IP address', bmc.ip, null, iloLink(bmc.ip)),
			row('iLO hostname', bmc.hostname, null, iloLink(bmc.hostname)),
			row('iLO', bmc.version ?: bmc.model),
			row('Baseline', bundle ? (bundle.releaseVersion ?: bundle.displayName ?: bundle.name) : (bundleId(server) ? '(unknown bundle)' : 'None')),
			row('Power', words(hw.powerState), hw.powerState == 'ON' ? 'ok' : null),
			row('UID indicator', words(hw.indicatorLed)),
			row('Operating system', os ? "${os} (as last reported to COM; may be out of date)" : '--'),
			row('Maintenance mode', server?.maintenanceMode == true ? 'Enabled' : 'Disabled'),
			row('UUID', hw.uuid),
			row('Product ID', hw.productId),
			row('Subscription', subscription),
			row('Platform', words(server?.platformFamily)),
			row('Manufacturer', hw.manufacturer),
			row('Manufacture date', when(hw.manufacturingDate)),
			row('Auto iLO firmware update', server?.autoIloFwUpdate == true ? 'Enabled' : (server?.autoIloFwUpdate == false ? 'Disabled' : '--'))
		]

		List<Map> healthRows = HEALTH.findResults { List<String> c ->
			Object v = health[c[0]]
			if (v == null || v == 'NOT_PRESENT') return null
			[label: c[1], value: words(v), level: level(v)]
		}

		List<Map> firmware = ((List<Map>) (server?.firmwareInventory ?: [])).collect { Map f ->
			[name: f.name ?: '--', location: f.deviceContext ?: '--', version: f.version ?: '--']
		}

		List<Map> support = warranty ? [
			row('Support state', words(warranty.supportState), level(warranty.supportState)),
			row('Service level', words(warranty.supportLevel)),
			row('Contract level', words(warranty.contractLevel)),
			row('Support start date', when(warranty.supportStartDate)),
			row('Support end date', when(warranty.supportEndDate)),
			row('Auto support case creation', words(server?.autoCaseCreationState))
		] : [row('Support', 'No warranty record in COM for this server'), row('Auto support case creation', words(server?.autoCaseCreationState))]

		return [
			comId          : comId,
			maintenanceOn  : server?.maintenanceMode == true,
			comUrl         : settings.serverPageUrl(comId),
			iloUrl         : iloLink(bmc.ip),
			name           : server?.name,
			model          : hw.model,
			serial         : hw.serialNumber,
			overallHealth  : words(health.summary),
			overallLevel   : level(health.summary),
			power          : words(hw.powerState),
			summary        : summary,
			health         : healthRows,
			security       : securityItems(security),
			securityLevel  : security ? level(security.overallSecurityStatus) : 'muted',
			securityStatus : security ? words(security.overallSecurityStatus) : '--',
			firmware       : firmware,
			firmwareCount  : firmware.size(),
			support        : support,
			alerts         : alerts,
			alertCount     : alerts.size(),
			inventoryAt    : when(server?.lastFullInventoryCollectionAt),
			lastFirmware   : server?.lastFirmwareUpdate ? "${words(((Map) server.lastFirmwareUpdate).status)} on ${when(((Map) server.lastFirmwareUpdate).attemptedAt)}" : null
		]
	}

	/** URL forms the route probe tries: with and without the /plugin prefix, permissioned and open. */
	static List<String> probeUrls() {
		return ['/plugin' + ComController.PROBE, ComController.PROBE]
	}

	/** Adds is<State> flags (Handlebars has no equality helper) and the match color. */
	static Map withFlags(Map model) {
		Map m = new LinkedHashMap(model ?: [state: 'error', error: 'No data'])
		String st = (m.state ?: 'error') as String
		m["is${st.substring(0, 1).toUpperCase()}${st.substring(1)}".toString()] = true
		if (m.match) m["match${(m.match as String).substring(0, 1).toUpperCase()}${(m.match as String).substring(1)}".toString()] = true
		return m
	}

	private static Map row(String label, Object value, String level = null, String href = null) {
		[label: label, value: (value == null || value.toString() == '') ? '--' : value.toString(), level: level, href: href]
	}
}
