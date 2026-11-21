package com.fullmoney.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fullmoney.bean.SaveMoneyResult;

/**
 * 複利試算（浮動）：每年的利率與額外增加投入在區間內隨機取值，其餘同 {@link MoneyCalculator}
 *
 * <p>相同種子得到相同結果，換一個種子就是另一組亂數。</p>
 */
public class FloatingMoneyCalculator {

	/** 利率取到萬分之一 ex. 0.0437 = 4.37% */
	private static final int RATE_SCALE = 4;

	/** 額外增加投入取到元 */
	private static final int AMOUNT_SCALE = 0;

	private final MoneyCalculator calculator = new MoneyCalculator();

	/**
	 * @param initialAmount 原始本金，只在第 1 年年初投入一次
	 * @param baseAmount 每年固定投入金額
	 * @param addMin 每年額外增加投入的最低值，第 2 年起每年在 addMin 到 addMax 之間取值
	 * @param addMax 每年額外增加投入的最高值
	 * @param rateMin 年利率最低值 ex. 0.02，每年在 rateMin 到 rateMax 之間取值
	 * @param rateMax 年利率最高值 ex. 0.06
	 * @param totalYear 總年數
	 * @param seed 亂數種子
	 */
	public SaveMoneyResult calculate(BigDecimal initialAmount, BigDecimal baseAmount, BigDecimal addMin, BigDecimal addMax,
			BigDecimal rateMin, BigDecimal rateMax, int totalYear, long seed) {
		RangeRandom random = new RangeRandom(seed);
		List<BigDecimal> addAmounts = new ArrayList<>(totalYear);
		List<BigDecimal> rates = new ArrayList<>(totalYear);
		for (int i = 0; i < totalYear; i++) {
			rates.add(random.pick(rateMin, rateMax, RATE_SCALE));
			addAmounts.add(i == 0 ? BigDecimal.ZERO : random.pick(addMin, addMax, AMOUNT_SCALE));
		}
		return calculator.calculate(initialAmount, baseAmount, addAmounts, rates);
	}
}
