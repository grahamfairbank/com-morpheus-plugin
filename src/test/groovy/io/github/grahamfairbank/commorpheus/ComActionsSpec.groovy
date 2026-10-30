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
	long clockNow = 1_000L
	ComService service = new ComService({ ComSettings s -> fake }, { clockNow }, new DirectExecutor())
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
		Map cached = service.jobStatus(Fixtures.host(), ON, r.jobId)   // within JOB_TTL_MS: no new COM call
		clockNow += ComService.JOB_TTL_MS
		Map done = service.jobStatus(Fixtures.host(), ON, r.jobId)

		then:
		running.ok && !running.done && running.state == 'PENDING'
		cached.state == 'PENDING'
		done.ok && done.done && done.resultCode == 'SUCCESS'
	}

	def "each action uses its documented template"() {
		given:
		service.tab(Fixtures.host(), ON)

		fake.maintenanceMode = action == 'comMaintenanceOff'   // so the maintenance change isn't a no-op

		expect:
		service.startAction(Fixtures.host(), ON, action, 'alice').ok
		fake.jobBodies.last().jobTemplate == template

		where:
		action             | template
		'uidOff'           | 'fd837434-a2f2-4bc8-b1b4-ec068bd036aa'
		'collectInventory' | 'd6595f1b-84e6-4587-ade5-656e2a5ea20d'
		'iloReset'         | '394b492c-8d88-4e4e-a775-1a95cb5f1065'
		'comMaintenanceOn' | '4eb92af1-1ce4-4cb0-8581-fb5a7dcdbf2b'
		'comMaintenanceOff'| '2798720f-b090-427d-b210-e48d33ce2f27'
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
		service.startAction(nomatch, ON, 'comMaintenanceOn', 'a').error.contains("isn't matched")
		service.startAction(probable, ON, 'comMaintenanceOn', 'a').error.contains('only probable')
		fake.jobBodies.isEmpty()
	}

	def "COM maintenance: refused when the server is already in that state (read uncached)"() {
		given:
		service.tab(Fixtures.host(), ON)
		fake.maintenanceMode = on

		when:
		Map r = service.startAction(Fixtures.host(), ON, action, 'a')

		then:
		r.ok == allowed
		allowed || r.error.contains(words)
		fake.jobBodies.size() == (allowed ? 1 : 0)

		where:
		on    | action              | allowed | words
		true  | 'comMaintenanceOn'  | false   | 'already in COM maintenance'
		false | 'comMaintenanceOff' | false   | "isn't in COM maintenance"
		false | 'comMaintenanceOn'  | true    | ''
		true  | 'comMaintenanceOff' | true    | ''
	}

	def "a stale cached server record doesn't decide the maintenance check"() {
		given:
		service.tab(Fixtures.host(), ON)   // caches the server with maintenanceMode false
		fake.maintenanceMode = true        // then someone turns it on in COM

		expect:
		service.startAction(Fixtures.host(), ON, 'comMaintenanceOn', 'a').error.contains('already in COM maintenance')
		service.startAction(Fixtures.host(), ON, 'comMaintenanceOff', 'a').ok   // the refusal didn't use up the cooldown
	}

	def "COM maintenance: the uncached state read happens only after the cooldown check"() {
		given:
		service.tab(Fixtures.host(), ON)
		service.startAction(Fixtures.host(), ON, 'uidOn', 'a')
		int reads = fake.calls.count { it =~ /\/v1\/servers\/[^\/?]+$/ }

		when:
		Map r = service.startAction(Fixtures.host(), ON, 'comMaintenanceOn', 'a')

		then:
		r.error.contains('less than 30 seconds')
		fake.calls.count { it =~ /\/v1\/servers\/[^\/?]+$/ } == reads
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
		service.jobStatus(Fixtures.host(), ON, other).error == ComService.NO_SUCH_JOB
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

	static final Map<String, String> BROWSER = ['X-Requested-With': 'XMLHttpRequest', Host: 'morpheus.example.com', Origin: 'https://morpheus.example.com']

	/**
	 * A route call as the browser script makes it: POST, X-Requested-With, same-origin Origin, and the tab's
	 * action token for host 109 unless params set their own. Override any of these to test the refusals.
	 */
	ViewModel<Map> call(Map params, String level, Long accountId = 1L, boolean master = false,
	                    String method = 'POST', Map<String, String> headers = BROWSER, boolean withRequest = true) {
		Map p = new LinkedHashMap(params)
		if (!p.containsKey('token')) p.token = service.actionToken(109L)
		ViewModel<Map> vm = new ViewModel<>()
		if (withRequest) {
			vm.request = Stub(jakarta.servlet.http.HttpServletRequest) {
				getMethod() >> method
				getHeader(_) >> { String n -> headers[n] }
				getParameter(_) >> { String n -> p[n] as String }
				getServerName() >> 'morpheus.example.com'
			}
		}
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

	// ---- 0.3.1 security fixes (independent review, 2026-10-25) ----------------------------------

	def "request checks: POST only (unknown method refused), X-Requested-With, same origin"() {
		given:
		service.tab(Fixtures.host(), ON)
		ComController c = controller(ON, hostRecord(1L))

		expect:
		c.action(call([serverId: '109', action: 'uidOn'], 'full', 1L, false, method, headers, withRequest)).status == status

		where:
		method | headers                                                              | withRequest | status
		'GET'  | BROWSER                                                              | true        | 403 // GET can't start a job
		null   | BROWSER                                                              | true        | 403 // unknown method fails closed
		'POST' | BROWSER                                                              | false       | 403 // no request at all
		'POST' | BROWSER.findAll { it.key != 'X-Requested-With' }                     | true        | 403 // what a cross-site form sends
		'POST' | BROWSER + [Origin: 'https://evil.example.net']                       | true        | 403 // cross-origin
		'POST' | BROWSER + [Origin: 'null']                                           | true        | 403 // sandboxed/opaque origin
		'POST' | BROWSER.findAll { it.key != 'Origin' } + [Referer: 'https://evil.example.net/x'] | true | 403 // cross-site referer
		'POST' | BROWSER + [Origin: 'https://morpheus.example.com:443']               | true        | 200 // port differences are fine
		'POST' | BROWSER + [Host: 'internal:8080', 'X-Forwarded-Host': 'morpheus.example.com'] | true | 200 // behind a proxy
	}

	def "the action token from the rendered tab is required"() {
		given:
		service.tab(Fixtures.host(), ON)
		ComController c = controller(ON, hostRecord(1L))

		expect:
		c.action(call([serverId: '109', action: 'uidOn', token: token], 'full')).status == status

		where:
		token                     | status
		null                      | 403
		''                        | 403
		'0' * 64                  | 403
		'deadbeef'                | 403
	}

	def "the matched tab carries a token for its own host only"() {
		given:
		service.tab(Fixtures.host(), ON)

		when:
		Map tab = service.tab(Fixtures.host(), ON)

		then:
		tab.actionsEnabled
		service.validToken(109L, tab.actionToken)
		!service.validToken(110L, tab.actionToken)
	}

	def "a com-id tag pointing at another server can't be used for actions"() {
		given:
		service.tab(Fixtures.host(), ON)
		HostFacts h = Fixtures.host()
		h.tags = ['com-id': '869121-B21+TST0000ABN']   // another server; none of this host's MACs are in its inventory

		when:
		Map r = service.startAction(h, ON, 'iloReset', 'mallory')
		Map tab = service.tab(h, ON)

		then:
		!r.ok
		r.error.contains("agree with the host's hardware")
		fake.jobBodies.isEmpty()
		!tab.actionsEnabled              // no buttons, whatever the tab shows
	}

	def "a com-serial tag for a server outside the workspace, or before matching finished, can't act"() {
		given:
		HostFacts h = Fixtures.host()
		h.tags = ['com-id': Fixtures.MATCHED_ID]

		expect: 'the index isn\'t built yet'
		service.startAction(h, ON, 'uidOn', 'a').error.contains('still in progress')

		when:
		service.tab(Fixtures.host(), ON)
		h.tags = ['com-id': '000000-B21+NOTINCOM']

		then:
		service.startAction(h, ON, 'uidOn', 'a').error.contains("isn't in this workspace's server list")
	}

	def "a tag that agrees with the host's hardware is actionable, and confirms a probable match"() {
		given:
		service.tab(Fixtures.host(), ON)
		HostFacts probable = Fixtures.host()
		probable.interfaces = probable.interfaces.take(3) + [[name: 'eno9', macAddress: '00:00:5E:99:99:98']]
		probable.tags = ['com-serial': 'TST0000ABM']

		expect:
		service.startAction(probable, ON, 'uidOn', 'a').ok
	}

	def "only one action per server every 30 seconds; a refused job doesn't block a retry"() {
		given:
		long[] now = [1_000L]
		ComService svc = new ComService({ ComSettings s -> fake }, { now[0] }, new DirectExecutor())
		svc.tab(Fixtures.host(), ON)

		expect:
		svc.startAction(Fixtures.host(), ON, 'uidOn', 'a').ok
		svc.startAction(Fixtures.host(), ON, 'uidOff', 'a').error.contains('less than 30 seconds')

		when:
		now[0] += 31_000L
		fake.jobPostStatus = 403
		Map refused = svc.startAction(Fixtures.host(), ON, 'uidOff', 'a')
		fake.jobPostStatus = 201
		Map retried = svc.startAction(Fixtures.host(), ON, 'uidOff', 'a')

		then:
		!refused.ok
		retried.ok
	}

	def "job status: same answer for a missing job, a job without a resource, and another server's job"() {
		given:
		service.tab(Fixtures.host(), ON)
		String mine = service.startAction(Fixtures.host(), ON, 'uidOn', 'a').jobId
		String noResource = service.clientFor(ON).createJob('uidOff', Fixtures.MATCHED_ID).id
		fake.jobs[noResource].remove('resource')
		String other = service.clientFor(ON).createJob('uidOn', '869121-B21+TST0000ABN').id

		expect:
		service.jobStatus(Fixtures.host(), ON, mine).ok
		[noResource, other, '00000000-0000-4000-8000-000000009999'].every {
			service.jobStatus(Fixtures.host(), ON, it).error == ComService.NO_SUCH_JOB
		}
	}

	def "unexpected errors return a reference, not internal detail"() {
		expect:
		ComService.unexpected('test', new IllegalStateException('secret internal path /x/y')) ==~ /Unexpected error \(ref [0-9a-f]{1,8}\)\. Details are in the Morpheus log\./
	}

	def "COM error bodies stay in the log"() {
		given:
		service.tab(Fixtures.host(), ON)
		fake.jobPostStatus = 500

		expect:
		!service.startAction(Fixtures.host(), ON, 'uidOn', 'a').error.contains('denied')
	}

	def "actions refuse servers that aren't hypervisor hosts"() {
		given:
		service.tab(Fixtures.host(), ON)
		HostFacts vm = Fixtures.host()
		vm.typeCode = 'mvmVm'

		expect:
		service.startAction(vm, ON, 'uidOn', 'a').error.contains('only for hypervisor hosts')
	}
}
