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
	/** Every server's inventory is read again at least this often, whatever its collection time says. */
	static final long FULL_REBUILD_MS = 6L * 3600_000L
	/** Transport or sign-in failures in a row (ComException.outage) after which a build stops and the old index stays. */
	static final int MAX_TRANSPORT_FAILURES = 3

	/** comId -> [id, name, serial, model, productId, hostname, inventoryAt] */
	final Map<String, Map> servers
	/** comId -> globally unique MACs from its inventory */
	final Map<String, Set<String>> macs
	final long builtAt
	/** comId -> why its inventory couldn't be read (the server stays matchable by override only) */
	final Map<String, String> errors
	/** When the last build that read every inventory started (incremental builds keep it). */
	final long fullBuiltAt
	/** The client this index was read with: MACs are reused only by a build with the same client. */
	final ComClient source
	/** Inventories read by the build that made this index (the rest were reused). */
	final int inventoriesRead

	ComIndex(Map<String, Map> servers, Map<String, Set<String>> macs, long builtAt, Map<String, String> errors = [:],
	         long fullBuiltAt = builtAt, ComClient source = null, int inventoriesRead = 0) {
		this.servers = servers
		this.macs = macs
		this.builtAt = builtAt
		this.errors = errors
		this.fullBuiltAt = fullBuiltAt
		this.source = source
		this.inventoriesRead = inventoriesRead
	}

	/** Thrown when a newer connection replaced the client a build was using; nothing is written. */
	static class Superseded extends RuntimeException {
		Superseded() { super('superseded by new connection settings') }
	}

	/**
	 * Builds the index from one paged server list plus one inventory read per server that needs it (C2):
	 * a server whose lastFullInventoryCollectionAt is set and unchanged since the previous index (read with the
	 * same client, less than FULL_REBUILD_MS ago) keeps its MACs without a read. New and changed servers, those
	 * whose read failed last time, those in mustRead (a collect-inventory job finished) and those without a
	 * collection time are read. Servers no longer listed drop out.
	 *
	 * @param current returns false once this client has been replaced; the build then stops (Superseded)
	 */
	static ComIndex build(ComClient client, long now, ComIndex previous = null, Closure<Boolean> current = { true },
	                      Set<String> mustRead = Collections.<String>emptySet()) {
		boolean reuse = previous != null && previous.source != null && previous.source.is(client) &&
			now - previous.fullBuiltAt < FULL_REBUILD_MS
		Map<String, Map> servers = [:]
		Map<String, Set<String>> macs = [:]
		Map<String, String> errors = [:]
		int read = 0, reused = 0, failuresInRow = 0
		client.servers(0L).each { Map s ->
			String id = s.id as String
			if (!id) return
			Map hw = (Map) (s.hardware ?: [:])
			String inventoryAt = s.lastFullInventoryCollectionAt as String
			servers[id] = [id: id, name: s.name, serial: hw.serialNumber, model: hw.model, productId: hw.productId,
				hostname: ((Map) (s.host ?: [:])).hostname, inventoryAt: inventoryAt]
			if (reuse && inventoryAt && !mustRead.contains(id) && previous.macs.containsKey(id) && !previous.errors.containsKey(id) &&
				previous.servers[id]?.inventoryAt == inventoryAt) {
				macs[id] = previous.macs[id]
				reused++
				return
			}
			if (!current.call()) throw new Superseded()
			try {
				// Not cached (0.5.0): only the MACs are kept, so the index doesn't hold every server's ~100 KB inventory.
				Map inv = client.inventory(id, 0L)
				read++
				failuresInRow = 0
				macs[id] = MacMatcher.inventoryMacs(inv?.networkAdapter)
			} catch (ComException e) {
				if (e.rateLimited) throw e
				// COM unreachable or sign-in failing: stop and keep the old index rather than install one missing MACs.
				if (e.outage && ++failuresInRow >= MAX_TRANSPORT_FAILURES) throw e
				if (!e.outage) failuresInRow = 0
				errors[id] = e.message
				log.debug("com-morpheus: no inventory for ${id}: ${e.message}")
			}
		}
		log.info("com-morpheus: indexed ${servers.size()} COM servers (${macs.count { k, v -> v }} with MACs; ${read} inventories read, ${reused} unchanged" +
			"${errors ? ", ${errors.size()} unreadable: ${errors.keySet().take(5).join(', ')}${errors.size() > 5 ? ', ...' : ''}" : ''})")
		return new ComIndex(servers, macs, now, errors, reuse ? previous.fullBuiltAt : now, client, read)
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
