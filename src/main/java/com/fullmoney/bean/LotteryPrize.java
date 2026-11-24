package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 彩券的一個獎項
 *
 * <p>獎金有兩種：固定獎金（fixedAmount），或由獎金池依比率分配（poolShare，例如大樂透頭獎 82%）。
 * 獎金池的獎項每期金額不同，試算時以長期平均估算。</p>
 *
 * <p>不用 record：瀏覽器端引擎（TeaVM）不支援 record 自動產生的方法。</p>
 */
public final class LotteryPrize {

	private final String name;
	private final String rule;
	private final long combinations;
	private final BigDecimal fixedAmount;
	private final BigDecimal poolShare;

	private LotteryPrize(String name, String rule, long combinations, BigDecimal fixedAmount, BigDecimal poolShare) {
		this.name = name;
		this.rule = rule;
		this.combinations = combinations;
		this.fixedAmount = fixedAmount;
		this.poolShare = poolShare;
	}

	/**
	 * @param combinations 所有號碼組合中，中這個獎的組合數 ex. 今彩539 貳獎 C(5,4) × C(34,1) = 170
	 * @param amount 每注獎金 ex. "20000"
	 */
	static LotteryPrize fixed(String name, String rule, long combinations, String amount) {
		return new LotteryPrize(name, rule, combinations, new BigDecimal(amount), null);
	}

	/**
	 * @param share 分到獎金池的比率 ex. "0.82"
	 */
	static LotteryPrize pool(String name, String rule, long combinations, String share) {
		return new LotteryPrize(name, rule, combinations, null, new BigDecimal(share));
	}

	/** @return ex. 頭獎 */
	public String getName() {
		return name;
	}

	/** @return 中獎條件 ex. 中 5 個號碼 */
	public String getRule() {
		return rule;
	}

	/** @return 中這個獎的號碼組合數 */
	public long getCombinations() {
		return combinations;
	}

	/** @return 固定獎金，由獎金池分配時為 null */
	public BigDecimal getFixedAmount() {
		return fixedAmount;
	}

	/** @return 分到獎金池的比率，固定獎金時為 null */
	public BigDecimal getPoolShare() {
		return poolShare;
	}

	public boolean isPool() {
		return poolShare != null;
	}
}
