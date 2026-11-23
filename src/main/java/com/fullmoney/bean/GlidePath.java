package com.fullmoney.bean;

/**
 * 配置比例隨時間的調整方式（滑行路徑），減少的股票移到公債
 */
public enum GlidePath {

	/** 整個期間維持同一比例 */
	FIXED("固定比例"),

	/**
	 * 目標日期：期滿前最後 5 年（期間較短時從第 2 年起）逐年降低股票比例，最後一年降到起始的一半，
	 * 避免快到期時遇上大跌來不及回本
	 */
	TARGET_DATE("接近期滿逐年降低股票"),

	/** 年齡法則：股票比例不超過「100 − 當年年齡」，每長一歲上限降 1 個百分點 */
	AGE_RULE("依年齡法則（股票 ≤ 100 − 年齡）");

	/** 目標日期：最後幾年逐年降低 */
	public static final int TARGET_DATE_YEARS = 5;

	/** 目標日期：最後一年的股票比例為起始的幾倍 */
	public static final double TARGET_DATE_FLOOR = 0.5;

	/** 年齡法則：股票比例上限 = 此值 − 年齡（%） */
	public static final int AGE_RULE_BASE = 100;

	private final String label;

	GlidePath(String label) {
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
