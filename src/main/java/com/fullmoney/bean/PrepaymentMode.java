package com.fullmoney.bean;

/**
 * 提前還款後的攤還方式
 */
public enum PrepaymentMode {

	/** 每期攤還金額不變，提早還清 */
	SHORTEN_TERM("縮短年限"),

	/** 還清時間不變，重算之後的每期金額 */
	REDUCE_PAYMENT("降低月付");

	private final String label;

	PrepaymentMode(String label) {
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
