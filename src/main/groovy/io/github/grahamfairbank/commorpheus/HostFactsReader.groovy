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

	static HostFacts facts(ComputeServer server, MorpheusContext morpheus, boolean withInterfaces) {
		ComputeServerType t = server.computeServerType
		HostFacts h = new HostFacts(id: server.id, name: server.name, hostname: server.hostname,
			vendor: server.hardwareProductVendor, product: server.hardwareProductName,
			typeCode: t?.code, vmHypervisor: t?.vmHypervisor, tags: tags(server))
		if (withInterfaces) h.interfaces = interfaces(server, morpheus)
		return h
	}

	static List<Map> interfaces(ComputeServer server, MorpheusContext morpheus) {
		List<ComputeServerInterface> list = server.interfaces
		if (!list) {
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
