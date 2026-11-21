package com.fullmoney.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * 試算 API 的共用流程：解析參數、呼叫試算、輸出 JSON，並把錯誤轉成使用者看得懂的訊息
 *
 * <p>接受 POST（表單編碼）與 GET（查詢字串）。成功回傳 200；條件不合理回傳 400 與
 * {@code {"error": 訊息, "field": 欄位}}；其他錯誤回傳 500 並寫入紀錄。</p>
 */
public abstract class ApiHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(ApiHandler.class.getName());
	private static final int MAX_BODY_BYTES = 64 * 1024;

	private final String path;

	protected ApiHandler(String path) {
		this.path = path;
	}

	public String getPath() {
		return path;
	}

	@Override
	public final void handle(HttpExchange exchange) throws IOException {
		try {
			//HttpServer 以字首比對路徑，/api/bondxyz 也會進來
			if (!path.equals(exchange.getRequestURI().getPath())) {
				Http.sendJson(exchange, 404, error("找不到這個網址。", null));
				return;
			}
			String method = exchange.getRequestMethod();
			Params params;
			if ("POST".equals(method)) {
				String body = readBody(exchange);
				if (body == null) {
					Http.sendJson(exchange, 413, error("送出的資料過大。", null));
					return;
				}
				params = Params.parse(body);
			} else if ("GET".equals(method) || "HEAD".equals(method)) {
				params = Params.parse(exchange.getRequestURI().getRawQuery());
			} else {
				exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST");
				Http.sendJson(exchange, 405, error("不支援此請求方式。", null));
				return;
			}
			Http.sendJson(exchange, 200, respond(params));
		} catch (BadRequestException e) {
			Http.sendJson(exchange, 400, error(e.getMessage(), e.getField()));
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "API failed: " + path, e);
			Http.sendJson(exchange, 500, error("伺服器試算時發生錯誤，請稍後再試。", null));
		} finally {
			exchange.close();
		}
	}

	/**
	 * @return 可輸出成 JSON 的結果，通常是 LinkedHashMap
	 * @throws BadRequestException 條件不合理
	 */
	protected abstract Object respond(Params params);

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

	private static Map<String, Object> error(String message, String field) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("error", message);
		error.put("field", field);
		return error;
	}
}
