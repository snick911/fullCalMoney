package com.fullmoney.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

/**
 * 回應共用的標頭與輸出
 */
final class Http {

	static final String JSON = "application/json; charset=utf-8";
	static final String TEXT = "text/plain; charset=utf-8";

	/** 小於此大小時壓縮的效益不明顯 */
	private static final int GZIP_MIN_BYTES = 1024;

	/** 貨幣投資「取得最新匯率」由瀏覽器直接讀取的公開匯率來源，與 app.js 的 RATE_SOURCES 相同 */
	private static final String RATE_SOURCES = "https://open.er-api.com https://cdn.jsdelivr.net https://latest.currency-api.pages.dev";

	private static final String CONTENT_SECURITY_POLICY = String.join("; ",
			"default-src 'self'",
			"script-src 'self'",
			"style-src 'self'",
			"img-src 'self' data:",
			"connect-src 'self' " + RATE_SOURCES,
			"base-uri 'none'",
			"form-action 'self'",
			"frame-ancestors 'none'",
			"object-src 'none'");

	private Http() {
	}

	static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
		Headers headers = exchange.getResponseHeaders();
		headers.set("Content-Type", contentType);
		headers.set("X-Content-Type-Options", "nosniff");
		headers.set("Referrer-Policy", "no-referrer");
		headers.set("X-Frame-Options", "DENY");
		headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);

		byte[] payload = body;
		if (payload.length >= GZIP_MIN_BYTES && isCompressible(contentType) && acceptsGzip(exchange)) {
			payload = gzip(payload);
			headers.set("Content-Encoding", "gzip");
			headers.add("Vary", "Accept-Encoding");
		}

		boolean noBody = status == 304 || "HEAD".equals(exchange.getRequestMethod());
		if (noBody) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		exchange.sendResponseHeaders(status, payload.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(payload);
		}
	}

	static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		send(exchange, status, JSON, Json.write(value).getBytes(StandardCharsets.UTF_8));
	}

	static void sendText(HttpExchange exchange, int status, String text) throws IOException {
		send(exchange, status, TEXT, text.getBytes(StandardCharsets.UTF_8));
	}

	private static boolean isCompressible(String contentType) {
		return contentType.startsWith("text/") || contentType.startsWith("application/json")
				|| contentType.startsWith("image/svg") || contentType.startsWith("application/manifest");
	}

	private static boolean acceptsGzip(HttpExchange exchange) {
		String accept = exchange.getRequestHeaders().getFirst("Accept-Encoding");
		return accept != null && accept.toLowerCase().contains("gzip");
	}

	private static byte[] gzip(byte[] body) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length / 3 + 64);
		try (GZIPOutputStream out = new GZIPOutputStream(buffer)) {
			out.write(body);
		}
		return buffer.toByteArray();
	}
}
