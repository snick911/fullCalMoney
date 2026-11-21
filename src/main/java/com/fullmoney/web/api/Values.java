package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fullmoney.web.Params;

/**
 * API 共用的輸入範圍與輸出數值格式
 */
final class Values {

	/** 金額上限，與桌面版的輸入欄位相同 */
	static final String MAX_MONEY = "1000000000";

	/** 匯率範圍，1 單位外幣可換多少新台幣，不可為 0 */
	static final String MIN_EXCHANGE_RATE = "0.0001";
	static final String MAX_EXCHANGE_RATE = "100000";

	private static final int MONEY_SCALE = 2;
	private static final int RATIO_SCALE = 10;

	private Values() {
	}

	/**
	 * 金額輸出到小數兩位。用 HALF_EVEN 與桌面版 NumberFormat 的顯示結果一致
	 */
	static BigDecimal money(BigDecimal amount) {
		return amount == null ? null : amount.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
	}

	/**
	 * @param whole true 時四捨五入到整數（不顯示小數），false 時同 {@link #money(BigDecimal)}
	 */
	static BigDecimal money(BigDecimal amount, boolean whole) {
		if (amount == null || !whole) {
			return money(amount);
		}
		return amount.setScale(0, RoundingMode.HALF_UP);
	}

	/** 網頁的「金額不顯示小數」勾選框，勾選時送出 round=1 */
	static boolean wholeMoney(Params params) {
		return params.flag("round");
	}

	/** 回傳給網頁的金額小數位數 */
	static int moneyDecimals(boolean whole) {
		return whole ? 0 : MONEY_SCALE;
	}

	/**
	 * 比例輸出到小數十位，網頁顯示為百分比，null 表示無法計算
	 */
	static BigDecimal ratio(BigDecimal ratio) {
		return ratio == null ? null : ratio.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
	}

	/** 金額為 0 時回傳 null，表格顯示為空白 */
	static BigDecimal blankIfZero(BigDecimal amount) {
		return amount.signum() == 0 ? null : amount;
	}
}
