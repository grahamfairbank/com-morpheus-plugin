package io.github.grahamfairbank.commorpheus

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * "HPE COM" tab on cluster pages (0.4.0): one read-only row per hypervisor host with its COM match, health,
 * power, active alerts, support state and COM maintenance mode, linking to each host's own HPE COM tab and COM page.
 */
@Slf4j
class ComClusterTabProvider extends AbstractClusterTabProvider {

	ComMorpheusPlugin plugin
	MorpheusContext morpheus

	ComClusterTabProvider(ComMorpheusPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }
	@Override String getCode() { 'com-morpheus-cluster-tab' }
	@Override String getName() { 'HPE COM' }

	@Override
	Boolean show(ComputeServerGroup cluster, User user, Account account) {
		if (cluster?.id == null) return false
		ComSettings s = plugin.loadSettings()
		if (!(s.configured && ComMorpheusPlugin.canView(s, user) && mayView(cluster, account))) return false
		if (s.showOnAnyHost) return true
		try {
			return worthShowing(hosts(cluster).collect { HostFactsReader.facts(it, morpheus, false) })
		} catch (Throwable e) {
			log.debug("com-morpheus: cluster tab check for ${cluster.id} failed", e)
			return false
		}
	}

	@Override
	HTMLResponse renderTemplate(ComputeServerGroup cluster) {
		ComSettings s = plugin.loadSettings()
		Map model
		try {
			List<HostFacts> facts = hosts(cluster).collect { HostFactsReader.facts(it, morpheus, true) }
			model = plugin.service.cluster(cluster.name, facts, s)
		} catch (Throwable e) {
			model = [state: 'error', error: ComService.unexpected("cluster tab for ${cluster?.id}", e)]
		}
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = ComView.withFlags(model)
		return getRenderer().renderTemplate('hbs/com-cluster', vm)
	}

	/** The cluster's servers that belong to the cluster's own tenant (render has no user, so the owner decides). */
	List<ComputeServer> hosts(ComputeServerGroup cluster) {
		List<ComputeServer> all = morpheus.services.computeServer.list(new DataQuery().withFilter('serverGroup.id', cluster.id)) ?: []
		return owned(all, cluster)
	}

	/** Keeps servers whose tenant owns the cluster. With no known owner, keeps none (fail closed). */
	static List<ComputeServer> owned(List<ComputeServer> servers, ComputeServerGroup cluster) {
		Long owner = safe { cluster?.owner?.id } as Long
		if (owner == null) return []
		return (servers ?: []).findAll { ComputeServer c -> (safe { c.account?.id } as Long) == owner }
	}

	/** Same rule as the host tab and the action routes: the user's tenant owns it, or the user is in the master tenant. */
	static boolean mayView(ComputeServerGroup cluster, Account account) {
		if ((safe { account?.masterAccount }) == true) return true
		Long mine = safe { account?.id } as Long
		Long owner = safe { cluster?.owner?.id } as Long
		return mine != null && mine == owner
	}

	/** The tab is only worth showing when at least one hypervisor host in the cluster is a physical HPE server. */
	static boolean worthShowing(List<HostFacts> hosts) {
		return (hosts ?: []).any { ComService.isHost(it) && ComService.isPhysicalHpe(it) }
	}

	private static Object safe(Closure c) {
		try { return c.call() } catch (Throwable ignored) { return null }
	}
}
