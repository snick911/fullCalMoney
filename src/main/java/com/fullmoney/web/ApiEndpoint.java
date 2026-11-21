package com.fullmoney.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一個試算 API：輸入表單編碼的參數，輸出可轉成 JSON 的結果
 *
 * <p>不依賴 HttpServer，網站伺服器（{@link ApiHandler}）與 GitHub Pages 的瀏覽器端引擎共用同一段程式，
 * 所以這裡與它呼叫的類別只能用 TeaVM 支援的 Java 類別（不可用 java.text、String.format、反射）。</p>
 */
public abstract class ApiEndpoint {

	private final String path;

	protected ApiEndpoint(String path) {
		this.path = path;
	}

	public String getPath() {
		return path;
	}

	/**
	 * @param rawParams application/x-www-form-urlencoded 內容，可為 null
	 * @return 成功為 200；條件不合理為 400 與 {@code {"error": 訊息, "field": 欄位}}
	 * @throws RuntimeException 程式錯誤，由呼叫端記錄並回傳 500
	 */
	public ApiResponse execute(String rawParams) {
		try {
			return new ApiResponse(200, respond(Params.parse(rawParams)));
		} catch (BadRequestException e) {
			return new ApiResponse(400, error(e.getMessage(), e.getField()));
		}
	}

	/**
	 * @return 可輸出成 JSON 的結果，通常是 LinkedHashMap
	 * @throws BadRequestException 條件不合理
	 */
	protected abstract Object respond(Params params);

	public static Map<String, Object> error(String message, String field) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("error", message);
		error.put("field", field);
		return error;
	}
}
