package io.github.grahamfairbank.commorpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.User
import com.morpheusdata.views.ViewModel
import spock.lang.Specification

/** Phase 2 actions: the service rules and the route guards (permission level, tenant, setting, match). */
class ComActionsSpec extends Specification {

	FakeCom fake = new FakeCom()
	ComService service = new ComService({ ComSettings s -> fake }, { 1_000L }, new DirectExecutor())
	static final ComSettings ON = ComSettings.fromMap([comClientId: 'id', comClientSecret: 'secret', enableActions: 'on'])
	static final ComSettings OFF = ComSettings.fromMap([comClientId: 'i', comClientSecret: 's'])

	// ---- service -------------------------------------------------------------------------------

	def "starts a COM job on a confirmed match and reports progress"() {
		given:
		service.tab(Fixtures.host(), ON)   // builds the index

		when:
		Map r = service.startAction(Fixtures.host(), ON, 'uidOn', 'alice')

		then:
		r.ok
		r.jobId
		fake.jobBodies == [[jobTemplate: 'a46b210a-b0c7-4223-ab43-c4c1e77e680c', resourceType: 'compute-ops-mgmt/server', resourceId: Fixtures.MATCHED_ID]]

		when:
		Map running = service.jobStatus(Fixtures.host(), ON, r.jobId)
		fake.jobs[r.jobId] += [state: 'COMPLETE', resultCode: 'SUCCESS', status: 'Done']
		Map done = service.jobStatus(Fixtures.host(), ON, r.jobId)

		then:
		running.ok && !running.done && running.state == 'PENDING'
		done.ok && done.done && done.resultCode == 'SUCCESS'
	}

	def "each action uses its documented template"() {
		given:
		service.tab(Fixtures.host(), ON)

		expect:
		service.startAction(Fixtures.host(), ON, action, 'alice').ok
		fake.jobBodies.last().jobTemplate == template

		where:
		action             | template
		'uidOff'           | 'fd837434-a2f2-4bc8-b1b4-ec068bd036aa'
		'collectInventory' | 'd6595f1b-84e6-4587-ade5-656e2a5ea20d'
		'iloReset'         | '394b492c-8d88-4e4e-a775-1a95cb5f1065'
	}

	def "refuses when actions are off, unknown, unmatched or only probable"() {
		given:
		service.tab(Fixtures.host(), ON)
		HostFacts probable = Fixtures.host()
		probable.interfaces = probable.interfaces.take(3) + [[name: 'eno9', macAddress: '00:00:5E:99:99:98']] // 3 of 4 MACs -> probable
		HostFacts nomatch = Fixtures.host()
		nomatch.interfaces = [[name: 'eno1', macAddress: '00:00:5E:99:99:99']]

		expect:
		!service.startAction(Fixtures.host(), ComSettings.fromMap([comClientId: 'id', comClientSecret: 's']), 'uidOn', 'a').ok
		service.startAction(Fixtures.host(), ON, 'powerOff', 'a').error.contains('Unknown action')
		service.startAction(nomatch, ON, 'uidOn', 'a').error.contains("isn't matched")
		service.startAction(probable, ON, 'uidOn', 'a').error.contains('only probable')
		fake.jobBodies.isEmpty()
	}

	def "COM refusing the job (role without job rights) is explained"() {
		given:
		service.tab(Fixtures.host(), ON)
		fake.jobPostStatus = 403

		expect:
		service.startAction(Fixtures.host(), ON, 'uidOn', 'a').error.contains('COM role that can run server jobs')
	}

	def "a job for another server is not reported"() {
		given:
		service.tab(Fixtures.host(), ON)
		String other = service.clientFor(ON).createJob('uidOn', '869121-B21+TST0000ABN').id

		expect:
		service.jobStatus(Fixtures.host(), ON, other).error.contains("isn't for this host")
	}

	// ---- routes --------------------------------------------------------------------------------

	ComController controller(ComSettings s, ComputeServer server) {
		ComMorpheusPlugin plugin = new ComMorpheusPlugin() {
			@Override ComSettings loadSettings() { s }
		}
		plugin.service = service
		MorpheusContext ctx = Stub(MorpheusContext) {
			getServices() >> Stub(MorpheusServices) {
				getComputeServer() >> Stub(MorpheusSynchronousComputeServerService) { get(109L) >> server }
			}
		}
		return new ComController(plugin, ctx)
	}

	ComputeServer hostRecord(Long accountId) {
		Map h = (Map) Fixtures.json('vme-host.json')
		ComputeServer s = new ComputeServer(name: h.name, hostname: h.hostname, hardwareProductVendor: h.hardwareProductVendor,
			hardwareProductName: h.hardwareProductName, computeServerType: new ComputeServerType(code: 'mvmHost'),
			account: new Account(id: accountId))
		s.id = 109L
		s.interfaces = ((List<Map>) h.interfaces).collect { new ComputeServerInterface(name: it.name, macAddress: it.macAddress) }
		return s
	}

	ViewModel<Map> call(Map params, String level, Long accountId = 1L, boolean master = false) {
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = params
		vm.user = new User(username: 'alice', account: new Account(id: accountId, masterAccount: master),
			permissions: level ? [(ComMorpheusPlugin.ACTIONS_PERMISSION): level] : [:])
		return vm
	}

	def "action route: full access, own tenant, actions on -> job started"() {
		given:
		service.tab(Fixtures.host(), ON)
		ComController c = controller(ON, hostRecord(1L))

		when:
		def r = c.action(call([serverId: '109', action: 'collectInventory'], 'full'))

		then:
		r.status == 200
		r.data.ok
		r.data.jobId
	}

	def "action route guards"() {
		given:
		service.tab(Fixtures.host(), ON)

		expect:
		controller(settings, hostRecord(owner)).action(call(params, level, userAccount, master)).status == status

		where:
		settings                                                          | owner | params                                      | level  | userAccount | master | status
		OFF                                                              | 1L    | [serverId: '109', action: 'uidOn']          | 'full' | 1L          | false  | 403 // actions off
		ON                                                                | 1L    | [serverId: '109', action: 'uidOn']          | 'read' | 1L          | false  | 403 // not full
		ON                                                                | 1L    | [serverId: '109', action: 'uidOn']          | null   | 1L          | false  | 403 // no permission
		ON                                                                | 2L    | [serverId: '109', action: 'uidOn']          | 'full' | 1L          | false  | 404 // other tenant's host
		ON                                                                | 2L    | [serverId: '109', action: 'uidOn']          | 'full' | 1L          | true   | 200 // master tenant may act
		ON                                                                | 1L    | [serverId: 'abc', action: 'uidOn']          | 'full' | 1L          | false  | 400 // bad id
		ON                                                                | 1L    | [serverId: '110', action: 'uidOn']          | 'full' | 1L          | false  | 404 // no such host
		ON                                                                | 1L    | [serverId: '109', action: 'powerOff']       | 'full' | 1L          | false  | 409 // not an allowed action
	}

	def "caps route says whether to show buttons"() {
		expect:
		controller(settings, hostRecord(1L)).caps(call([:], level)).data.canAct == canAct

		where:
		settings                                                      | level  | canAct
		ON                                                            | 'full' | true
		ON                                                            | 'read' | false
		OFF                                                           | 'full' | false
	}

	def "job route rejects a malformed job id"() {
		given:
		service.tab(Fixtures.host(), ON)

		expect:
		controller(ON, hostRecord(1L)).jobStatus(call([serverId: '109', jobId: '../../x'], 'full')).status == 400
	}
}
