package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 定存本息以某個匯率換回指定貨幣後的報酬，金額都是指定貨幣
 */
public class DepositReturn {

	/** 到期時 1 單位定存幣別可換多少指定貨幣 */
	private final BigDecimal rate;
	/** 換回的總金額 = 本金換回 + 利息換回 */
	private final BigDecimal value;
	private final BigDecimal principalValue;
	private final BigDecimal interestValue;
	/** 匯兌損益 = 本金換回 − 投入成本 */
	private final BigDecimal exchangeGain;
	/** 總報酬 = 換回的總金額 − 投入成本 */
	private final BigDecimal profit;
	/** 總報酬率，投入成本為 0 時為 null */
	private final BigDecimal totalReturn;
	/** 年化報酬率，投入成本為 0 時為 null */
	private final BigDecimal annualReturn;

	public DepositReturn(BigDecimal rate, BigDecimal value, BigDecimal principalValue, BigDecimal interestValue, BigDecimal exchangeGain,
			BigDecimal profit, BigDecimal totalReturn, BigDecimal annualReturn) {
		this.rate = rate;
		this.value = value;
		this.principalValue = principalValue;
		this.interestValue = interestValue;
		this.exchangeGain = exchangeGain;
		this.profit = profit;
		this.totalReturn = totalReturn;
		this.annualReturn = annualReturn;
	}

	public BigDecimal getRate() {
		return rate;
	}

	public BigDecimal getValue() {
		return value;
	}

	public BigDecimal getPrincipalValue() {
		return principalValue;
	}

	public BigDecimal getInterestValue() {
		return interestValue;
	}

	public BigDecimal getExchangeGain() {
		return exchangeGain;
	}

	public BigDecimal getProfit() {
		return profit;
	}

	public BigDecimal getTotalReturn() {
		return totalReturn;
	}

	public BigDecimal getAnnualReturn() {
		return annualReturn;
	}
}
