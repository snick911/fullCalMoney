package com.fullmoney.bean;

import java.math.BigDecimal;

public class SaveMoney {

	private int theYear;

	private BigDecimal amount=BigDecimal.ZERO;

	private BigDecimal interestRate=BigDecimal.ZERO;

	/**
	 * 本年利息(含本年投入與累積財富的利息)
	 * */
	private BigDecimal interest=BigDecimal.ZERO;

	/**
	 * 純本金部位
	 * */
	private BigDecimal totalBaseAmount=BigDecimal.ZERO;

	/**
	 * 累積財富
	 * */
	private BigDecimal totalSumAmount=BigDecimal.ZERO;

	public int getTheYear() {
		return theYear;
	}

	public void setTheYear(int theYear) {
		this.theYear = theYear;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public BigDecimal getInterestRate() {
		return interestRate;
	}

	public void setInterestRate(BigDecimal interestRate) {
		this.interestRate = interestRate;
	}

	public BigDecimal getInterest() {
		return interest;
	}

	public void setInterest(BigDecimal interest) {
		this.interest = interest;
	}

	public BigDecimal getTotalBaseAmount() {
		return totalBaseAmount;
	}

	public void setTotalBaseAmount(BigDecimal totalBaseAmount) {
		this.totalBaseAmount = totalBaseAmount;
	}

	public BigDecimal getTotalSumAmount() {
		return totalSumAmount;
	}

	public void setTotalSumAmount(BigDecimal totalSumAmount) {
		this.totalSumAmount = totalSumAmount;
	}


}
