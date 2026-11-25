package com.fullmoney.service;

import java.util.ArrayList;
import java.util.List;

import com.fullmoney.bean.DcaResult;
import com.fullmoney.bean.DcaYear;

/**
 * 定期定額：第 1 個月初投入第一次，之後每隔固定月數投入同樣的金額，以年化報酬率計算期末市值
 *
 * <p>每筆投入的市值 = 投入金額 ×（1 + 年化報酬率）^（持有月數 ÷ 12），期末市值為所有投入相加。
 * 買入手續費外加在投入金額上（投入金額全部買進標的）；期末賣出費用 = 期末市值 × 賣出費用率。</p>
 */
public final class DcaCalculator {

	public static final int MONTHS_PER_YEAR = 12;

	/** 二分法的次數：區間每次減半，60 次後已小於 double 的精度 */
	private static final int IRR_ITERATIONS = 100;
	private static final double IRR_LOWEST = -0.9999;

	/**
	 * @param amount 每期投資金額
	 * @param rate 年化報酬率 ex. 0.07
	 * @param interval 每隔幾個月投入一次（1～12）
	 * @param buyFeeRate 買入手續費率 ex. 0.001425
	 * @param minFee 每筆最低手續費
	 * @param sellFeeRate 期末賣出費用率 ex. 0.002425
	 */
	public DcaResult calculate(double amount, double rate, int interval, int years, double buyFeeRate, double minFee, double sellFeeRate) {
		double fee = fee(amount, buyFeeRate, minFee);
		double growth = 1 + rate;
		List<DcaYear> rows = new ArrayList<>(years);
		double value = 0;
		int month = 0;
		for (int year = 1; year <= years; year++) {
			int end = year * MONTHS_PER_YEAR;
			//去年年底的市值再成長一年，加上今年每筆投入成長到年底
			value *= growth;
			for (; month < end; month += interval) {
				value += amount * Math.pow(growth, (double) (end - month) / MONTHS_PER_YEAR);
			}
			int count = count(end, interval);
			rows.add(new DcaYear(year, count, (amount + fee) * count, value));
		}
		int months = years * MONTHS_PER_YEAR;
		int count = count(months, interval);
		double sellCost = value * sellFeeRate;
		double irr = irr(amount + fee, interval, months, value - sellCost);
		return new DcaResult(count, fee, amount * count, value, sellCost, irr, rows);
	}

	/** 每次買入的手續費：投入金額 × 費率，不足最低手續費時以最低手續費計 */
	public static double fee(double amount, double buyFeeRate, double minFee) {
		return Math.max(amount * buyFeeRate, minFee);
	}

	/** @return 前 months 個月投入幾次：第 0、interval、2 × interval…個月各一次 */
	public static int count(int months, int interval) {
		return (months + interval - 1) / interval;
	}

	/**
	 * 每隔 interval 個月投入 payment，以年化報酬率 rate 成長到第 months 個月的終值
	 */
	public static double futureValue(double payment, int interval, int months, double rate) {
		double growth = 1 + rate;
		double sum = 0;
		for (int month = 0; month < months; month += interval) {
			sum += payment * Math.pow(growth, (double) (months - month) / MONTHS_PER_YEAR);
		}
		return sum;
	}

	/**
	 * 實際年化報酬率（內部報酬率）：每次付出 payment（含手續費），期末拿回 target（扣除賣出費用）時，
	 * 讓所有付出的終值剛好等於 target 的年化報酬率。終值隨報酬率遞增，以二分法求解；沒有任何費用時等於原來的年化報酬率
	 */
	public static double irr(double payment, int interval, int months, double target) {
		if (payment <= 0 || target <= 0) {
			return Double.NaN;
		}
		double low = IRR_LOWEST;
		if (futureValue(payment, interval, months, low) >= target) {
			return low;
		}
		double high = 1;
		while (futureValue(payment, interval, months, high) < target) {
			low = high;
			high = high * 2 + 1;
		}
		for (int i = 0; i < IRR_ITERATIONS && high - low > 1e-14; i++) {
			double middle = (low + high) / 2;
			if (futureValue(payment, interval, months, middle) < target) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return (low + high) / 2;
	}
}
