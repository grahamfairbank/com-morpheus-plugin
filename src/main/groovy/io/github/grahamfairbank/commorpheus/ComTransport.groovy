package io.github.grahamfairbank.commorpheus

import groovy.transform.CompileStatic

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One HTTP exchange. An interface so ComClient can be tested without a network. */
@CompileStatic
interface ComTransport {
	HttpResult send(String method, String url, Map<String, String> headers, String body)
}

@CompileStatic
class HttpResult {
	final int status
	final String body
	final Map<String, List<String>> headers

	HttpResult(int status, String body, Map<String, List<String>> headers = [:]) {
		this.status = status
		this.body = body ?: ''
		this.headers = headers != null ? headers : new LinkedHashMap<String, List<String>>()
	}

	String header(String name) {
		Map.Entry<String, List<String>> e = headers.find { k, v -> k?.equalsIgnoreCase(name) }
		return e?.value ? e.value[0] : null
	}
}

/** Real transport on the JDK HTTP client, with optional proxy and fixed timeouts. */
@CompileStatic
class JdkComTransport implements ComTransport {

	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10)
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30)

	private final HttpClient client

	JdkComTransport(String proxyUrl = null) {
		HttpClient.Builder b = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NEVER)
		if (proxyUrl) {
			URI p = new URI(proxyUrl)
			int port = p.port > 0 ? p.port : (p.scheme == 'https' ? 443 : 80)
			b.proxy(ProxySelector.of(new InetSocketAddress(p.host, port)))
		}
		client = b.build()
	}

	@Override
	HttpResult send(String method, String url, Map<String, String> headers, String body) {
		HttpRequest.Builder rb = HttpRequest.newBuilder(new URI(url)).timeout(REQUEST_TIMEOUT)
		headers?.each { k, v -> if (v != null) rb.header(k, v) }
		HttpRequest.BodyPublisher pub = body != null ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody()
		rb.method(method, pub)
		HttpResponse<String> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofString())
		return new HttpResult(r.statusCode(), r.body(), r.headers().map())
	}
}
