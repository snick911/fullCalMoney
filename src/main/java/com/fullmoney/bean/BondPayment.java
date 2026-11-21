package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 債券單期現金流
 */
public class BondPayment {

	private final int period;
	private final BigDecimal coupon;
	private final BigDecimal redemption;
	private final BigDecimal homeCoupon;
	private final BigDecimal homeRedemption;

	/**
	 * 到本期為止收回的新台幣合計
	 * */
	private final BigDecimal homeReceived;

	/**
	 * 到本期為止的新台幣報酬率，投入成本為 0 時為 null
	 * */
	private final BigDecimal homeReturn;

	public BondPayment(int period, BigDecimal coupon, BigDecimal redemption, BigDecimal homeCoupon, BigDecimal homeRedemption,
			BigDecimal homeReceived, BigDecimal homeReturn) {
		this.period = period;
		this.coupon = coupon;
		this.redemption = redemption;
		this.homeCoupon = homeCoupon;
		this.homeRedemption = homeRedemption;
		this.homeReceived = homeReceived;
		this.homeReturn = homeReturn;
	}

	public int getPeriod() {
		return period;
	}

	public BigDecimal getCoupon() {
		return coupon;
	}

	public BigDecimal getRedemption() {
		return redemption;
	}

	public BigDecimal getHomeCoupon() {
		return homeCoupon;
	}

	public BigDecimal getHomeRedemption() {
		return homeRedemption;
	}

	public BigDecimal getHomeReceived() {
		return homeReceived;
	}

	public BigDecimal getHomeReturn() {
		return homeReturn;
	}
}
