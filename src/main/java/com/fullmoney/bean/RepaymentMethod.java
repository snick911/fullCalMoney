package com.fullmoney.bean;

/**
 * 房貸還款方式
 */
public enum RepaymentMethod {

	/** 每期還款金額固定，前期利息佔比較高 */
	EQUAL_PAYMENT("本息平均攤還"),

	/** 每期償還本金固定，月付金逐期遞減 */
	EQUAL_PRINCIPAL("本金平均攤還");

	private final String label;

	RepaymentMethod(String label) {
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
