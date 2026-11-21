package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 房貸單期還款明細
 */
public class MortgagePayment {

	private final int period;

	/**
	 * 本期適用的年利率 ex. 0.022
	 * */
	private final BigDecimal annualRate;

	private final BigDecimal principal;

	private final BigDecimal interest;

	/**
	 * 本期實際提前還款的本金，不含在月付金內
	 * */
	private final BigDecimal prepayment;

	/**
	 * 本期還款後的剩餘本金
	 * */
	private final BigDecimal balance;

	public MortgagePayment(int period, BigDecimal annualRate, BigDecimal principal, BigDecimal interest,
			BigDecimal prepayment, BigDecimal balance) {
		this.period = period;
		this.annualRate = annualRate;
		this.principal = principal;
		this.interest = interest;
		this.prepayment = prepayment;
		this.balance = balance;
	}

	public int getPeriod() {
		return period;
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

	public BigDecimal getPayment() {
		return principal.add(interest);
	}

	public BigDecimal getPrepayment() {
		return prepayment;
	}

	public BigDecimal getBalance() {
		return balance;
	}
}
