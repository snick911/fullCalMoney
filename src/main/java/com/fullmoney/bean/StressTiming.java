package com.fullmoney.bean;

/**
 * 壓力情境發生的時間點
 */
public enum StressTiming {

	/** 最後一年（長期盤整為最後 5 年）：累積金額最多時遇上，影響最大 */
	END("期滿前"),

	/** 期間正中間 */
	MIDDLE("期間中段"),

	/** 第 1 年（長期盤整為前 5 年）：金額還少，之後有時間回復 */
	START("剛開始投資時");

	private final String label;

	StressTiming(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

	@Override
	public String toString() {
		return label;
	}
}
