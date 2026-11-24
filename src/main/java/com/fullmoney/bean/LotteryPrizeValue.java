package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 一個獎項試算用的金額與機率
 */
public final class LotteryPrizeValue {

	private final LotteryPrize prize;
	private final double probability;
	private final BigDecimal amount;
	private final BigDecimal afterTax;
	private final boolean estimated;
	private final BigDecimal expectedValue;

	/**
	 * @param amount 每注稅前獎金
	 * @param afterTax 扣除所得稅與印花稅後的獎金
	 * @param estimated true 表示由獎金池的長期平均估算，不是固定金額或使用者輸入的金額
	 * @param expectedValue 每注平均拿回的稅後金額（機率 × 稅後獎金）
	 */
	public LotteryPrizeValue(LotteryPrize prize, double probability, BigDecimal amount, BigDecimal afterTax, boolean estimated,
			BigDecimal expectedValue) {
		this.prize = prize;
		this.probability = probability;
		this.amount = amount;
		this.afterTax = afterTax;
		this.estimated = estimated;
		this.expectedValue = expectedValue;
	}

	public LotteryPrize getPrize() {
		return prize;
	}

	/** @return 每注中這個獎的機率 */
	public double getProbability() {
		return probability;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public BigDecimal getAfterTax() {
		return afterTax;
	}

	public boolean isEstimated() {
		return estimated;
	}

	public boolean isTaxed() {
		return amount.compareTo(LotteryGame.TAX_FREE_LIMIT) > 0;
	}

	public BigDecimal getExpectedValue() {
		return expectedValue;
	}
}
