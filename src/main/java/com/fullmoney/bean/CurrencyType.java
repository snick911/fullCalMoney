package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 可選的幣別，匯率預設值只是輸入欄位的起始範例，不是實際報價
 */
public enum CurrencyType {

	TWD("新台幣", "1"),
	USD("美元", "32"),
	EUR("歐元", "35"),
	GBP("英鎊", "41"),
	JPY("日圓", "0.21"),
	AUD("澳幣", "21"),
	NZD("紐西蘭幣", "18.5"),
	CAD("加拿大幣", "22.5"),
	CHF("瑞士法郎", "38"),
	HKD("港幣", "4.1"),
	SGD("新加坡幣", "24"),
	CNY("人民幣", "4.5"),
	ZAR("南非幣", "1.8");

	private final String label;
	private final BigDecimal sampleRate;

	CurrencyType(String label, String sampleRate) {
		this.label = label;
		this.sampleRate = new BigDecimal(sampleRate);
	}

	public String getLabel() {
		return label;
	}

	/**
	 * @return 1 單位此幣別可換多少新台幣的範例值
	 */
	public BigDecimal getSampleRate() {
		return sampleRate;
	}

	@Override
	public String toString() {
		return name() + " " + label;
	}
}
