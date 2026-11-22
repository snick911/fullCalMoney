package com.fullmoney.service;

import java.util.Arrays;

/**
 * 蒙地卡羅模擬：產生一組共用的市場走勢，估算某個報酬率與波動的投資達成目標的機率與期滿金額分布
 *
 * <p>模型：每年的報酬率為對數常態分布，ln(1 + 年報酬) ~ N(ln(1 + 一般行情報酬率), 波動²)，
 * 所以「一般行情」就是走勢的中位數；不足一年的最後一段依比例縮放。每年內的成長平均分攤到每個月，
 * 本金在第 1 個月初投入、每月月初投入，與 {@link InvestmentPlanner#futureValue} 的逐月複利一致（波動為 0 時結果相同）。</p>
 *
 * <p>所有配置使用同一組亂數（相同的市場情境），比較才公平；種子固定，相同條件每次結果相同，
 * 伺服器與瀏覽器端引擎也相同。</p>
 */
public final class MarketSimulator {

	/** 模擬的走勢數量 */
	public static final int PATHS = 500;

	private static final long SEED = 20_261_122L;

	private static final int MONTHS_PER_YEAR = 12;

	private static final double EPSILON = 1e-12;

	private final int[] stepMonths;
	private final double[] stepYears;
	private final double[] stepScale;
	private final double[][] shocks;

	/**
	 * @param months 投資期間（月），須大於 0
	 */
	public MarketSimulator(int months) {
		int steps = (months + MONTHS_PER_YEAR - 1) / MONTHS_PER_YEAR;
		stepMonths = new int[steps];
		stepYears = new double[steps];
		stepScale = new double[steps];
		for (int k = 0; k < steps; k++) {
			stepMonths[k] = Math.min(MONTHS_PER_YEAR, months - k * MONTHS_PER_YEAR);
			stepYears[k] = stepMonths[k] / (double) MONTHS_PER_YEAR;
			stepScale[k] = Math.sqrt(stepYears[k]);
		}
		RangeRandom random = new RangeRandom(SEED);
		shocks = new double[PATHS][steps];
		for (int path = 0; path < PATHS; path++) {
			for (int k = 0; k < steps; k++) {
				shocks[path][k] = random.nextGaussian();
			}
		}
	}

	/**
	 * @param rate 一般行情（中位數）年報酬率 ex. 0.05
	 * @param volatility 年波動（對數報酬的標準差）ex. 0.18
	 * @return 每條走勢的期滿金額，已由小到大排序
	 */
	public double[] simulate(double principal, double monthly, double rate, double volatility) {
		double drift = Math.log(1 + rate);
		double[] finals = new double[PATHS];
		for (int path = 0; path < PATHS; path++) {
			double balance = principal;
			for (int k = 0; k < stepMonths.length; k++) {
				double logGrowth = drift * stepYears[k] + volatility * stepScale[k] * shocks[path][k];
				if (monthly == 0) {
					balance *= Math.exp(logGrowth);
					continue;
				}
				//每月月初投入，該段成長平均分攤到每個月：Σ g^j（j = 1..n）= g(G - 1)/(g - 1)，g 為月成長、G = g^n
				//（連乘取代第二次 exp，配置搜尋時每步只算一次 exp）
				int n = stepMonths[k];
				double monthGrowth = Math.exp(logGrowth / n);
				double growth = monthGrowth;
				for (int j = 1; j < n; j++) {
					growth *= monthGrowth;
				}
				balance = balance * growth + monthly * (Math.abs(monthGrowth - 1) < EPSILON ? n : monthGrowth * (growth - 1) / (monthGrowth - 1));
			}
			finals[path] = balance;
		}
		Arrays.sort(finals);
		return finals;
	}

	/** @return 期滿金額達到 target 的走勢比例 */
	public static double probability(double[] sortedFinals, double target) {
		int low = 0;
		int high = sortedFinals.length;
		//第一個不小於 target 的位置
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (sortedFinals[middle] < target - EPSILON * Math.max(1, Math.abs(target))) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		return (sortedFinals.length - low) / (double) sortedFinals.length;
	}

	/**
	 * @param fraction ex. 0.1 為第 10 百分位（只有 10% 的走勢更差）
	 */
	public static double percentile(double[] sortedFinals, double fraction) {
		double position = fraction * (sortedFinals.length - 1);
		int index = (int) Math.floor(position);
		if (index >= sortedFinals.length - 1) {
			return sortedFinals[sortedFinals.length - 1];
		}
		double weight = position - index;
		return sortedFinals[index] * (1 - weight) + sortedFinals[index + 1] * weight;
	}
}
