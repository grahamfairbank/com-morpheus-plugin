package io.github.grahamfairbank.commorpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.User
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Performance fixes measured on 2026-10-31: fewer Morpheus reads, gzip, a cheaper index, failing fast. */
class ComPerfSpec extends Specification {

	long now = 5_000_000L
	FakeCom fake = new FakeCom()
	ComService service = new ComService({ ComSettings s -> fake }, { now }, new DirectExecutor())
	static final ComSettings ON = ComSettings.fromMap([comClientId: 'id', comClientSecret: 'secret', enableActions: 'on'])
	static final ComSettings GATED = ComSettings.fromMap([comClientId: 'id', comClientSecret: 'secret', requirePermission: 'on'])
	HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', getClass().classLoader)

	// ---- test helpers --------------------------------------------------------------------------

	ComputeServer hostRecord(Long accountId = 1L) {
		Map h = (Map) Fixtures.json('vme-host.json')
		ComputeServer s = new ComputeServer(name: h.name, hostname: h.hostname, hardwareProductVendor: h.hardwareProductVendor,
			hardwareProductName: h.hardwareProductName, computeServerType: new ComputeServerType(code: 'mvmHost'),
			account: new Account(id: accountId))
		s.id = 109L
		s.interfaces = ((List<Map>) h.interfaces).collect { new ComputeServerInterface(name: it.name, macAddress: it.macAddress) }
		return s
	}

	ComMorpheusPlugin plugin(ComSettings s) {
		ComMorpheusPlugin p = new ComMorpheusPlugin() { @Override ComSettings loadSettings() { s } }
		p.service = service
		return p
	}

	MorpheusContext context(MorpheusSynchronousComputeServerService cs) {
		Stub(MorpheusContext) { getServices() >> Stub(MorpheusServices) { getComputeServer() >> cs } }
	}

	ComServerTabProvider hostTab(ComSettings s, MorpheusSynchronousComputeServerService cs) {
		HandlebarsRenderer r = renderer
		return new ComServerTabProvider(plugin(s), context(cs), { now }) { @Override Renderer<?> getRenderer() { r } }
	}

	ComClusterTabProvider clusterTab(ComSettings s, MorpheusSynchronousComputeServerService cs) {
		HandlebarsRenderer r = renderer
		return new ComClusterTabProvider(plugin(s), context(cs), { now }) { @Override Renderer<?> getRenderer() { r } }
	}

	static User user(Map<String, String> permissions = [:]) { new User(username: 'alice', permissions: permissions) }

	static byte[] gzip(byte[] data) {
		ByteArrayOutputStream out = new ByteArrayOutputStream()
		new GZIPOutputStream(out).withCloseable { it.write(data) }
		return out.toByteArray()
	}

	// ---- 1. show() and the render after it share one Morpheus read -----------------------------

	def "short memo: expires after its TTL and stays bounded"() {
		given:
		ShortMemo<String, String> m = new ShortMemo<>(15_000L, 3, { now })

		when:
		m.put('a', '1')
		now += 14_999L

		then:
		m.get('a') == '1'

		when:
		now += 1L

		then:
		m.get('a') == null

		when:
		(1..10).each { now += 1; m.put("k${it}".toString(), 'v') }

		then:
		m.size() <= 3
		m.get('k10') == 'v'
	}

	def "host tab: render reuses the record show() loaded instead of reading it again"() {
		given:
		MorpheusSynchronousComputeServerService cs = Mock()
		ComputeServer full = hostRecord()
		ComputeServer shallow = new ComputeServer(); shallow.id = 109L
		ComServerTabProvider tab = hostTab(ON, cs)
		service.tab(Fixtures.host(), ON)   // index ready

		when:
		boolean shown = tab.show(shallow, user(), null)
		String html = tab.renderTemplate(shallow).html

		then: 'one computeServer.get for the page (it was two, plus one more for the interfaces)'
		1 * cs.get(109L) >> full
		0 * cs._
		shown
		html.contains('TST0000ABM')

		when: 'past the reuse window, a render on its own reads the record again'
		now += ComServerTabProvider.FACTS_REUSE_MS
		tab.renderTemplate(shallow)

		then:
		1 * cs.get(109L) >> full
	}

	def "host tab: every show() still decides for its own user, whatever is remembered"() {
		given:
		MorpheusSynchronousComputeServerService cs = Mock()
		cs.get(109L) >> hostRecord()
		ComServerTabProvider tab = hostTab(GATED, cs)
		ComputeServer shallow = new ComputeServer(); shallow.id = 109L

		expect:
		tab.show(shallow, user([(ComMorpheusPlugin.PERMISSION_CODE): 'read']), null)
		!tab.show(shallow, user(), null)                                              // no view permission
		!tab.show(shallow, user([(ComMorpheusPlugin.PERMISSION_CODE): 'none']), null)
	}

	def "host tab: a shallow record that couldn't be loaded is never remembered"() {
		given:
		MorpheusSynchronousComputeServerService cs = Mock()
		ComputeServer shallow = new ComputeServer(); shallow.id = 109L
		ComServerTabProvider tab = hostTab(ON, cs)

		when:
		tab.show(shallow, user(), null)

		then:
		1 * cs.get(109L) >> null
		tab.recent.size() == 0
	}

	def "action route host lookup reads Morpheus by id on each call, also right after a tab show()"() {
		given:
		MorpheusSynchronousComputeServerService cs = Mock()
		ComServerTabProvider tab = hostTab(ON, cs)
		ComController controller = new ComController(plugin(ON), context(cs))
		ComputeServer shallow = new ComputeServer(); shallow.id = 109L
		ViewModel<Map> vm = new ViewModel<>()
		vm.request = Stub(jakarta.servlet.http.HttpServletRequest) { getParameter('serverId') >> '109' }
		vm.user = new User(username: 'alice', account: new Account(id: 1L))

		when:
		tab.show(shallow, user(), null)
		Map host = controller.host(vm)
		controller.host(vm)

		then:
		3 * cs.get(109L) >> hostRecord()
		((HostFacts) host.facts).interfaces.size() == 13
	}

	def "cluster tab: show() and render list the cluster's servers once"() {
		given:
		MorpheusSynchronousComputeServerService cs = Mock()
		Account owner = new Account(); owner.id = 1L
		ComputeServerGroup cluster = new ComputeServerGroup(name: 'cluster-a'); cluster.id = 5L; cluster.owner = owner
		ComClusterTabProvider tab = clusterTab(ON, cs)
		service.tab(Fixtures.host(), ON)   // index ready

		when:
		boolean shown = tab.show(cluster, user(), owner)
		String html = tab.renderTemplate(cluster).html

		then: 'list() records carry interfaces, so there is no get() per host either'
		1 * cs.list(_ as DataQuery) >> [hostRecord(1L)]
		0 * cs._
		shown
		html.contains('TST0000ABM')

		when:
		now += ComClusterTabProvider.HOSTS_REUSE_MS
		tab.renderTemplate(cluster)

		then:
		1 * cs.list(_ as DataQuery) >> [hostRecord(1L)]
	}

	def "cluster tab: another tenant is refused even while the owner's list is remembered"() {
		given:
		MorpheusSynchronousComputeServerService cs = Mock()
		Account owner = new Account(); owner.id = 1L
		Account other = new Account(); other.id = 2L
		ComputeServerGroup cluster = new ComputeServerGroup(name: 'cluster-a'); cluster.id = 5L; cluster.owner = owner
		ComClusterTabProvider tab = clusterTab(ON, cs)

		when:
		boolean mine = tab.show(cluster, user(), owner)
		boolean theirs = tab.show(cluster, user(), other)
		boolean gated = clusterTab(GATED, cs).show(cluster, user(), owner)   // no view permission

		then:
		1 * cs.list(_ as DataQuery) >> [hostRecord(1L), hostRecord(2L)]
		mine
		!theirs
		!gated
		tab.hostFacts(cluster).size() == 1   // still only the owner's servers
	}

	def "interfaces: a loaded record's empty list is final; a null list or a shallow record is read by id"() {
		given:
		MorpheusSynchronousComputeServerService cs = Mock()
		MorpheusContext ctx = context(cs)
		ComputeServer bare = new ComputeServer(computeServerType: new ComputeServerType(code: 'mvmHost')); bare.id = 109L
		bare.interfaces = []

		when:
		HostFacts loaded = HostFactsReader.facts(bare, ctx, true, true)

		then:
		0 * cs.get(_)
		loaded.interfaces == []

		when: 'no interface list at all, even on a record said to be loaded (9.1 list() hydration is unmeasured)'
		bare.interfaces = null
		HostFacts unhydrated = HostFactsReader.facts(bare, ctx, true, true)

		then:
		1 * cs.get(109L) >> hostRecord()
		unhydrated.interfaces.size() == 13

		when:
		HostFacts shallow = HostFactsReader.facts(bare, ctx, true)

		then:
		1 * cs.get(109L) >> hostRecord()
		shallow.interfaces.size() == 13
	}

	// ---- 2. gzip --------------------------------------------------------------------------------

	def "gzip bodies are decompressed whatever the header's case; plain ones pass through"() {
		given:
		byte[] json = '{"items":[1,2,3]}'.getBytes('UTF-8')

		expect:
		JdkComTransport.readBody(new ByteArrayInputStream(gzip(json)), enc, 1024) == '{"items":[1,2,3]}'
		JdkComTransport.readBody(new ByteArrayInputStream(json), null, 1024) == '{"items":[1,2,3]}'

		where:
		enc << ['gzip', 'GZIP', 'Gzip ']
	}

	def "the size limit applies to the decompressed bytes (a small gzip can't inflate past it)"() {
		given:
		byte[] bomb = gzip(new byte[4 * 1024 * 1024])   // 4 MB of zeros: about 4 KB compressed

		when:
		JdkComTransport.readBody(new ByteArrayInputStream(bomb), 'gzip', 1024 * 1024)

		then:
		bomb.length < 64 * 1024
		thrown(ResponseTooLargeException)
	}

	def "JDK transport: asks for gzip on GETs only, and decompresses; the limit holds end to end"() {
		given:
		List<Map> seen = Collections.synchronizedList([])
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
		server.createContext('/') { ex ->
			seen << [method: ex.requestMethod, path: ex.requestURI.path, acceptEncoding: ex.requestHeaders.getFirst('Accept-Encoding')]
			byte[] body = ex.requestURI.path == '/big' ? gzip(new byte[2 * 1024 * 1024]) :
				(ex.requestMethod == 'GET' ? gzip('{"ok":true}'.getBytes('UTF-8')) : '{"access_token":"t"}'.getBytes('UTF-8'))
			if (ex.requestMethod == 'GET') ex.responseHeaders.add('content-encoding', 'gzip')
			ex.sendResponseHeaders(200, body.length)
			ex.responseBody.withCloseable { it.write(body) }
		}
		server.start()
		String base = "http://127.0.0.1:${server.address.port}"
		JdkComTransport t = new JdkComTransport(null, 1024 * 1024)

		when:
		HttpResult got = t.send('GET', "${base}/v1/servers", [Accept: 'application/json'], null)
		HttpResult posted = t.send('POST', "${base}/token", ['Content-Type': 'application/x-www-form-urlencoded'], 'grant_type=client_credentials')
		t.send('GET', "${base}/big", [:], null)

		then:
		thrown(ResponseTooLargeException)
		got.body == '{"ok":true}'
		posted.body == '{"access_token":"t"}'
		seen.find { it.method == 'GET' }.acceptEncoding == 'gzip'
		seen.find { it.method == 'POST' }.acceptEncoding == null

		cleanup:
		server?.stop(0)
	}

	// ---- 3. C1: forced rebuilds ----------------------------------------------------------------

	int serverListReads() { fake.calls.count { it.contains('/v1/servers?') && !it.contains('filter=') } }

	def "a host with no match forces a rebuild at most once an hour"() {
		given:
		HostFacts h = Fixtures.host()
		h.interfaces = [[name: 'eno1', macAddress: '00:00:5E:99:99:99']]
		service.tab(h, ON)
		int built = serverListReads()

		when: 'the index is over 10 minutes old: the first no-match forces one rebuild'
		now += ComService.INDEX_MIN_REFRESH_MS + 1
		service.tab(h, ON)

		then:
		serverListReads() == built + 1

		when: 'ten minutes later the same host forces nothing'
		now += ComService.INDEX_MIN_REFRESH_MS + 1
		service.tab(h, ON)

		then:
		serverListReads() == built + 1

		when: 'an hour after its last forced rebuild, it may force again'
		now += ComService.FORCE_PER_HOST_MS
		service.tab(h, ON)

		then:
		serverListReads() == built + 2
	}

	def "a host with no usable MACs never forces a rebuild"() {
		given:
		HostFacts h = Fixtures.host()
		h.interfaces = []
		service.tab(h, ON)
		int built = serverListReads()

		when:
		now += ComService.INDEX_MIN_REFRESH_MS + 1
		Map tab = service.tab(h, ON)

		then:
		tab.state == 'nomatch'
		serverListReads() == built
	}

	// ---- 4. C2: incremental index ----------------------------------------------------------------

	int inventoryReads() { fake.calls.count { it.contains('/inventory') } }
	List<String> ids() { ((List<Map>) ((Map) Fixtures.json('com-servers.json')).items)*.id as List<String> }

	def "rebuild: unchanged inventories are reused, changed ones re-read, removed servers drop out"() {
		given:
		ids().each { fake.inventoryAt[it] = '2026-10-30T01:00:00Z' }
		service.requestIndex(ON)
		ComIndex first = service.currentIndex()
		int reads = inventoryReads()

		when: 'nothing changed (a forced rebuild within the full-rebuild window)'
		now += ComService.INDEX_MIN_REFRESH_MS + 1
		service.requestIndex(ON, true)

		then:
		reads == 6
		inventoryReads() == reads
		service.currentIndex() != first
		service.currentIndex().macs == first.macs
		service.currentIndex().inventoriesRead == 0

		when: 'one server collected a new inventory and one left COM'
		fake.inventoryAt[Fixtures.MATCHED_ID] = '2026-10-31T01:00:00Z'
		fake.hiddenServers << '869121-B21+TST0000ABS'
		now += ComService.INDEX_MIN_REFRESH_MS + 1
		service.requestIndex(ON, true)

		then:
		inventoryReads() == reads + 1
		fake.calls.findAll { it.contains('/inventory') }.last().contains('TST0000ABM')
		!service.currentIndex().servers.containsKey('869121-B21+TST0000ABS')
		!service.currentIndex().macs.containsKey('869121-B21+TST0000ABS')
		service.currentIndex().macs[Fixtures.MATCHED_ID] == first.macs[Fixtures.MATCHED_ID]
		service.tab(Fixtures.host(), ON).match == 'confirmed'
	}

	def "rebuild: servers without a collection time are read again, and every build after 6 hours reads all"() {
		given:
		ids().each { fake.inventoryAt[it] = '2026-10-30T01:00:00Z' }
		fake.inventoryAt.remove(Fixtures.MATCHED_ID)   // null in the list
		service.requestIndex(ON)
		int reads = inventoryReads()

		when:
		now += ComService.INDEX_MIN_REFRESH_MS + 1
		service.requestIndex(ON, true)

		then:
		inventoryReads() == reads + 1

		when: 'the regular 6-hour refresh is always a full one'
		now += ComService.INDEX_TTL_MS + 1
		service.requestIndex(ON)

		then:
		ComIndex.FULL_REBUILD_MS == 6L * 3600_000L
		inventoryReads() == reads + 1 + 6
	}

	def "a successful collect-inventory job makes the next build read that server again"() {
		given:
		ids().each { fake.inventoryAt[it] = '2026-10-30T01:00:00Z' }
		service.requestIndex(ON)
		service.tab(Fixtures.host(), ON)
		String jobId = service.clientFor(ON).createJob('collectInventory', Fixtures.MATCHED_ID).id
		fake.jobs[jobId].state = 'COMPLETE'
		fake.jobs[jobId].resultCode = 'SUCCESS'
		String uid = service.clientFor(ON).createJob('uidOn', Fixtures.MATCHED_ID).id
		fake.jobs[uid].state = 'COMPLETE'
		fake.jobs[uid].resultCode = 'SUCCESS'
		int reads = inventoryReads()

		when:
		service.jobStatus(Fixtures.host(), ON, uid)   // not an inventory job: changes nothing
		now += ComService.INDEX_MIN_REFRESH_MS + 1
		service.requestIndex(ON, true)

		then:
		inventoryReads() == reads

		when:
		service.jobStatus(Fixtures.host(), ON, jobId)
		now += ComService.INDEX_MIN_REFRESH_MS + 1
		service.requestIndex(ON, true)
		now += ComService.INDEX_MIN_REFRESH_MS + 1
		service.requestIndex(ON, true)

		then: 'read once, then reused again'
		inventoryReads() == reads + 1
		fake.calls.findAll { it.contains('/inventory') }.last().contains('TST0000ABM')
	}

	def "MACs are never reused across clients"() {
		given:
		ids().each { fake.inventoryAt[it] = '2026-10-30T01:00:00Z' }
		ComClient a = new ComClient(ON, fake, { now })
		ComClient b = new ComClient(ON, fake, { now })
		ComIndex fromA = ComIndex.build(a, now)
		int reads = inventoryReads()

		when:
		ComIndex.build(b, now, fromA)

		then:
		inventoryReads() == reads + 6
	}

	def "a build that loses COM part-way stops after a few failures and keeps the old index"() {
		given:
		boolean down = false
		int inventoryTries = 0
		ComService svc = new ComService({ ComSettings s ->
			{ String m, String u, Map h, String b ->
				if (down && u.contains('/inventory')) { inventoryTries++; throw new ConnectException('refused') }
				fake.send(m, u, h, b)
			} as ComTransport
		}, { now }, new DirectExecutor())
		svc.requestIndex(ON)
		ComIndex old = svc.currentIndex()

		when:
		down = true
		now += ComService.INDEX_TTL_MS + 1   // a full rebuild: every inventory would be read
		svc.requestIndex(ON)

		then:
		old != null
		inventoryTries == ComIndex.MAX_TRANSPORT_FAILURES
		svc.currentIndex().is(old)
		svc.resolve(Fixtures.host(), ON).how == 'confirmed'   // still matched from the old index
	}

	def "an answer cut off mid-body (truncated gzip) fails that call only; COM isn't marked down"() {
		given:
		byte[] whole = gzip(('{"items":[' + (1..2000).join(',') + ']}').getBytes('UTF-8'))
		byte[] cut = Arrays.copyOf(whole, whole.length.intdiv(2))
		boolean broken = true
		ComClient c = new ComClient(ON, { String m, String u, Map h, String b ->
			if (broken && m == 'GET') return new HttpResult(200, JdkComTransport.readBody(new ByteArrayInputStream(cut), 'gzip', 1 << 20))
			fake.send(m, u, h, b)
		} as ComTransport, { now })

		when:
		c.server(Fixtures.MATCHED_ID, 0L)

		then:
		ComException e = thrown()
		!e.outage
		!e.message.contains("Couldn't reach")

		when: 'the next call goes to COM at once'
		broken = false
		c.server(Fixtures.MATCHED_ID, 0L)

		then:
		noExceptionThrown()
		ComClient.connectPhase(new ConnectException('x'))
		ComClient.connectPhase(new IOException(new java.net.http.HttpConnectTimeoutException('x')))
		ComClient.connectPhase(new javax.net.ssl.SSLHandshakeException('x'))
		ComClient.connectPhase(new UnknownHostException('x'))
		!ComClient.connectPhase(new EOFException('x'))
		!ComClient.connectPhase(new java.util.zip.ZipException('x'))
		!ComClient.connectPhase(new IOException('reset mid-body'))
	}

	def "after a 429 the next build waits for COM's hint"() {
		given:
		fake.nextGetStatus = 429
		fake.nextGetBody = '{"retryAfterSeconds":300}'
		service.requestIndex(ON)

		expect:
		service.currentIndex() == null
		!service.requestIndex(ON)

		when:
		now += 120_000L

		then:
		!service.requestIndex(ON)

		when:
		now += 181_000L

		then:
		service.requestIndex(ON)
		service.currentIndex() != null
	}

	def "COM's ratelimit-reset header is read as the retry hint"() {
		given:
		ComClient c = new ComClient(ON, { String m, String u, Map h, String b ->
			m == 'POST' ? new HttpResult(200, '{"access_token":"tok","expires_in":3600}') : new HttpResult(429, '{}', ['RateLimit-Reset': ['120']])
		} as ComTransport, { now })

		when:
		c.server(Fixtures.MATCHED_ID, 0L)

		then:
		ComException e = thrown()
		e.rateLimited
		e.retryAfterSeconds == 120
	}

	def "retry delay doubles after failures, follows a 429 hint, and is capped"() {
		expect:
		ComService.retryDelay(new ComException('x'), 1) == 60_000L
		ComService.retryDelay(new ComException('x'), 2) == 120_000L
		ComService.retryDelay(new ComException('x'), 9) == ComService.INDEX_RETRY_MAX_MS
		ComService.retryDelay(new ComException('x', 429, 10), 1) == 60_000L
		ComService.retryDelay(new ComException('x', 429, 300), 1) == 300_000L
		ComService.retryDelay(new ComException('x', 429, 99_999), 1) == ComService.INDEX_RETRY_MAX_MS
	}

	// ---- 5. C3: fail fast -------------------------------------------------------------------------

	def "a failed sign-in is remembered: page renders fail at once with the same message"() {
		given:
		fake.tokenStatus[ComSettings.LEGACY_TOKEN_URL] = 401
		ComClient c = new ComClient(ON, fake, { now })

		when:
		c.accessToken()

		then:
		ComException first = thrown()

		when:
		int posts = fake.calls.count { it.startsWith('POST') }
		c.server(Fixtures.MATCHED_ID, 0L)

		then:
		ComException again = thrown()
		again.message == first.message
		fake.calls.count { it.startsWith('POST') } == posts

		when: 'background work still tries'
		ComClient.inBackground { c.accessToken() }

		then:
		thrown(ComException)
		fake.calls.count { it.startsWith('POST') } > posts

		when: 'after the window, page renders try again'
		fake.tokenStatus.clear()
		now += ComClient.FAIL_FAST_MS
		c.accessToken()

		then:
		noExceptionThrown()
	}

	def "a thread that waited on the sign-in lock sees the failure and doesn't sign in again"() {
		given:
		CountDownLatch inside = new CountDownLatch(1)
		CountDownLatch release = new CountDownLatch(1)
		List<String> posts = Collections.synchronizedList([])
		ComClient c = new ComClient(ON, { String m, String u, Map h, String b ->
			posts << u
			inside.countDown()
			release.await(10, TimeUnit.SECONDS)
			return new HttpResult(401, '{}')
		} as ComTransport, { now })
		List<Throwable> errors = Collections.synchronizedList([])
		Thread a = Thread.start { try { c.accessToken() } catch (Throwable e) { errors << e } }
		inside.await(10, TimeUnit.SECONDS)
		int firstAttempt = ComSettings.fromMap([comClientId: 'id', comClientSecret: 'secret']).tokenUrls().size()

		when:
		Thread b = Thread.start { try { c.accessToken() } catch (Throwable e) { errors << e } }
		long until = System.currentTimeMillis() + 10_000L
		while (b.state != Thread.State.BLOCKED && System.currentTimeMillis() < until) Thread.sleep(5)
		release.countDown()
		a.join(10_000L); b.join(10_000L)

		then:
		errors.size() == 2
		errors.every { it instanceof ComException && it.message.contains("Couldn't sign in") }
		posts.size() == firstAttempt
	}

	def "an unreachable COM is remembered for page renders; cached answers still work"() {
		given:
		boolean down = false
		ComClient c = new ComClient(ON, { String m, String u, Map h, String b ->
			if (down && m == 'GET') throw new java.net.http.HttpConnectTimeoutException('timed out')
			fake.send(m, u, h, b)
		} as ComTransport, { now })
		c.security(Fixtures.MATCHED_ID, 60_000L)   // cached
		down = true

		when:
		c.server(Fixtures.MATCHED_ID, 0L)

		then:
		ComException first = thrown()
		first.message.contains("Couldn't reach COM")

		when:
		int calls = fake.calls.size()
		c.alerts(Fixtures.MATCHED_ID, 0L)

		then:
		ComException again = thrown()
		again.message == first.message
		fake.calls.size() == calls
		c.security(Fixtures.MATCHED_ID, 60_000L) != null   // from the cache

		when:
		down = false
		now += ComClient.FAIL_FAST_MS
		c.server(Fixtures.MATCHED_ID, 0L)

		then:
		noExceptionThrown()
	}

	// ---- 6. C5: settings change -------------------------------------------------------------------

	/** Holds submitted tasks until the test runs them. */
	static class QueueExecutor extends AbstractExecutorService {
		List<Runnable> queued = []
		boolean shut
		void execute(Runnable r) { queued << r }
		void runAll() { List<Runnable> q = queued; queued = []; q.each { it.run() } }
		void shutdown() { shut = true }
		List<Runnable> shutdownNow() { shut = true; [] }
		boolean isShutdown() { shut }
		boolean isTerminated() { shut }
		boolean awaitTermination(long t, TimeUnit u) { true }
	}

	def "a build for the old settings writes neither index nor error for the new ones"() {
		given:
		QueueExecutor q = new QueueExecutor()
		ComService svc = new ComService({ ComSettings s -> fake }, { now }, q)
		ComSettings other = ComSettings.fromMap([comClientId: 'other', comClientSecret: 'secret'])
		svc.requestIndex(ON)
		svc.clientFor(other)

		when: 'the old build runs after the change'
		q.runAll()

		then:
		svc.currentIndex() == null
		svc.tab(Fixtures.host(), other).state == 'building'
		!fake.calls.any { it.contains('/inventory') }   // it stopped before reading inventories

		when: 'a failing old build'
		q.runAll()   // the new build queued by tab()
		svc.requestIndex(ON)   // switches back to ON; queues a build for ON
		svc.clientFor(other)
		fake.nextGetStatus = 500
		q.runAll()

		then:
		svc.tab(Fixtures.host(), other).error == null
	}

	def "new settings try the workspace alerts query again"() {
		given:
		fake.filteredAlertsStatus = 400
		HostFacts h = Fixtures.host()
		service.cluster('c', [h], ON)
		service.cluster('c', [h], ON)
		int before = fake.calls.count { it.contains('/v1/alerts?') }
		ComSettings other = ComSettings.fromMap([comClientId: 'other', comClientSecret: 'secret'])

		when:
		service.cluster('c', [h], other)
		service.cluster('c', [h], other)

		then:
		before == 1
		fake.calls.count { it.contains('/v1/alerts?') } == 2
	}

	// ---- 7. C6: thread-safe cached JSON -------------------------------------------------------------

	def "cached answers are plain maps and lists, not lazy ones"() {
		given:
		ComClient c = new ComClient(ON, fake, { now })

		when:
		Map sv = c.server(Fixtures.MATCHED_ID, 60_000L)
		List<Map> servers = c.servers(60_000L)

		then:
		!sv.getClass().name.contains('Lazy')
		!sv.hardware.getClass().name.contains('Lazy')
		!servers[0].getClass().name.contains('Lazy')
		sv.hardware instanceof Map
	}

	// ---- 8. C4: the cluster summary reads only its servers ---------------------------------------

	def "cluster summary reads its servers by id, not the whole workspace"() {
		given:
		service.cluster('c', [Fixtures.host()], ON)   // builds the index

		when:
		Map m = service.cluster('c', [Fixtures.host()], ON)
		List<String> filtered = fake.calls.findAll { it.contains('/v1/servers?filter=') }.collect { URLDecoder.decode(it, 'UTF-8') }

		then:
		m.rows[0].serial == 'TST0000ABM'
		filtered.size() == 1
		filtered[0].contains("filter=id in ('869121-B21+TST0000ABM')")
		filtered[0].contains('select=id,name,hardware')
		serverListReads() == 1   // only the index's
	}

	def "cluster summary falls back to the whole list if COM refuses the id filter, and keeps to it for a while"() {
		given:
		fake.serverFilterStatus = 400
		service.cluster('c', [Fixtures.host()], ON)

		when:
		Map m = service.cluster('c', [Fixtures.host()], ON)
		now += ComService.SUMMARY_TTL_MS + 1
		service.cluster('c', [Fixtures.host()], ON)

		then:
		m.rows[0].serial == 'TST0000ABM'
		m.notes == []
		fake.calls.count { it.contains('/v1/servers?filter=') } == 1
	}

	def "a com-id tag value COM doesn't know never reaches the id filter, so it can't trigger the whole-list fallback"() {
		given:
		HostFacts tagged = new HostFacts(id: 120L, name: 'host-09', vendor: 'HPE', product: 'ProLiant DL380 Gen10', typeCode: 'mvmHost',
			tags: ['com-id': "x') or (id ne 'y"])
		List<HostFacts> hosts = [Fixtures.host(), tagged]
		fake.serverFilterStatus = 400   // would answer 400 to any filter that reached it
		service.cluster('c', hosts, ON)   // builds the index

		when:
		fake.serverFilterStatus = 200
		Map m = service.cluster('c', hosts, ON)
		List<String> filtered = fake.calls.findAll { it.contains('/v1/servers?filter=') }.collect { URLDecoder.decode(it, 'UTF-8') }

		then:
		filtered.size() == 1
		!filtered[0].contains('id ne')
		m.rows.find { it.hostId == 120L }.statusText == 'Not in COM'
		m.rows.find { it.hostId == 109L }.serial == 'TST0000ABM'

		when: 'later renders keep using the filter'
		now += ComService.SUMMARY_TTL_MS + 1
		service.cluster('c', hosts, ON)

		then:
		fake.calls.count { it.contains('/v1/servers?filter=') } == 2
	}

	def "id reads go 50 at a time, sorted, with quotes escaped"() {
		given:
		ComClient c = new ComClient(ON, fake, { now })
		List<String> many = (1..120).collect { String.format('000000-B21+XYZ%07d', it) } + ["a'b"]

		when:
		c.serversById(many.reverse(), 0L)
		List<String> filtered = fake.calls.findAll { it.contains('/v1/servers?filter=') }.collect { URLDecoder.decode(it, 'UTF-8') }

		then:
		filtered.size() == 3
		filtered[0].contains("'000000-B21+XYZ0000001'")
		filtered.any { it.contains("'a''b'") }
	}
}
