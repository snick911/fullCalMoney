package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.fullmoney.bean.InvestmentPlan;
import com.fullmoney.bean.InvestmentPlan.Fit;
import com.fullmoney.bean.InvestmentTier;

/**
 * 投資計畫：由本金、期間與期望獲利算出需要的年化報酬率，再找出可以達成的標的與配置
 *
 * <p>年化報酬率 = (目標金額 / 本金)^(1 / 年數) - 1，年數可含月份 ex. 2 年 6 個月 = 2.5 年。
 * 小數次方只能用 double 計算，結果只用於比較與顯示。</p>
 *
 * <p>判斷規則：</p>
 * <ol>
 * <li>從風險最低的類別往上找，報酬率上限（行情好）達得到的類別都是可考慮的方案。</li>
 * <li>列到第一個「一般行情就能達成、且投資期間足夠」的類別，再多列一類較高報酬的選擇，最多 3 個方案。</li>
 * <li>需要的報酬率介於兩類的一般行情報酬率之間時，提出兩類混合的配置建議。</li>
 * <li>連積極成長的上限都達不到時為高風險投機；年化與總報酬都超過 100% 時只能靠運氣（彩券）。</li>
 * </ol>
 */
public class InvestmentPlanner {

	private static final int MONTHS_PER_YEAR = 12;

	/** 最多列出幾個方案 */
	private static final int MAX_OPTIONS = 3;

	/** 需要的年化報酬率與總報酬率都超過 100% 時（ex. 1 年內要翻倍以上），只能靠運氣 */
	private static final double LOTTERY_RATE = 1.0;

	/** 計畫不可行時，以股票／基金的一般行情報酬率估算延長期間、增加本金或降低目標的替代方案 */
	private static final InvestmentTier BENCHMARK = InvestmentTier.STOCK;

	/** 配置建議可混合的兩類，股債平衡本身就是混合，不再拿來配置 */
	private static final InvestmentTier[][] MIXES = {
			{ InvestmentTier.DEPOSIT, InvestmentTier.BOND },
			{ InvestmentTier.BOND, InvestmentTier.STOCK },
			{ InvestmentTier.STOCK, InvestmentTier.AGGRESSIVE } };

	/** 配置比例的單位（%） */
	private static final int MIX_STEP = 10;

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
		plan.setOptions(options(tier, principal, profit, months, rate));
		plan.setAllocation(profit.signum() > 0 && plan.isFeasible() ? allocation(principal, months, rate) : null);
		plan.setRows(rows(principal, target, growth, months));

		//期間短於風險最低方案的建議年數：延長到建議年數時需要的年化報酬率
		if (tier.getMinYears() > 0 && months < tier.getMinYears() * MONTHS_PER_YEAR) {
			double extended = round(Math.pow(growth, 1.0 / tier.getMinYears()) - 1);
			plan.setExtendedRate(decimal(extended));
			plan.setExtendedTier(tierOf(extended, totalReturn));
		}

		if (!plan.isFeasible()) {
			double factor = Math.pow(1 + BENCHMARK.getTypicalRate().doubleValue(), years);
			plan.setBenchmark(BENCHMARK);
			plan.setBenchmarkMonths(monthsNeeded(growth, BENCHMARK.getTypicalRate().doubleValue()));
			plan.setBenchmarkPrincipal(profit.divide(decimal(factor - 1), 0, RoundingMode.HALF_UP));
			plan.setBenchmarkProfit(principal.multiply(decimal(factor - 1)).setScale(0, RoundingMode.HALF_UP));
		}
		return plan;
	}

	/**
	 * 風險最低、行情好時有機會達成的類別
	 *
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

	/** 從風險最低、有機會達成的類別往上列，到第一個穩當的方案再多列一類 */
	private static List<InvestmentPlan.Option> options(InvestmentTier lowest, BigDecimal principal, BigDecimal profit, int months, double rate) {
		List<InvestmentPlan.Option> options = new ArrayList<>();
		if (lowest.getMaxRate() == null) {
			options.add(new InvestmentPlan.Option(lowest, Fit.INFEASIBLE, true, null, null));
			return options;
		}
		boolean settled = false;
		for (InvestmentTier tier : InvestmentTier.values()) {
			if (tier.ordinal() < lowest.ordinal() || tier.getMaxRate() == null || options.size() == MAX_OPTIONS) {
				continue;
			}
			Fit fit = rate <= tier.getTypicalRate().doubleValue() + EPSILON ? Fit.COMFORTABLE : Fit.STRETCH;
			boolean horizonOk = months >= tier.getMinYears() * MONTHS_PER_YEAR;
			options.add(new InvestmentPlan.Option(tier, fit, horizonOk, grow(principal, tier.getTypicalRate(), months), grow(principal, tier.getMaxRate(), months)));
			//期望獲利為 0 只要保本，不必再列其他類別
			if (settled || profit.signum() == 0) {
				break;
			}
			settled = fit == Fit.COMFORTABLE && horizonOk;
		}
		return options;
	}

	/** 需要的報酬率介於兩類的一般行情報酬率之間時，算出高風險那一類至少要占多少比例 */
	private static InvestmentPlan.Allocation allocation(BigDecimal principal, int months, double rate) {
		for (InvestmentTier[] mix : MIXES) {
			double low = mix[0].getTypicalRate().doubleValue();
			double high = mix[1].getTypicalRate().doubleValue();
			if (rate <= low + EPSILON || rate >= high - EPSILON) {
				continue;
			}
			int highPercent = (int) Math.ceil((rate - low) / (high - low) * (100 / MIX_STEP) - EPSILON) * MIX_STEP;
			if (highPercent <= 0 || highPercent >= 100) {
				return null;
			}
			BigDecimal blended = mix[0].getTypicalRate().multiply(BigDecimal.valueOf(100 - highPercent))
					.add(mix[1].getTypicalRate().multiply(BigDecimal.valueOf(highPercent))).movePointLeft(2);
			boolean horizonOk = months >= mix[1].getMinYears() * MONTHS_PER_YEAR;
			return new InvestmentPlan.Allocation(mix[0], mix[1], highPercent, blended, grow(principal, blended, months), horizonOk);
		}
		return null;
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

	/** 本金以年報酬率 rate 複利 months 個月後的金額 */
	private static BigDecimal grow(BigDecimal principal, BigDecimal rate, int months) {
		return principal.multiply(decimal(Math.pow(1 + rate.doubleValue(), months / (double) MONTHS_PER_YEAR)));
	}

	/** 以年報酬率 rate 讓本金成長為 growth 倍需要的月數 */
	private static int monthsNeeded(double growth, double rate) {
		return growth <= 1 ? 0 : (int) Math.ceil(Math.log(growth) / Math.log(1 + rate) * MONTHS_PER_YEAR - EPSILON);
	}

	private static double round(double rate) {
		return rate < RATE_PRECISION ? Math.round(rate * RATE_PRECISION) / RATE_PRECISION : rate;
	}

	private static BigDecimal decimal(double value) {
		return BigDecimal.valueOf(value);
	}
}
