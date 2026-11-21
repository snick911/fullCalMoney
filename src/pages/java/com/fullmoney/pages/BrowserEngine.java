package com.fullmoney.pages;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.teavm.jso.JSExport;

import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.ApiResponse;
import com.fullmoney.web.Json;
import com.fullmoney.web.api.ApiEndpoints;

/**
 * GitHub Pages 用的瀏覽器端試算引擎
 *
 * <p>GitHub Pages 只能放靜態檔案，無法執行 Java 伺服器。建置時由 TeaVM 把這個類別與它用到的試算程式
 * （service、bean、web.api）編譯成 JavaScript 模組 {@code js/engine.js}，網頁直接在瀏覽器中呼叫，
 * 計算結果與伺服器版完全相同。只在 Maven 的 pages 設定檔中編譯，build.bat 不會用到。</p>
 */
public final class BrowserEngine {

	private static final Map<String, ApiEndpoint> ENDPOINTS = new HashMap<>();

	static {
		for (ApiEndpoint endpoint : ApiEndpoints.all()) {
			ENDPOINTS.put(endpoint.getPath(), endpoint);
		}
	}

	private BrowserEngine() {
	}

	/**
	 * @param path 與伺服器相同的 API 路徑 ex. /api/mortgage
	 * @param query 表單編碼的參數 ex. loanAmount=10000000&amp;rate=2.2
	 * @return JSON {@code {"status": HTTP 狀態碼, "body": 與伺服器 API 相同的內容}}
	 */
	@JSExport
	public static String call(String path, String query) {
		ApiResponse response;
		ApiEndpoint endpoint = ENDPOINTS.get(path);
		if (endpoint == null) {
			response = new ApiResponse(404, ApiEndpoint.error("找不到這個試算項目。", null));
		} else {
			try {
				response = endpoint.execute(query);
			} catch (RuntimeException e) {
				response = new ApiResponse(500, ApiEndpoint.error("試算時發生錯誤，請重新整理頁面後再試。", null));
			}
		}
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("status", response.getStatus());
		envelope.put("body", response.getBody());
		return Json.write(envelope);
	}
}
