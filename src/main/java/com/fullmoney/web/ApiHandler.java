package com.fullmoney.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * 把 {@link ApiEndpoint} 接到 HttpServer：檢查路徑與請求方式、讀取參數、輸出 JSON
 *
 * <p>接受 POST（表單編碼）與 GET（查詢字串）。程式錯誤寫入紀錄並回傳 500。</p>
 */
public final class ApiHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(ApiHandler.class.getName());
	private static final int MAX_BODY_BYTES = 64 * 1024;

	private final ApiEndpoint endpoint;

	public ApiHandler(ApiEndpoint endpoint) {
		this.endpoint = endpoint;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			//HttpServer 以字首比對路徑，/api/bondxyz 也會進來
			if (!endpoint.getPath().equals(exchange.getRequestURI().getPath())) {
				Http.sendJson(exchange, 404, ApiEndpoint.error("找不到這個網址。", null));
				return;
			}
			String method = exchange.getRequestMethod();
			String rawParams;
			if ("POST".equals(method)) {
				rawParams = readBody(exchange);
				if (rawParams == null) {
					Http.sendJson(exchange, 413, ApiEndpoint.error("送出的資料過大。", null));
					return;
				}
			} else if ("GET".equals(method) || "HEAD".equals(method)) {
				rawParams = exchange.getRequestURI().getRawQuery();
			} else {
				exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST");
				Http.sendJson(exchange, 405, ApiEndpoint.error("不支援此請求方式。", null));
				return;
			}
			ApiResponse response = endpoint.execute(rawParams);
			Http.sendJson(exchange, response.getStatus(), response.getBody());
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "API failed: " + endpoint.getPath(), e);
			Http.sendJson(exchange, 500, ApiEndpoint.error("伺服器試算時發生錯誤，請稍後再試。", null));
		} finally {
			exchange.close();
		}
	}

	/** @return 內容，超過大小上限時為 null */
	private static String readBody(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
			if (body.length > MAX_BODY_BYTES) {
				return null;
			}
			return new String(body, StandardCharsets.UTF_8);
		}
	}
}
