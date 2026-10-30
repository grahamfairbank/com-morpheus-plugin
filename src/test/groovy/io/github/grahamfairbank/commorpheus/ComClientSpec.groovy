package io.github.grahamfairbank.commorpheus

import spock.lang.Specification

class ComClientSpec extends Specification {

	long now = 1_000_000L
	FakeCom fake = new FakeCom()

	ComClient client(Map settings = [:]) {
		new ComClient(ComSettings.fromMap([comClientId: 'id', comClientSecret: 's3cret'] + settings), fake, { now })
	}

	def "signs in via legacy SSO and reuses the token"() {
		given:
		def c = client()

		when:
		c.server(Fixtures.MATCHED_ID, 0L)
		c.server(Fixtures.MATCHED_ID, 0L)

		then:
		fake.calls.count { it.startsWith('POST') } == 1
		fake.calls[0] == "POST ${ComSettings.LEGACY_TOKEN_URL}"
	}

	def "falls back to the workspace token URL and remembers it"() {
		given:
		fake.tokenStatus[ComSettings.LEGACY_TOKEN_URL] = 401
		def c = client(comWorkspaceId: '4f2a9c7e1b8d4e6fa3c5d7e9b1f20a64')

		when:
		c.accessToken()
		now += 8_000_000L   // past expiry
		fake.calls.clear()
		c.accessToken()

		then: 'second sign-in goes straight to the URL that worked'
		fake.calls == ['POST https://global.api.greenlake.hpe.com/authorization/v2/oauth2/4f2a9c7e1b8d4e6fa3c5d7e9b1f20a64/token']
	}

	def "explains a failed sign-in without leaking the secret"() {
		given:
		fake.tokenStatus[ComSettings.LEGACY_TOKEN_URL] = 401

		when:
		client().accessToken()

		then:
		ComException e = thrown()
		e.message.contains('sso.common.cloud.hpe.com: HTTP 401')
		!e.message.contains('s3cret')
	}

	def "encodes the + in COM server ids"() {
		when:
		client().server(Fixtures.MATCHED_ID, 0L)

		then:
		fake.calls.any { it.endsWith('/v1/servers/869121-B21%2BTST0000ABM') }
	}

	def "caches GETs for the TTL"() {
		given:
		def c = client()

		when:
		c.security(Fixtures.MATCHED_ID, 60_000L)
		now += 30_000L
		c.security(Fixtures.MATCHED_ID, 60_000L)
		now += 31_000L
		c.security(Fixtures.MATCHED_ID, 60_000L)

		then:
		fake.calls.count { it.contains('security-parameters') } == 2
	}

	def "a 401 drops the token and retries once"() {
		given:
		def c = client()
		c.accessToken()
		fake.nextGetStatus = 401

		when:
		Map s = c.server(Fixtures.MATCHED_ID, 0L)

		then:
		s.id == Fixtures.MATCHED_ID
		fake.calls.count { it.startsWith('POST') } == 2
	}

	def "a 429 reports COM's retry hint"() {
		given:
		fake.nextGetStatus = 429
		fake.nextGetBody = '{"errorCode":"HPE_GL_ERROR_TOO_MANY_REQUESTS","retryInfo":{"retryAfterSeconds":42}}'

		when:
		client().server(Fixtures.MATCHED_ID, 0L)

		then:
		ComException e = thrown()
		e.rateLimited
		e.retryAfterSeconds == 42
		e.message.contains('try again in 42 s')
	}

	def "pages through collections"() {
		when:
		List all = client().listAll('/compute-ops-mgmt/v1/servers', 0L, 4)

		then:
		all.size() == 6
		fake.calls.count { it.contains('/v1/servers?offset=') } == 2
	}
}
