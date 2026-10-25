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
		'https://eu-central.api.greenlake.hpe.com'   | 'https://com.example/' | 'https://com.example'
		'http://insecure.example.com'                | null                   | 'https://us-west2.compute.cloud.hpe.com'
	}

	def "token URLs: override, else legacy SSO then workspace issuers"() {
		expect:
		ComSettings.fromMap([:]).tokenUrls() == [ComSettings.LEGACY_TOKEN_URL]
		ComSettings.fromMap([comWorkspaceId: 'abc']).tokenUrls().size() == 3
		ComSettings.fromMap([comWorkspaceId: 'abc']).tokenUrls()[1] == 'https://global.api.greenlake.hpe.com/authorization/v2/oauth2/abc/token'
		ComSettings.fromMap([comTokenUrl: 'https://t.example/token', comWorkspaceId: 'abc']).tokenUrls() == ['https://t.example/token']
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
