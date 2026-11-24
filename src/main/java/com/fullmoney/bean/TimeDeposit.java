package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 一筆定存的條件
 */
public class TimeDeposit {

	/** 存期（月），1～12 */
	private final int months;
	/** 年利率，比例 ex. 0.035 */
	private final BigDecimal annualRate;

	public TimeDeposit(int months, BigDecimal annualRate) {
		this.months = months;
		this.annualRate = annualRate;
	}

	public int getMonths() {
		return months;
	}

	public BigDecimal getAnnualRate() {
		return annualRate;
	}
}
