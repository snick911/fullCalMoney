package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 可重現的亂數：相同種子在 Java 伺服器與瀏覽器端引擎（TeaVM）都產生相同的數列，
 * 網頁保存種子後重新開啟就能得到同一組結果。
 *
 * <p>不用 java.util.Random，避免不同執行環境的實作不同。演算法為 SplitMix64。</p>
 */
public final class RangeRandom {

	private static final double DOUBLE_UNIT = 1.0 / (1L << 53);

	private long state;
	private double spareGaussian;
	private boolean hasSpareGaussian;

	public RangeRandom(long seed) {
		this.state = seed;
	}

	/** @return 0（含）到 1（不含）之間平均分布的亂數 */
	public double nextDouble() {
		return (nextLong() >>> 11) * DOUBLE_UNIT;
	}

	/** @return 標準常態分布（平均 0、標準差 1）的亂數，以 Box-Muller 轉換，每次產生兩個 */
	public double nextGaussian() {
		if (hasSpareGaussian) {
			hasSpareGaussian = false;
			return spareGaussian;
		}
		double radius = Math.sqrt(-2 * Math.log(1 - nextDouble()));
		double angle = 2 * Math.PI * nextDouble();
		spareGaussian = radius * Math.sin(angle);
		hasSpareGaussian = true;
		return radius * Math.cos(angle);
	}

	private long nextLong() {
		state += 0x9E3779B97F4A7C15L;
		long z = state;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/**
	 * 在 min 到 max（含兩端）之間，以 10 的 -scale 次方為單位平均取值
	 *
	 * @param scale ex. 0 取整數、4 取到萬分之一
	 * @return max 不大於 min 時回傳 min
	 */
	public BigDecimal pick(BigDecimal min, BigDecimal max, int scale) {
		long steps = max.subtract(min).movePointRight(scale).setScale(0, RoundingMode.FLOOR).longValue();
		if (steps <= 0) {
			return min;
		}
		long step = (nextLong() >>> 1) % (steps + 1);
		return min.add(BigDecimal.valueOf(step).movePointLeft(scale));
	}
}
