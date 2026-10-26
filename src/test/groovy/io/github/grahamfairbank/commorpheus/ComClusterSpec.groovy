package io.github.grahamfairbank.commorpheus

import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.ViewModel
import spock.lang.Specification

/** 0.4.0: cluster summary tab and COM maintenance mode actions. */
class ComClusterSpec extends Specification {

	FakeCom fake = new FakeCom()
	ComService service = new ComService({ ComSettings s -> fake }, { 1_000L }, new DirectExecutor())
	static final ComSettings ON = ComSettings.fromMap([comClientId: 'id', comClientSecret: 'secret', enableActions: 'on'])

	List<HostFacts> hosts() {
		HostFacts matched = Fixtures.host()
		HostFacts unknown = new HostFacts(id: 110L, name: 'host-03', vendor: 'HPE', product: 'ProLiant DL380 Gen10', typeCode: 'mvmHost',
			interfaces: [[name: 'eno1', macAddress: '00:00:5E:99:99:99']])
		HostFacts vmware = new HostFacts(id: 111L, name: 'nested-01', vendor: 'VMware, Inc.\n', product: 'VMware20,1\n', typeCode: 'mvmHost')
		HostFacts vm = new HostFacts(id: 112L, name: 'a-vm', vendor: 'HPE', product: 'ProLiant', typeCode: 'mvmVm')
		[unknown, matched, vmware, vm]
	}

	def "one row per hypervisor host, from bulk COM reads"() {
		given:
		service.cluster('houston-cluster1', hosts(), ON)   // first call builds the index

		when:
		Map m = service.cluster('houston-cluster1', hosts(), ON)
		Map row = m.rows.find { it.hostId == 109L }

		then:
		m.state == 'summary'
		m.rows*.hostName == ['host-02', 'host-03', 'nested-01']   // sorted; the VM is left out
		row.statusText == 'Confirmed'
		row.serial == 'TST0000ABM'
		row.health == 'OK'
		row.alerts == 8
		row.support == 'Expired'
		row.supportLevel == 'bad'
		row.maintenance == 'No'
		row.hostUrl == '/infrastructure/servers/109'
		row.comUrl.endsWith('/serverDetail/869121-B21+TST0000ABM')
		m.rows.find { it.hostId == 110L }.statusText == 'No COM match'
		m.rows.find { it.hostId == 111L }.statusText == 'Not an HPE server'
		m.totals == [hosts: 3, matched: 1, ok: 1, warn: 0, bad: 0, withAlerts: 1, alerts: 8, maintenance: 0, supportIssues: 1, unmatched: 2]
		m.notes == []
	}

	def "bulk reads: one server list and one alerts call for the whole cluster"() {
		when:   // the second call is served from the 60 s cache
		service.cluster('c', hosts(), ON)
		service.cluster('c', hosts(), ON)

		then:
		fake.calls.count { it.contains('/v1/servers?') } == 2   // one fresh read for the MAC index, one for the summary; the second render adds none
		fake.calls.count { it.contains('/v1/alerts') } == 1
		!fake.calls.any { it =~ /\/v1\/servers\/[^\/?]+$/ }   // no per-host server detail calls (inventory reads belong to the MAC index)
	}

	def "alerts: one filtered, trimmed query for active alerts only"() {
		when:
		service.cluster('c', hosts(), ON)
		service.cluster('c', hosts(), ON)
		String q = URLDecoder.decode(fake.calls.find { it.contains('/v1/alerts') }, 'UTF-8')

		then:
		fake.calls.find { it.contains('/v1/alerts') }.contains('filter=clearedAt%20eq%20null')
		q.contains('filter=clearedAt eq null')
		q.contains('select=id,serverId,device/id,clearedAt')
		q.contains('limit=1000')
	}

	def "alerts: falls back to the full list if COM refuses the filter"() {
		given:
		fake.filteredAlertsStatus = 400
		service.cluster('c', hosts(), ON)

		when:
		Map m = service.cluster('c', hosts(), ON)

		then:
		m.state == 'summary'
		m.notes == []
		m.rows.find { it.hostId == 109L }.alerts == 8
		fake.calls.any { it.endsWith('/v1/alerts?offset=0&limit=1000') }
	}

	def "tenant: only the cluster owner's servers are listed; other tenants can't open the tab"() {
		given:
		Account owner = new Account(); owner.id = 7L
		Account other = new Account(); other.id = 8L
		Account master = new Account(); master.id = 1L; master.masterAccount = true
		ComputeServerGroup cluster = new ComputeServerGroup(); cluster.id = 5L; cluster.owner = owner
		ComputeServer mine = new ComputeServer(); mine.id = 109L; mine.account = owner
		ComputeServer theirs = new ComputeServer(); theirs.id = 110L; theirs.account = other
		ComputeServer none = new ComputeServer(); none.id = 111L
		ComputeServerGroup ownerless = new ComputeServerGroup(); ownerless.id = 6L

		expect:
		ComClusterTabProvider.owned([mine, theirs, none], cluster)*.id == [109L]
		ComClusterTabProvider.owned([mine, theirs], ownerless).isEmpty()   // unknown owner: fail closed
		ComClusterTabProvider.mayView(cluster, owner)
		ComClusterTabProvider.mayView(cluster, master)
		!ComClusterTabProvider.mayView(cluster, other)
		!ComClusterTabProvider.mayView(cluster, null)
		!ComClusterTabProvider.mayView(ownerless, owner)
	}

	def "the tab shows only for clusters with a physical HPE hypervisor host"() {
		given:
		List<HostFacts> all = hosts()

		expect:
		ComClusterTabProvider.worthShowing(all)
		!ComClusterTabProvider.worthShowing(all.findAll { !ComService.isPhysicalHpe(it) || !ComService.isHost(it) })
		!ComClusterTabProvider.worthShowing([])
	}

	def "host names and COM data are escaped in the cluster template"() {
		given:
		List<HostFacts> hs = hosts()
		hs.find { it.id == 110L }.name = '<img src=x onerror=alert(1)>'
		service.cluster('c', hs, ON)
		HandlebarsRenderer r = new HandlebarsRenderer('renderer', getClass().classLoader)
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = ComView.withFlags(service.cluster('<b>c</b>', hs, ON))

		when:
		String html = r.renderTemplate('hbs/com-cluster', vm).html

		then:
		!html.contains('<img')
		html.contains('&lt;img')
		!html.contains('<b>c</b>')
	}

	def "host tab warns while COM maintenance is on"() {
		given:
		service.tab(Fixtures.host(), ON)
		HandlebarsRenderer r = new HandlebarsRenderer('renderer', getClass().classLoader)

		expect:
		render(r, service.tab(Fixtures.host(), ON) + [maintenanceOn: true]).contains('COM maintenance mode is on.')
		!render(r, service.tab(Fixtures.host(), ON) + [maintenanceOn: false]).contains('COM maintenance mode is on.')
	}

	def "empty, unconfigured and still-matching clusters"() {
		expect:
		service.cluster('c', [], ON).state == 'empty'
		service.cluster('c', hosts(), ComSettings.fromMap([:])).state == 'unconfigured'
		new ComService({ ComSettings s -> fake }, { 1_000L }, new java.util.concurrent.AbstractExecutorService() {
			void execute(Runnable r) {}; void shutdown() {}; List<Runnable> shutdownNow() { [] }
			boolean isShutdown() { false }; boolean isTerminated() { false }; boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) { true }
		}).cluster('c', [Fixtures.host()], ON).state == 'building'
	}

	def "cluster template renders totals, rows and links, escaped, with no script"() {
		given:
		service.cluster('c', hosts(), ON)
		HandlebarsRenderer r = new HandlebarsRenderer('renderer', getClass().classLoader)
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = ComView.withFlags(service.cluster('c', hosts(), ON))

		when:
		String html = r.renderTemplate('hbs/com-cluster', vm).html.replace('&#x2F;', '/').replace('&#x3D;', '=')

		then:
		html.contains('Hosts: 3 (1 in COM)')
		html.contains('Active alerts: 8 on 1 host(s)')
		html.contains('href="/infrastructure/servers/109"')
		html.contains('/serverDetail/869121-B21+TST0000ABM')
		html.contains('Not an HPE server')
		html.contains('unofficial community plugin')
		!html.contains('<script')
	}

	def "host tab shows the maintenance button that applies"() {
		given:
		service.tab(Fixtures.host(), ON)
		HandlebarsRenderer r = new HandlebarsRenderer('renderer', getClass().classLoader)

		expect:
		render(r, service.tab(Fixtures.host(), ON) + [maintenanceOn: on]).contains(label)

		where:
		on    | label
		false | 'data-action="comMaintenanceOn">Enter COM maintenance'
		true  | 'data-action="comMaintenanceOff">Exit COM maintenance'
	}

	private static String render(HandlebarsRenderer r, Map model) {
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = ComView.withFlags(model)
		return r.renderTemplate('hbs/com-tab', vm).html.replace('&#x3D;', '=')
	}
}
