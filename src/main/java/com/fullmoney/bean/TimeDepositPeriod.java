package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 依序定存的一筆明細，金額都是定存幣別
 */
public class TimeDepositPeriod {

	/** 第幾筆，從 1 開始 */
	private final int index;
	/** 從第幾個月開始存，第 1 筆從第 1 個月開始 */
	private final int startMonth;
	/** 在第幾個月底到期 */
	private final int endMonth;
	private final int months;
	private final BigDecimal annualRate;
	/** 這一筆存入的本金 */
	private final BigDecimal principal;
	private final BigDecimal interest;
	/** 到這一筆為止的利息合計 */
	private final BigDecimal cumulativeInterest;
	/** 到期後接續下一筆的方式 */
	private final DepositRollover rollover;

	public TimeDepositPeriod(int index, int startMonth, int endMonth, int months, BigDecimal annualRate, BigDecimal principal, BigDecimal interest,
			BigDecimal cumulativeInterest, DepositRollover rollover) {
		this.index = index;
		this.startMonth = startMonth;
		this.endMonth = endMonth;
		this.months = months;
		this.annualRate = annualRate;
		this.principal = principal;
		this.interest = interest;
		this.cumulativeInterest = cumulativeInterest;
		this.rollover = rollover;
	}

	public DepositRollover getRollover() {
		return rollover;
	}

	public int getIndex() {
		return index;
	}

	public int getStartMonth() {
		return startMonth;
	}

	public int getEndMonth() {
		return endMonth;
	}

	public int getMonths() {
		return months;
	}

	public BigDecimal getAnnualRate() {
		return annualRate;
	}

	public BigDecimal getPrincipal() {
		return principal;
	}

	public BigDecimal getInterest() {
		return interest;
	}

	/** @return 這一筆到期的本息 */
	public BigDecimal getMaturity() {
		return principal.add(interest);
	}

	public BigDecimal getCumulativeInterest() {
		return cumulativeInterest;
	}
}
