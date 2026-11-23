package com.fullmoney.service;

import com.fullmoney.bean.GlidePath;
import com.fullmoney.bean.InvestmentTier;
import com.fullmoney.bean.RebalanceMode;

/**
 * 一種資產配置在模擬中的設定：四個部位（定存、公債、公司債、股票）各用哪一類標的、每年的目標比例與再平衡方式
 *
 * <p>逐年調整股票比例（{@link GlidePath}）時，減少的股票移到公債，每年依新比例調整（等同每年再平衡）。</p>
 */
public final class Allocation {

	/** 部位：定存、公債、公司債、股票 */
	public static final int SLOTS = 4;
	public static final int DEPOSIT = 0;
	public static final int GOV_BOND = 1;
	public static final int CORPORATE_BOND = 2;
	public static final int STOCK = 3;

	private static final double PERCENT = 100.0;

	private final int[] instruments;
	private final double[][] weights;
	private final RebalanceMode rebalance;
	private final boolean scheduled;

	private Allocation(int[] instruments, double[][] weights, RebalanceMode rebalance, boolean scheduled) {
		this.instruments = instruments;
		this.weights = weights;
		this.rebalance = rebalance;
		this.scheduled = scheduled;
	}

	/**
	 * @param percents 定存、公債、公司債、股票的起始比例（%）
	 * @param stockLevel 股票等級，股票比例為 0 時可為 null
	 * @param age 目前年齡（年齡法則用）
	 * @param steps 模擬的年數（最後不足一年也算一年）
	 */
	public static Allocation of(int[] percents, InvestmentTier stockLevel, GlidePath glide, RebalanceMode rebalance, int age, int steps) {
		int[] instruments = { MarketSimulator.instrumentOf(InvestmentTier.DEPOSIT), MarketSimulator.instrumentOf(InvestmentTier.GOV_BOND),
				MarketSimulator.instrumentOf(InvestmentTier.CORPORATE_BOND),
				stockLevel == null ? MarketSimulator.instrumentOf(InvestmentTier.DEPOSIT) : MarketSimulator.instrumentOf(stockLevel) };
		double stock = percents[STOCK] / PERCENT;
		double[][] weights = new double[steps][SLOTS];
		for (int k = 0; k < steps; k++) {
			double stockNow = stockAt(glide, stock, k, steps, age);
			for (int slot = 0; slot < SLOTS; slot++) {
				weights[k][slot] = percents[slot] / PERCENT;
			}
			weights[k][STOCK] = stockNow;
			weights[k][GOV_BOND] += stock - stockNow;
		}
		return new Allocation(instruments, weights, rebalance, glide != GlidePath.FIXED);
	}

	/** 全部放在同一類標的（列出單一類別方案時用） */
	public static Allocation single(InvestmentTier tier, int steps) {
		int slot = tier == InvestmentTier.DEPOSIT ? DEPOSIT : tier == InvestmentTier.GOV_BOND ? GOV_BOND
				: tier == InvestmentTier.CORPORATE_BOND ? CORPORATE_BOND : STOCK;
		int[] instruments = new int[SLOTS];
		instruments[slot] = MarketSimulator.instrumentOf(tier);
		double[][] weights = new double[steps][SLOTS];
		for (int k = 0; k < steps; k++) {
			weights[k][slot] = 1;
		}
		return new Allocation(instruments, weights, RebalanceMode.NONE, false);
	}

	/**
	 * 第 year 年（0 起算）的股票比例
	 *
	 * <ul>
	 * <li>目標日期：從期滿前 5 年（期間較短時從第 2 年）起逐年降低，最後一年降到起始的一半。</li>
	 * <li>年齡法則：不超過 (100 − 當年年齡)%，超過起始比例時維持起始比例。</li>
	 * </ul>
	 */
	static double stockAt(GlidePath glide, double stock, int year, int steps, int age) {
		if (glide == GlidePath.TARGET_DATE) {
			int first = Math.max(1, steps - GlidePath.TARGET_DATE_YEARS);
			if (year < first) {
				return stock;
			}
			double progress = (year - first + 1) / (double) (steps - first);
			return stock * (1 - (1 - GlidePath.TARGET_DATE_FLOOR) * progress);
		}
		if (glide == GlidePath.AGE_RULE) {
			return Math.min(stock, Math.max(0, (GlidePath.AGE_RULE_BASE - age - year) / PERCENT));
		}
		return stock;
	}

	/** 各部位對應的標的序號（{@link MarketSimulator#INSTRUMENTS}） */
	int[] instruments() {
		return instruments;
	}

	/** [年][部位] 的目標比例（0～1） */
	double[][] weights() {
		return weights;
	}

	RebalanceMode rebalance() {
		return rebalance;
	}

	/** 比例逐年變動時，每年都依新比例調整 */
	boolean isScheduled() {
		return scheduled;
	}

	/** 整個期間的平均比例，用於近似公式篩選 */
	public double[] averageWeights() {
		double[] average = new double[SLOTS];
		for (double[] year : weights) {
			for (int slot = 0; slot < SLOTS; slot++) {
				average[slot] += year[slot] / weights.length;
			}
		}
		return average;
	}

	/** 最後一年的股票比例（%） */
	public double endStockPercent() {
		return weights[weights.length - 1][STOCK] * PERCENT;
	}
}
