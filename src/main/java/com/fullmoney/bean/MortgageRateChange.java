package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 房貸分段利率，從指定期數起改用新利率
 */
public class MortgageRateChange {

	private final int startPeriod;

	/**
	 * 年利率 ex. 0.022
	 * */
	private final BigDecimal annualRate;

	public MortgageRateChange(int startPeriod, BigDecimal annualRate) {
		this.startPeriod = startPeriod;
		this.annualRate = annualRate;
	}

	public int getStartPeriod() {
		return startPeriod;
	}

	public BigDecimal getAnnualRate() {
		return annualRate;
	}
}
