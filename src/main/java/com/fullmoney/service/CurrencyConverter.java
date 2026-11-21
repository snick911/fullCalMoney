package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 幣別轉換，透過新台幣換算，匯率都是 1 單位該幣別可換多少新台幣
 */
public class CurrencyConverter {

	private static final MathContext PRECISION = MathContext.DECIMAL128;
	private static final int MONEY_SCALE = 2;

	public BigDecimal convert(BigDecimal amount, BigDecimal fromRate, BigDecimal toRate) {
		return amount.multiply(fromRate).divide(toRate, PRECISION).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * @return 1 單位來源幣別可換多少目標幣別
	 */
	public BigDecimal crossRate(BigDecimal fromRate, BigDecimal toRate) {
		return fromRate.divide(toRate, PRECISION);
	}
}
