package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.fullmoney.bean.InvestmentPlan;
import com.fullmoney.bean.InvestmentTier;

/**
 * 投資計畫：由本金、期間與期望獲利算出需要的年化報酬率，再依 {@link InvestmentTier} 的規則建議標的
 *
 * <p>年化報酬率 = (目標金額 / 本金)^(1 / 年數) - 1，年數可含月份 ex. 2 年 6 個月 = 2.5 年。
 * 小數次方只能用 double 計算，結果只用於比較與顯示。</p>
 */
public class InvestmentPlanner {

	private static final int MONTHS_PER_YEAR = 12;

	/** 需要的年化報酬率與總報酬率都超過 100% 時（ex. 1 年內要翻倍以上），只能靠運氣 */
	private static final double LOTTERY_RATE = 1.0;

	/** 計畫不可行時，以股票／基金的報酬率上限估算延長期間、增加本金或降低目標的替代方案 */
	private static final InvestmentTier BENCHMARK = InvestmentTier.STOCK;

	/** 年化報酬率取到小數第 6 位再比較，避免 5% 因浮點誤差算成 5.0000000001% */
	private static final double RATE_PRECISION = 1e6;

	private static final double EPSILON = 1e-9;

	private static final int RATIO_SCALE = 10;

	/**
	 * @param principal 投資本金，須大於 0
	 * @param months 投資期間（月），須大於 0
	 * @param profit 期望獲利金額，不可為負
	 */
	public InvestmentPlan plan(BigDecimal principal, int months, BigDecimal profit) {
		BigDecimal target = principal.add(profit);
		double growth = target.doubleValue() / principal.doubleValue();
		double years = months / (double) MONTHS_PER_YEAR;
		double rate = round(Math.pow(growth, 1 / years) - 1);
		double totalReturn = growth - 1;
		InvestmentTier tier = tierOf(rate, totalReturn);

		InvestmentPlan plan = new InvestmentPlan();
		plan.setPrincipal(principal);
		plan.setProfit(profit);
		plan.setTarget(target);
		plan.setMonths(months);
		plan.setTotalReturn(profit.divide(principal, RATIO_SCALE, RoundingMode.HALF_UP));
		plan.setMultiple(target.divide(principal, RATIO_SCALE, RoundingMode.HALF_UP));
		plan.setRequiredRate(decimal(rate));
		plan.setTier(tier);
		plan.setRows(rows(principal, target, growth, months));

		List<InvestmentPlan.Scenario> scenarios = new ArrayList<>();
		for (InvestmentTier each : InvestmentTier.values()) {
			if (each.getMaxRate() != null) {
				scenarios.add(scenario(each, principal, growth, years, rate));
			}
		}
		plan.setScenarios(scenarios);

		//期間短於建議年數：延長到建議年數時需要的年化報酬率
		if (tier.getMinYears() > 0 && months < tier.getMinYears() * MONTHS_PER_YEAR) {
			double extended = round(Math.pow(growth, 1.0 / tier.getMinYears()) - 1);
			plan.setExtendedRate(decimal(extended));
			plan.setExtendedTier(tierOf(extended, totalReturn));
		}

		if (!plan.isFeasible()) {
			double benchmarkRate = BENCHMARK.getMaxRate().doubleValue();
			double factor = Math.pow(1 + benchmarkRate, years);
			plan.setBenchmark(scenario(BENCHMARK, principal, growth, years, rate));
			plan.setBenchmarkPrincipal(profit.divide(decimal(factor - 1), 0, RoundingMode.HALF_UP));
			plan.setBenchmarkProfit(principal.multiply(decimal(factor - 1)).setScale(0, RoundingMode.HALF_UP));
		}
		return plan;
	}

	/**
	 * @param rate 需要的年化報酬率
	 * @param totalReturn 總報酬率（期望獲利 / 本金）
	 */
	public static InvestmentTier tierOf(double rate, double totalReturn) {
		if (rate > LOTTERY_RATE + EPSILON && totalReturn > LOTTERY_RATE + EPSILON) {
			return InvestmentTier.LOTTERY;
		}
		for (InvestmentTier tier : InvestmentTier.values()) {
			if (tier.getMaxRate() != null && rate <= tier.getMaxRate().doubleValue() + EPSILON) {
				return tier;
			}
		}
		return InvestmentTier.SPECULATIVE;
	}

	/** 每滿一年一列，最後不足一年的月份另列；最後一列剛好是目標金額 */
	private static List<InvestmentPlan.Row> rows(BigDecimal principal, BigDecimal target, double growth, int months) {
		List<InvestmentPlan.Row> rows = new ArrayList<>();
		for (int m = MONTHS_PER_YEAR; m <= months; m += MONTHS_PER_YEAR) {
			//(1 + 年化報酬率)^年數 = growth^(m / months)，避免年化報酬率極大時溢位
			BigDecimal balance = m == months ? target : principal.multiply(decimal(Math.pow(growth, m / (double) months)));
			rows.add(new InvestmentPlan.Row(m, balance));
		}
		if (months % MONTHS_PER_YEAR != 0) {
			rows.add(new InvestmentPlan.Row(months, target));
		}
		return rows;
	}

	private static InvestmentPlan.Scenario scenario(InvestmentTier tier, BigDecimal principal, double growth, double years, double requiredRate) {
		double rate = tier.getMaxRate().doubleValue();
		BigDecimal endAmount = principal.multiply(decimal(Math.pow(1 + rate, years)));
		boolean reached = requiredRate <= rate + EPSILON;
		int monthsNeeded = growth <= 1 ? 0 : (int) Math.ceil(Math.log(growth) / Math.log(1 + rate) * MONTHS_PER_YEAR - EPSILON);
		return new InvestmentPlan.Scenario(tier, endAmount, reached, monthsNeeded);
	}

	private static double round(double rate) {
		return rate < RATE_PRECISION ? Math.round(rate * RATE_PRECISION) / RATE_PRECISION : rate;
	}

	private static BigDecimal decimal(double value) {
		return BigDecimal.valueOf(value);
	}
}
