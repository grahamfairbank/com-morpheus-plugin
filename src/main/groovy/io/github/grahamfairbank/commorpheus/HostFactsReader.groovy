package io.github.grahamfairbank.commorpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.MetadataTag
import groovy.util.logging.Slf4j

/** Copies what the plugin needs out of Morpheus's ComputeServer. Shared by the tab and the routes. */
@Slf4j
class HostFactsReader {

	/**
	 * @param loaded true when the record came from computeServer.get() or list(), which carry the interfaces
	 *        (measured on 9.0.1), so they are never read again. False for a possibly shallow record.
	 */
	static HostFacts facts(ComputeServer server, MorpheusContext morpheus, boolean withInterfaces, boolean loaded = false) {
		ComputeServerType t = server.computeServerType
		HostFacts h = new HostFacts(id: server.id, name: server.name, hostname: server.hostname,
			vendor: server.hardwareProductVendor, product: server.hardwareProductName,
			typeCode: t?.code, vmHypervisor: t?.vmHypervisor, tags: tags(server))
		if (withInterfaces) h.interfaces = interfaces(server, morpheus, loaded)
		return h
	}

	/**
	 * The server's interfaces. A record from get() or list() already has them (an empty list means none), so
	 * only a shallow record (or one with no interface list at all) is re-read by id (C11: that repeat cost a second computeServer.get, 0.7 to 1.1 s).
	 */
	static List<Map> interfaces(ComputeServer server, MorpheusContext morpheus, boolean loaded = false) {
		List<ComputeServerInterface> list = server.interfaces
		// null means not loaded (whatever the caller thinks); only an empty list on a loaded record means "none".
		if (list == null || (list.isEmpty() && !loaded)) {
			try {
				list = morpheus.services.computeServer.get(server.id)?.interfaces
			} catch (Throwable e) {
				log.warn("com-morpheus: could not load interfaces for server ${server.id}: ${e}")
			}
		}
		return (list ?: []).collect { ComputeServerInterface i -> [name: i.name, macAddress: i.macAddress] }
	}

	/** Morpheus tags as name -> value: metadata tags, plus "name=value" or "name:value" items in the tags string. */
	static Map<String, String> tags(ComputeServer server) {
		Map<String, String> out = [:]
		try {
			(server.metadata ?: []).each { MetadataTag m -> if (m?.name) out[m.name] = m.value }
		} catch (Throwable ignored) { }
		try {
			(server.tags ?: '').split(',').each { String item ->
				def kv = item.trim().split('[=:]', 2)
				if (kv.size() == 2 && kv[0].trim()) out.putIfAbsent(kv[0].trim(), kv[1].trim())
			}
		} catch (Throwable ignored) { }
		return out
	}
}
