package com.fullmoney.bean;

import java.math.BigDecimal;
import java.util.List;

/**
 * 依序定存的結果，金額都是定存幣別
 */
public class TimeDepositResult {

	private final List<TimeDepositPeriod> periods;
	private final BigDecimal principal;
	private final BigDecimal totalInterest;
	/** 全部定存的總月數 */
	private final int months;

	public TimeDepositResult(List<TimeDepositPeriod> periods, BigDecimal principal, BigDecimal totalInterest, int months) {
		this.periods = periods;
		this.principal = principal;
		this.totalInterest = totalInterest;
		this.months = months;
	}

	public List<TimeDepositPeriod> getPeriods() {
		return periods;
	}

	public BigDecimal getPrincipal() {
		return principal;
	}

	public BigDecimal getTotalInterest() {
		return totalInterest;
	}

	/** @return 最後拿回的本金加利息（本金續存時，領出的利息也算在內） */
	public BigDecimal getFinalAmount() {
		return principal.add(totalInterest);
	}

	public int getMonths() {
		return months;
	}
}
