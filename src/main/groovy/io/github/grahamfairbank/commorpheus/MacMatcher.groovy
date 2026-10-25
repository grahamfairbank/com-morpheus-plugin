package io.github.grahamfairbank.commorpheus

import groovy.json.JsonOutput
import groovy.transform.CompileStatic

/**
 * Matches a Morpheus host to a COM server by NIC MAC addresses.
 *
 * VME stores each host's interfaces with their factory MACs, including bonded ports; COM's inventory
 * lists the same adapters. Only globally unique MACs count: locally administered ones (bonds, bridges,
 * OVS, VM tap devices) are random or shared and would cause false matches.
 */
@CompileStatic
class MacMatcher {

	enum Status { CONFIRMED, PROBABLE, CONFLICT, NONE, NO_MACS }

	@CompileStatic
	static class Result {
		Status status
		String comId
		int shared
		int hostMacs
		/** comId -> shared MAC count, best first; filled for CONFLICT and PROBABLE. */
		Map<String, Integer> candidates = [:]
	}

	private static final List<String> VIRTUAL_PREFIXES = ['vnet', 'tap', 'veth', 'virbr', 'docker', 'ovs-']
	private static final String MAC_RE = /(?<![0-9A-Fa-f:])([0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5})(?![0-9A-Fa-f:])/

	/** Upper-case, colon-separated, or null when it isn't a MAC. */
	static String normalize(String mac) {
		String m = mac?.trim()?.toUpperCase()?.replace('-', ':')
		return (m ==~ /[0-9A-F]{2}(:[0-9A-F]{2}){5}/) ? m : null
	}

	/** Globally unique unicast MAC: not zero, not multicast/broadcast, not locally administered. */
	static boolean isGloballyUnique(String mac) {
		String m = normalize(mac)
		if (!m || m == '00:00:00:00:00:00') return false
		int first = Integer.parseInt(m.substring(0, 2), 16)
		return (first & 0x01) == 0 && (first & 0x02) == 0
	}

	/** Usable MACs from VME interfaces ([name:, macAddress:] maps), skipping virtual interfaces. */
	static Set<String> hostMacs(List<Map> interfaces) {
		Set<String> out = new TreeSet<>()
		interfaces?.each { Map i ->
			String name = (i.name ?: '').toString().toLowerCase()
			if (VIRTUAL_PREFIXES.any { String p -> name.startsWith(p) }) return
			String m = normalize(i.macAddress as String)
			if (m && isGloballyUnique(m)) out << m
		}
		return out
	}

	/** Every globally unique MAC anywhere in a COM inventory section (raw Redfish, so field names vary). */
	static Set<String> inventoryMacs(Object section) {
		if (section == null) return new TreeSet<>()
		String json = section instanceof String ? (String) section : JsonOutput.toJson(section)
		Set<String> out = new TreeSet<>()
		def m = json =~ MAC_RE
		while (m.find()) {
			String mac = normalize(m.group(1))
			if (mac && isGloballyUnique(mac)) out << mac
		}
		return out
	}

	/**
	 * CONFIRMED: at least 2 shared MACs, every host MAC found, all on one COM server.
	 * PROBABLE:  one COM server shares some but not all (NIC replaced, stale inventory) or just one MAC.
	 * CONFLICT:  host MACs found on more than one COM server.
	 */
	static Result match(Set<String> host, Map<String, Set<String>> comMacs) {
		Result r = new Result(hostMacs: host?.size() ?: 0)
		if (!host) { r.status = Status.NO_MACS; return r }
		Map<String, Integer> hits = [:]
		comMacs?.each { String id, Set<String> macs ->
			int n = macs ? (int) host.count { String h -> macs.contains(h) } : 0
			if (n > 0) hits[id] = n
		}
		Map<String, Integer> sorted = hits.sort { Map.Entry<String, Integer> a, Map.Entry<String, Integer> b -> b.value <=> a.value }
		r.candidates = sorted
		if (hits.isEmpty()) { r.status = Status.NONE; return r }
		if (hits.size() > 1) { r.status = Status.CONFLICT; return r }
		Map.Entry<String, Integer> only = sorted.entrySet().first()
		r.comId = only.key
		r.shared = only.value
		r.status = (r.shared >= 2 && r.shared == host.size()) ? Status.CONFIRMED : Status.PROBABLE
		return r
	}
}
