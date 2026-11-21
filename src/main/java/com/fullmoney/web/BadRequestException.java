package com.fullmoney.web;

/**
 * 輸入條件不合理，訊息會直接顯示給使用者，必須寫給使用者看
 */
public class BadRequestException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * 出問題的欄位名稱，清單欄位為「名稱:索引」 ex. ratePeriod:0，無法對應欄位時為 null
	 * */
	private final String field;

	public BadRequestException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}
}
