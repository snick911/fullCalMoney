package com.fullmoney.bean;

/**
 * 定存到期後接續下一筆的方式
 */
public enum DepositRollover {

	/** 到期本息全部存入下一筆，利息再生利息 */
	COMPOUND("本息續存"),
	/** 只有本金存入下一筆，利息領出不再計息 */
	PRINCIPAL("本金續存（利息領出）");

	private final String label;

	DepositRollover(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
