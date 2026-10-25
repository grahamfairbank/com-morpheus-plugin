package io.github.grahamfairbank.commorpheus

import groovy.util.logging.Slf4j

/**
 * Snapshot of the COM workspace used for matching: every server's identity plus the MACs from its
 * inventory. Built from one paged server list and one inventory call per server, so it's built in the
 * background and kept for hours (NICs rarely change).
 */
@Slf4j
class ComIndex {

	static final long INVENTORY_TTL_MS = 6L * 3600_000L

	/** comId -> [id, name, serial, model, productId, hostname] */
	final Map<String, Map> servers
	/** comId -> globally unique MACs from its inventory */
	final Map<String, Set<String>> macs
	final long builtAt
	/** comId -> why its inventory couldn't be read (the server stays matchable by override only) */
	final Map<String, String> errors

	ComIndex(Map<String, Map> servers, Map<String, Set<String>> macs, long builtAt, Map<String, String> errors = [:]) {
		this.servers = servers
		this.macs = macs
		this.builtAt = builtAt
		this.errors = errors
	}

	static ComIndex build(ComClient client, long now) {
		Map<String, Map> servers = [:]
		Map<String, Set<String>> macs = [:]
		Map<String, String> errors = [:]
		client.servers(0L).each { Map s ->
			String id = s.id as String
			if (!id) return
			Map hw = (Map) (s.hardware ?: [:])
			servers[id] = [id: id, name: s.name, serial: hw.serialNumber, model: hw.model, productId: hw.productId,
				hostname: ((Map) (s.host ?: [:])).hostname]
			try {
				Map inv = client.inventory(id, INVENTORY_TTL_MS)
				macs[id] = MacMatcher.inventoryMacs(inv?.networkAdapter)
			} catch (ComException e) {
				if (e.rateLimited) throw e
				errors[id] = e.message
				log.warn("com-morpheus: no inventory for ${id}: ${e.message}")
			}
		}
		log.info("com-morpheus: indexed ${servers.size()} COM servers (${macs.count { k, v -> v }} with MACs)")
		return new ComIndex(servers, macs, now, errors)
	}

	/** COM id for a manual override value: a COM id, or a serial number (case-insensitive). */
	String resolveOverride(String value) {
		if (!value) return null
		String v = value.trim()
		if (servers.containsKey(v)) return v
		Map hit = servers.values().find { Map s -> (s.serial as String)?.equalsIgnoreCase(v) }
		return hit?.id as String
	}
}
