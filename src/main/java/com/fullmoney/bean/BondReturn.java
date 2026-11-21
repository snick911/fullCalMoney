package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 以單一幣別計算的債券報酬，投入成本為 0 時各報酬率為 null
 */
public class BondReturn {

	private final BigDecimal cost;
	private final BigDecimal couponTotal;
	private final BigDecimal redemption;
	private final BigDecimal profit;
	private final BigDecimal couponReturn;
	private final BigDecimal annualCouponReturn;
	private final BigDecimal totalReturn;

	/**
	 * 使現金流淨現值為 0 的年化報酬率(IRR)，以年複利表示，隱含配息以相同報酬率再投入
	 * */
	private final BigDecimal annualReturn;

	public BondReturn(BigDecimal cost, BigDecimal couponTotal, BigDecimal redemption, BigDecimal profit,
			BigDecimal couponReturn, BigDecimal annualCouponReturn, BigDecimal totalReturn, BigDecimal annualReturn) {
		this.cost = cost;
		this.couponTotal = couponTotal;
		this.redemption = redemption;
		this.profit = profit;
		this.couponReturn = couponReturn;
		this.annualCouponReturn = annualCouponReturn;
		this.totalReturn = totalReturn;
		this.annualReturn = annualReturn;
	}

	public BigDecimal getCost() {
		return cost;
	}

	public BigDecimal getCouponTotal() {
		return couponTotal;
	}

	public BigDecimal getRedemption() {
		return redemption;
	}

	public BigDecimal getProfit() {
		return profit;
	}

	public BigDecimal getCouponReturn() {
		return couponReturn;
	}

	public BigDecimal getAnnualCouponReturn() {
		return annualCouponReturn;
	}

	public BigDecimal getTotalReturn() {
		return totalReturn;
	}

	public BigDecimal getAnnualReturn() {
		return annualReturn;
	}
}
