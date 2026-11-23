package com.fullmoney.bean;

/**
 * 壓力測試的市場情境：在指定的年份把各資產的報酬換成情境的數值，其他年份照常隨機
 *
 * <p>數值參考 2008 年金融海嘯、2022 年升息循環與日本「失落的十年」，依本網站的標的類別簡化，
 * 依序為定存、公債、公司債、定存型股票、穩健型股票、穩健成長型股票、積極成長型股票、高波動型股票（與 {@link InvestmentTier} 前 8 類相同）。</p>
 */
public enum StressScenario {

	NONE("不做壓力測試", null, 0, false, null),

	/** 一年內股市重挫，資金逃向公債 */
	CRASH("金融海嘯", new double[] { 0.0175, 0.06, -0.15, -0.25, -0.35, -0.40, -0.50, -0.60 }, 1, false,
			"一年內股票下跌 25%～60%（依等級）、公司債下跌 15%，公債因避險買盤上漲 6%"),

	/** 利率快速上升，股債同時下跌 */
	RATE_HIKE("升息循環", new double[] { 0.03, -0.10, -0.14, -0.10, -0.18, -0.22, -0.30, -0.40 }, 1, false,
			"一年內利率快速上升：公債下跌 10%、公司債下跌 14%、股票下跌 10%～40%，定存利率升到 3%"),

	/** 連續數年沒有成長，仍有正常的波動 */
	STAGNATION("長期盤整", new double[] { 0.01, 0.02, 0.03, 0, 0, 0, 0, 0 }, 5, true,
			"連續 5 年股票的一般行情為 0%（仍會上下波動）、公司債 3%、公債 2%、定存 1%");

	private final String label;
	private final double[] returns;
	private final int years;
	private final boolean random;
	private final String description;

	StressScenario(String label, double[] returns, int years, boolean random, String description) {
		this.label = label;
		this.returns = returns;
		this.years = years;
		this.random = random;
		this.description = description;
	}

	public String getLabel() {
		return label;
	}

	/**
	 * @param instrument 標的類別的序號（0 定存 … 7 高波動型股票）
	 * @return 情境年份的年報酬率（random 為 true 時是一般行情，仍加上隨機波動）
	 */
	public double getReturn(int instrument) {
		return returns[instrument];
	}

	/** 情境持續幾年 */
	public int getYears() {
		return years;
	}

	/** true：情境年份只改一般行情、保留隨機波動；false：情境年份的報酬固定為情境數值 */
	public boolean isRandom() {
		return random;
	}

	public String getDescription() {
		return description;
	}

	@Override
	public String toString() {
		return label;
	}
}
