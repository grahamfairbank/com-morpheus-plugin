package io.github.grahamfairbank.commorpheus

import groovy.transform.CompileStatic

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream

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

/** A response body over JdkComTransport.MAX_BODY_BYTES (0.5.0). */
@CompileStatic
class ResponseTooLargeException extends IOException {
	ResponseTooLargeException(String message) { super(message) }
}

/** Real transport on the JDK HTTP client, with optional proxy, fixed timeouts and a response size cap. */
@CompileStatic
class JdkComTransport implements ComTransport {

	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10)
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30)
	/** Largest response body read (0.5.0). A server inventory is about 100 KB; a page of any list far less. */
	static final int MAX_BODY_BYTES = 16 * 1024 * 1024

	private final HttpClient client
	/** Largest body accepted, counted after decompression (a small gzip can't inflate past it). */
	final int maxBodyBytes

	JdkComTransport(String proxyUrl = null, int maxBodyBytes = MAX_BODY_BYTES) {
		this.maxBodyBytes = maxBodyBytes
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
		// COM answers gzip when asked: about 7 to 10 times smaller (2026-10-31). Reads only; sign-in and job POSTs unchanged.
		if (method == 'GET' && !headers?.keySet()?.any { String k -> k?.equalsIgnoreCase('Accept-Encoding') }) rb.header('Accept-Encoding', 'gzip')
		HttpRequest.BodyPublisher pub = body != null ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody()
		rb.method(method, pub)
		HttpResponse<InputStream> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream())
		String encoding = r.headers().firstValue('Content-Encoding').orElse(null)   // JDK header lookup ignores case
		return new HttpResult(r.statusCode(), readBody(r.body(), encoding, maxBodyBytes), r.headers().map())
	}

	/**
	 * The body as UTF-8, gunzipped when Content-Encoding is gzip (any case). The size limit applies to the
	 * decompressed bytes, so a small compressed answer that inflates past it is refused the same way.
	 */
	static String readBody(InputStream stream, String contentEncoding, int limit) {
		String enc = contentEncoding?.trim()?.toLowerCase()
		if (enc == 'gzip' || enc == 'x-gzip') {
			InputStream gz
			try {
				gz = new GZIPInputStream(stream, 65536)
			} catch (IOException e) {
				stream.close()
				throw e
			}
			return readLimited(gz, limit)   // closes gz, which closes stream
		}
		return readLimited(stream, limit)
	}

	/** Reads at most limit bytes as UTF-8; throws ResponseTooLargeException (and closes the stream) beyond that. */
	static String readLimited(InputStream stream, int limit) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream()
			byte[] buf = new byte[65536]
			int n
			while ((n = stream.read(buf)) != -1) {
				if (out.size() + n > limit) throw new ResponseTooLargeException("response over ${(int) (limit / (1024 * 1024))} MB".toString())
				out.write(buf, 0, n)
			}
			return new String(out.toByteArray(), 'UTF-8')
		} finally {
			stream.close()
		}
	}
}
