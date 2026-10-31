package io.github.grahamfairbank.commorpheus

import spock.lang.Specification

class ComSettingsSpec extends Specification {

	def "defaults and bad JSON never throw"() {
		expect:
		!ComSettings.fromJson(null).configured
		!ComSettings.fromJson('not json').configured
		ComSettings.fromJson('{}').apiUrl == 'https://us-west.api.greenlake.hpe.com'
		ComSettings.fromJson('{}').uiUrl == 'https://us-west2.compute.cloud.hpe.com'
	}

	def "UI URL follows the API region unless overridden"() {
		expect:
		ComSettings.fromMap([comApiUrl: api, comUiUrl: ui]).uiUrl == expected

		where:
		api                                          | ui                     | expected
		'https://us-west.api.greenlake.hpe.com/'     | null                   | 'https://us-west2.compute.cloud.hpe.com'
		'https://eu-central.api.greenlake.hpe.com'   | null                   | 'https://eu-central1.compute.cloud.hpe.com'
		'https://eu-central.api.greenlake.hpe.com'   | 'https://ui.compute.cloud.hpe.com/' | 'https://ui.compute.cloud.hpe.com'
		'https://eu-central.api.greenlake.hpe.com'   | 'https://com.example/' | 'https://eu-central1.compute.cloud.hpe.com'
		'http://insecure.example.com'                | null                   | 'https://us-west2.compute.cloud.hpe.com'
	}

	def "token URLs: override, else workspace issuers then legacy SSO (0.4.2)"() {
		expect:
		ComSettings.fromMap([:]).tokenUrls() == [ComSettings.LEGACY_TOKEN_URL]
		ComSettings.fromMap([comWorkspaceId: WS]).tokenUrls().size() == 3
		ComSettings.fromMap([comWorkspaceId: WS]).tokenUrls()[0] == "https://global.api.greenlake.hpe.com/authorization/v2/oauth2/${WS}/token"
		ComSettings.fromMap([comWorkspaceId: WS]).tokenUrls()[2] == ComSettings.LEGACY_TOKEN_URL
		ComSettings.fromMap([comTokenUrl: 'https://sso.example.greenlake.hpe.com/token', comWorkspaceId: WS]).tokenUrls() == ['https://sso.example.greenlake.hpe.com/token']
	}

	static final String WS = '4f2a9c7e1b8d4e6fa3c5d7e9b1f20a64'

	def "URL settings must be HPE GreenLake hosts, so the secret and token can't be sent elsewhere (0.5.0)"() {
		when:
		ComSettings s = ComSettings.fromMap([comClientId: 'i', comClientSecret: 's', (key): value])

		then:
		(s.problem() != null) == refused
		!s.tokenUrls().any { it.contains('example.com') || it.contains('evil') }
		!s.apiUrl.contains('evil')

		where:
		key           | value                                                  | refused
		'comTokenUrl' | 'https://collector.evil.example/t'                     | true
		'comTokenUrl' | 'https://evil.example/global.api.greenlake.hpe.com'    | true
		'comTokenUrl' | 'https://global.api.greenlake.hpe.com.evil.example/t' | true
		'comTokenUrl' | 'https://user@global.api.greenlake.hpe.com/t'          | true
		'comTokenUrl' | 'https://global.api.greenlake.hpe.com/t?x=1'          | true
		'comTokenUrl' | 'http://global.api.greenlake.hpe.com/t'               | true
		'comTokenUrl' | 'https://notgreenlake.hpe.com/t'                       | true
		'comTokenUrl' | 'https://global.api.greenlake.hpe.com/authorization/v2/oauth2/x/token' | false
		'comTokenUrl' | 'https://sso.common.cloud.hpe.com/as/token.oauth2'    | false
		'comApiUrl'   | 'https://api.evil.example'                             | true
		'comApiUrl'   | 'https://eu-central.api.greenlake.hpe.com'             | false
		'comUiUrl'    | 'https://evil.example'                                 | true
		'comWorkspaceId' | 'abc'                                               | true
		'comWorkspaceId' | '../../evil'                                        | true
		'comWorkspaceId' | WS.toUpperCase()                                    | false
		'proxyUrl'    | 'ftp://proxy.example.com'                              | true
		'proxyUrl'    | 'http://proxy.example.com:3128'                        | false
	}

	def "the connection key doesn't contain the secret (0.5.0)"() {
		given:
		ComSettings a = ComSettings.fromMap([comClientId: 'i', comClientSecret: 'top-secret'])

		expect:
		!a.connectionKey().contains('top-secret')
		a.connectionKey() == ComSettings.fromMap([comClientId: 'i', comClientSecret: 'top-secret']).connectionKey()
		a.connectionKey() != ComSettings.fromMap([comClientId: 'i', comClientSecret: 'other']).connectionKey()
	}

	def "server page link keeps the + in the COM id"() {
		expect:
		ComSettings.fromMap([:]).serverPageUrl('869121-B21+MXQ0000000') == 'https://us-west2.compute.cloud.hpe.com/serverDetail/869121-B21+MXQ0000000'
	}

	def "checkboxes accept VME's on/off"() {
		expect:
		ComSettings.fromMap([requirePermission: 'on', showOnAnyHost: 'off']).requirePermission
		!ComSettings.fromMap([requirePermission: 'on', showOnAnyHost: 'off']).showOnAnyHost
	}
}
