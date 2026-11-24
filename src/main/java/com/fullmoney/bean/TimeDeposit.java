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
	/** 到期後接續下一筆的方式；最後一筆到期後換回，不影響結果 */
	private final DepositRollover rollover;

	public TimeDeposit(int months, BigDecimal annualRate) {
		this(months, annualRate, DepositRollover.COMPOUND);
	}

	public TimeDeposit(int months, BigDecimal annualRate, DepositRollover rollover) {
		this.months = months;
		this.annualRate = annualRate;
		this.rollover = rollover;
	}

	public int getMonths() {
		return months;
	}

	public BigDecimal getAnnualRate() {
		return annualRate;
	}

	public DepositRollover getRollover() {
		return rollover;
	}
}
