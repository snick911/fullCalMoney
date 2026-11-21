package com.fullmoney.bean;

import java.math.BigDecimal;
import java.util.List;

/**
 * 債券試算結果
 */
public class BondResult {

	private final List<BondPayment> payments;

	/**
	 * 以債券幣別計算的報酬
	 * */
	private final BondReturn bondReturn;

	/**
	 * 換回新台幣後的報酬
	 * */
	private final BondReturn homeReturn;

	/**
	 * 到期殖利率，以配息頻率複利的名義年利率，與銀行報價相同，投入成本為 0 時為 null
	 * */
	private final BigDecimal yieldToMaturity;

	/**
	 * 匯率變動造成的新台幣損益 = 新台幣總損益 - 債券幣別總損益 * 買入匯率
	 * */
	private final BigDecimal exchangeGain;

	public BondResult(List<BondPayment> payments, BondReturn bondReturn, BondReturn homeReturn, BigDecimal yieldToMaturity, BigDecimal exchangeGain) {
		this.payments = payments;
		this.bondReturn = bondReturn;
		this.homeReturn = homeReturn;
		this.yieldToMaturity = yieldToMaturity;
		this.exchangeGain = exchangeGain;
	}

	public List<BondPayment> getPayments() {
		return payments;
	}

	public BondReturn getBondReturn() {
		return bondReturn;
	}

	public BondReturn getHomeReturn() {
		return homeReturn;
	}

	public BigDecimal getYieldToMaturity() {
		return yieldToMaturity;
	}

	public BigDecimal getExchangeGain() {
		return exchangeGain;
	}
}
