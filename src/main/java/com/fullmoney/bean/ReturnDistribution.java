package com.fullmoney.bean;

/**
 * 蒙地卡羅模擬的年報酬分布
 *
 * <p>兩者的中位數（一般行情）與標準差（預估波動）相同；厚尾分布為自由度 5 的 t 分布，
 * 大部分年份更接近一般行情，但大漲大跌的年份較多，且各資產同時大跌的機會較高。</p>
 */
public enum ReturnDistribution {

	NORMAL("常態分布"),

	FAT_TAIL("厚尾分布（大漲大跌較多）");

	/** t 分布的自由度 */
	public static final int DEGREES_OF_FREEDOM = 5;

	private final String label;

	ReturnDistribution(String label) {
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
