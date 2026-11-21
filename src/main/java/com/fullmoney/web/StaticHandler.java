package com.fullmoney.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * 從 classpath 提供網頁檔案，只開放白名單內的副檔名
 */
final class StaticHandler implements HttpHandler {

	private static final Map<String, String> CONTENT_TYPES = Map.of(
			"html", "text/html; charset=utf-8",
			"css", "text/css; charset=utf-8",
			"js", "text/javascript; charset=utf-8",
			"svg", "image/svg+xml",
			"png", "image/png",
			"ico", "image/x-icon",
			"json", "application/json; charset=utf-8",
			"webmanifest", "application/manifest+json; charset=utf-8",
			"txt", "text/plain; charset=utf-8");

	private final String root;

	/** 只快取存在的檔案，避免任意路徑把記憶體塞滿 */
	private final Map<String, Resource> cache = new ConcurrentHashMap<>();

	private record Resource(byte[] body, String contentType, String etag) {
	}

	StaticHandler(String root) {
		this.root = root;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			String method = exchange.getRequestMethod();
			if (!"GET".equals(method) && !"HEAD".equals(method)) {
				exchange.getResponseHeaders().set("Allow", "GET, HEAD");
				Http.sendText(exchange, 405, "Method Not Allowed");
				return;
			}
			String path = exchange.getRequestURI().getPath();
			if (path == null || path.isEmpty() || path.endsWith("/")) {
				path = (path == null ? "/" : path) + "index.html";
			}
			Resource resource = isSafe(path) ? find(path) : null;
			if (resource == null) {
				Http.sendText(exchange, 404, "Not Found");
				return;
			}
			exchange.getResponseHeaders().set("ETag", resource.etag());
			//每次都向伺服器確認，改版後不會拿到舊檔；內容沒變時只回 304
			exchange.getResponseHeaders().set("Cache-Control", "no-cache");
			if (resource.etag().equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
				Http.send(exchange, 304, resource.contentType(), new byte[0]);
				return;
			}
			Http.send(exchange, 200, resource.contentType(), resource.body());
		} finally {
			exchange.close();
		}
	}

	private static boolean isSafe(String path) {
		if (!path.startsWith("/") || path.contains("..") || path.contains("\\") || path.contains("//") || path.contains(":")) {
			return false;
		}
		return path.chars().noneMatch(c -> c < 0x20);
	}

	private Resource find(String path) {
		Resource cached = cache.get(path);
		if (cached != null) {
			return cached;
		}
		Resource loaded = load(path);
		if (loaded != null) {
			cache.put(path, loaded);
		}
		return loaded;
	}

	private Resource load(String path) {
		int dot = path.lastIndexOf('.');
		String contentType = dot < 0 ? null : CONTENT_TYPES.get(path.substring(dot + 1).toLowerCase());
		if (contentType == null) {
			return null;
		}
		try (InputStream in = StaticHandler.class.getResourceAsStream(root + path)) {
			if (in == null) {
				return null;
			}
			byte[] body = in.readAllBytes();
			CRC32 crc = new CRC32();
			crc.update(body);
			return new Resource(body, contentType, "\"" + Long.toHexString(crc.getValue()) + "-" + body.length + "\"");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
