package io.github.grahamfairbank.commorpheus

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * Test data from a live COM workspace and VME host (2026-10-22/23), anonymised: serials TST0000AB?,
 * IPs from documentation ranges, MAC vendor prefixes replaced consistently in both COM and VME data.
 * The matched server is 869121-B21+TST0000ABM.
 */
class Fixtures {
	static final String MATCHED_ID = '869121-B21+TST0000ABM'

	static Object json(String name) {
		new JsonSlurper().parse(Fixtures.getResourceAsStream("/fixtures/${name}"))
	}

	static String text(String name) { Fixtures.getResourceAsStream("/fixtures/${name}").text }

	static HostFacts host() {
		Map h = (Map) json('vme-host.json')
		new HostFacts(id: 109L, name: h.name, hostname: h.hostname, vendor: h.hardwareProductVendor,
			product: h.hardwareProductName, typeCode: h.computeServerType.code, interfaces: h.interfaces)
	}
}

/** Runs submitted tasks on the caller's thread, so background index builds finish before asserts. */
class DirectExecutor extends AbstractExecutorService {
	boolean shut
	void execute(Runnable r) { r.run() }
	void shutdown() { shut = true }
	List<Runnable> shutdownNow() { shut = true; [] }
	boolean isShutdown() { shut }
	boolean isTerminated() { shut }
	boolean awaitTermination(long t, TimeUnit u) { true }
}

/**
 * A fake GreenLake: token endpoints and the COM endpoints the plugin reads, answered from fixtures.
 * Other servers get a small inventory with MACs that match nothing.
 */
class FakeCom implements ComTransport {
	List<String> calls = []
	Map<String, Integer> tokenStatus = [:]   // token URL -> status to return (default 200)
	int nextGetStatus = 0                    // one-off status for the next GET (e.g. 401, 429)
	String nextGetBody = ''
	int jobPostStatus = 201                  // status for POST /v1/jobs
	Map<String, Map> jobs = [:]              // created jobs by id
	List<Map> jobBodies = []                 // bodies POSTed to /v1/jobs

	HttpResult send(String method, String url, Map<String, String> headers, String body) {
		calls << "${method} ${url}".toString()
		if (method == 'POST' && url.contains('/compute-ops-mgmt/v1/jobs')) {
			assert headers.Authorization == 'Bearer tok'
			Map b = (Map) new groovy.json.JsonSlurper().parseText(body)
			jobBodies << b
			if (jobPostStatus >= 300) return new HttpResult(jobPostStatus, '{"message":"denied"}')
			String id = String.format('00000000-0000-4000-8000-%012d', jobs.size() + 1)
			Map job = [id: id, state: 'PENDING', resultCode: null, status: 'Queued', jobTemplate: b.jobTemplate,
			           resource: [id: b.resourceId, type: b.resourceType], resourceUri: "/compute-ops-mgmt/v1/jobs/${id}".toString()]
			jobs[id] = job
			return new HttpResult(jobPostStatus, JsonOutput.toJson(job))
		}
		if (method == 'POST') {
			int st = tokenStatus[url] ?: 200
			return st == 200 ? new HttpResult(200, '{"access_token":"tok","token_type":"Bearer","expires_in":7199}') :
				new HttpResult(st, '{"error":"unauthorized_request"}')
		}
		assert headers.Authorization == 'Bearer tok'
		if (nextGetStatus) {
			int st = nextGetStatus; nextGetStatus = 0
			return new HttpResult(st, nextGetBody)
		}
		URI u = new URI(url)
		String p = u.rawPath.replaceFirst('^/compute-ops-mgmt', '')
		Map q = (u.rawQuery ?: '').split('&').findAll().collectEntries { String kv -> def a = kv.split('=', 2); [(a[0]): a.size() > 1 ? a[1] : ''] }
		String id = URLDecoder.decode(p.tokenize('/')[2] ?: '', 'UTF-8')
		switch (p) {
			case '/v1/servers': return page('com-servers.json', q)
			case ~'/v1/servers/[^/]+': return id == Fixtures.MATCHED_ID ? ok(Fixtures.text('com-server.json')) : new HttpResult(404, '{}')
			case ~'/v1/servers/[^/]+/inventory':
				if (id == Fixtures.MATCHED_ID) return ok(Fixtures.text('com-inventory.json'))
				int n = id.hashCode() & 0xff
				return ok(JsonOutput.toJson([id: id, networkAdapter: [modified: 'x', data: [[Ports: [[Ethernet: [AssociatedMACAddresses: [String.format('00:00:61:00:%02X:01', n)]]]]]]]]))
			case ~'/v1/servers/[^/]+/alerts': return page('com-alerts.json', q)
			case ~'/v1/servers/[^/]+/security-parameters': return ok(Fixtures.text('com-security.json'))
			case '/v1beta2/server-warranty': return page('com-warranty.json', q)
			case ~'/v1/firmware-bundles/[^/]+': return ok('{"id":"b","name":"bundle","releaseVersion":"2025.01.00.00"}')
			case '/v1/groups': return page('com-groups.json', q)
			case ~'/v1/jobs/[^/]+': return jobs[id] ? ok(JsonOutput.toJson(jobs[id])) : new HttpResult(404, '{}')
			case ~'/v1/groups/[^/]+/devices': return ok('{"items":[],"count":0,"total":0}')
		}
		return new HttpResult(404, '{"message":"no fake for ' + p + '"}')
	}

	private static HttpResult ok(String body) { new HttpResult(200, body) }

	/** Serves a collection fixture honouring offset/limit, like COM. */
	private static HttpResult page(String fixture, Map q) {
		Map all = (Map) Fixtures.json(fixture)
		List items = (List) all.items
		int offset = (q.offset ?: '0') as int
		int limit = (q.limit ?: '100') as int
		List slice = offset < items.size() ? items.subList(offset, Math.min(items.size(), offset + limit)) : []
		ok(JsonOutput.toJson([offset: offset, count: slice.size(), total: items.size(), items: slice]))
	}
}
