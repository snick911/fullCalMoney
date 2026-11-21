package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 債券試算條件，比例都用小數表示 ex. 買入價格 98.5 為 0.985，匯率為 1 單位外幣可換多少新台幣
 */
public class BondCondition {

	private final BigDecimal faceValue;
	private final BigDecimal buyPrice;
	private final BigDecimal redemptionPrice;
	private final BigDecimal couponRate;
	private final CouponFrequency frequency;
	private final int year;
	private final BigDecimal feeRate;
	private final BigDecimal taxRate;
	private final BigDecimal buyExchangeRate;
	private final BigDecimal couponExchangeRate;
	private final BigDecimal redemptionExchangeRate;

	public BondCondition(BigDecimal faceValue, BigDecimal buyPrice, BigDecimal redemptionPrice, BigDecimal couponRate,
			CouponFrequency frequency, int year, BigDecimal feeRate, BigDecimal taxRate,
			BigDecimal buyExchangeRate, BigDecimal couponExchangeRate, BigDecimal redemptionExchangeRate) {
		this.faceValue = faceValue;
		this.buyPrice = buyPrice;
		this.redemptionPrice = redemptionPrice;
		this.couponRate = couponRate;
		this.frequency = frequency;
		this.year = year;
		this.feeRate = feeRate;
		this.taxRate = taxRate;
		this.buyExchangeRate = buyExchangeRate;
		this.couponExchangeRate = couponExchangeRate;
		this.redemptionExchangeRate = redemptionExchangeRate;
	}

	public BigDecimal getFaceValue() {
		return faceValue;
	}

	public BigDecimal getBuyPrice() {
		return buyPrice;
	}

	public BigDecimal getRedemptionPrice() {
		return redemptionPrice;
	}

	public BigDecimal getCouponRate() {
		return couponRate;
	}

	public CouponFrequency getFrequency() {
		return frequency;
	}

	public int getYear() {
		return year;
	}

	public BigDecimal getFeeRate() {
		return feeRate;
	}

	public BigDecimal getTaxRate() {
		return taxRate;
	}

	public BigDecimal getBuyExchangeRate() {
		return buyExchangeRate;
	}

	public BigDecimal getCouponExchangeRate() {
		return couponExchangeRate;
	}

	public BigDecimal getRedemptionExchangeRate() {
		return redemptionExchangeRate;
	}
}
