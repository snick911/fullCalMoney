package com.fullmoney.web;

/**
 * API 回應：HTTP 狀態碼與可轉成 JSON 的內容
 */
public final class ApiResponse {

	private final int status;
	private final Object body;

	public ApiResponse(int status, Object body) {
		this.status = status;
		this.body = body;
	}

	public int getStatus() {
		return status;
	}

	public Object getBody() {
		return body;
	}
}
