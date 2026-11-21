package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.fullmoney.bean.InvestmentPlan;
import com.fullmoney.bean.InvestmentPlan.Fit;
import com.fullmoney.bean.InvestmentTier;

/**
 * 投資計畫：由本金、每月投資、期間與期望獲利算出需要的年化報酬率，再找出可以達成的標的與配置
 *
 * <p>投入方式：本金在第 1 個月初投入，之後每月月初投入固定金額與浮動金額。期滿金額 = 總投入 + 期望獲利。
 * 年化報酬率 r 換算成月報酬率 (1 + r)^(1/12) - 1 逐月複利；沒有每月投資時可直接用公式求 r，
 * 有每月投資時以二分法求解。小數次方只能用 double 計算，結果只用於比較與顯示。</p>
 *
 * <p>判斷規則：</p>
 * <ol>
 * <li>從風險最低的類別往上找，報酬率上限（行情好）達得到的類別都是可考慮的方案。</li>
 * <li>列到第一個「一般行情就能達成、且投資期間足夠」的類別，再多列一類較高報酬的選擇，最多 3 個方案。</li>
 * <li>需要的報酬率介於兩類的一般行情報酬率之間時，提出兩類混合的配置建議。</li>
 * <li>連積極成長的上限都達不到時為高風險投機；年化與總報酬都超過 100% 時只能靠運氣（彩券）。</li>
 * <li>每月浮動投資以區間中間值為預期情況，另外計算只投入下限、投入上限時需要的報酬率。</li>
 * </ol>
 */
public class InvestmentPlanner {

	private static final int MONTHS_PER_YEAR = 12;

	/** 最多列出幾個方案 */
	private static final int MAX_OPTIONS = 3;

	/** 需要的年化報酬率與總報酬率都超過 100% 時（ex. 1 年內要翻倍以上），只能靠運氣 */
	private static final double LOTTERY_RATE = 1.0;

	/** 計畫不可行時，以股票／基金的一般行情報酬率估算延長期間、增加本金或每月投資、降低目標的替代方案 */
	private static final InvestmentTier BENCHMARK = InvestmentTier.STOCK;

	/** 估算延長期間時最多找到 1000 年 */
	private static final int MAX_SEARCH_MONTHS = 12_000;

	/** 配置建議可混合的兩類，股債平衡本身就是混合，不再拿來配置 */
	private static final InvestmentTier[][] MIXES = {
			{ InvestmentTier.DEPOSIT, InvestmentTier.BOND },
			{ InvestmentTier.BOND, InvestmentTier.STOCK },
			{ InvestmentTier.STOCK, InvestmentTier.AGGRESSIVE } };

	/** 配置比例的單位（%） */
	private static final int MIX_STEP = 10;

	/** 年化報酬率取到小數第 6 位再比較，避免 5% 因浮點誤差算成 5.0000000001% */
	private static final double RATE_PRECISION = 1e6;

	private static final int SOLVER_STEPS = 200;

	/** double 最大約 2^1024 */
	private static final int MAX_DOUBLINGS = 1000;

	private static final double EPSILON = 1e-9;

	private static final int RATIO_SCALE = 10;

	private static final BigDecimal TWO = BigDecimal.valueOf(2);

	/**
	 * @param principal 投資本金，可為 0
	 * @param monthlyFixed 每月固定投資金額
	 * @param floatMin 每月浮動投資金額的最低值
	 * @param floatMax 每月浮動投資金額的最高值，不小於 floatMin
	 * @param months 投資期間（月），須大於 0
	 * @param profit 期望獲利金額，不可為負
	 * @throws IllegalArgumentException 預期情況沒有投入任何資金
	 */
	public InvestmentPlan plan(BigDecimal principal, BigDecimal monthlyFixed, BigDecimal floatMin, BigDecimal floatMax, int months,
			BigDecimal profit) {
		BigDecimal monthly = monthlyFixed.add(floatMin.add(floatMax).divide(TWO));
		BigDecimal invested = invested(principal, monthly, months);
		if (invested.signum() <= 0) {
			throw new IllegalArgumentException("沒有投入任何資金");
		}
		BigDecimal target = invested.add(profit);
		double p = principal.doubleValue();
		double m = monthly.doubleValue();
		double rate = round(requiredRate(p, m, months, profit.doubleValue()));
		double totalReturn = profit.doubleValue() / invested.doubleValue();
		InvestmentTier tier = tierOf(rate, totalReturn);

		InvestmentPlan plan = new InvestmentPlan();
		plan.setPrincipal(principal);
		plan.setMonthly(monthly);
		plan.setInvested(invested);
		plan.setProfit(profit);
		plan.setTarget(target);
		plan.setMonths(months);
		plan.setTotalReturn(profit.divide(invested, RATIO_SCALE, RoundingMode.HALF_UP));
		plan.setMultiple(target.divide(invested, RATIO_SCALE, RoundingMode.HALF_UP));
		plan.setRequiredRate(decimal(rate));
		plan.setTier(tier);
		if (floatMax.compareTo(floatMin) > 0) {
			plan.setLowCase(floatingCase(principal, monthlyFixed.add(floatMin), months, profit));
			plan.setHighCase(floatingCase(principal, monthlyFixed.add(floatMax), months, profit));
		}
		BigDecimal lowRate = plan.getLowCase() == null ? null : plan.getLowCase().getRate();
		plan.setOptions(options(tier, p, m, months, rate, profit, plan.getLowCase() != null, lowRate, target));
		plan.setAllocation(profit.signum() > 0 && plan.isFeasible() ? allocation(p, m, months, rate) : null);
		plan.setRows(rows(principal, monthly, target, months, rate));

		//期間短於風險最低方案的建議年數：延長到建議年數、每月照常投入時需要的年化報酬率
		int minMonths = tier.getMinYears() * MONTHS_PER_YEAR;
		if (minMonths > months) {
			double extended = round(requiredRate(p, m, minMonths, profit.doubleValue()));
			plan.setExtendedRate(decimal(extended));
			plan.setExtendedTier(tierOf(extended, profit.doubleValue() / invested(principal, monthly, minMonths).doubleValue()));
		}

		if (!plan.isFeasible()) {
			benchmark(plan, p, m, months, profit.doubleValue());
		}
		return plan;
	}

	/**
	 * 風險最低、行情好時有機會達成的類別
	 *
	 * @param rate 需要的年化報酬率
	 * @param totalReturn 總報酬率（期望獲利 / 總投入）
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

	/**
	 * 本金在第 1 個月初投入、每月月初投入 monthly，以年化報酬率 annualRate 逐月複利，months 個月後的金額
	 */
	public static double futureValue(double principal, double monthly, double annualRate, int months) {
		if (monthly == 0) {
			return principal * Math.pow(1 + annualRate, months / (double) MONTHS_PER_YEAR);
		}
		double rate = Math.pow(1 + annualRate, 1.0 / MONTHS_PER_YEAR) - 1;
		double growth = Math.pow(1 + rate, months);
		//月報酬率趨近 0 時 (growth - 1) / rate 會失去精度，直接用月數
		double annuity = Math.abs(rate) < EPSILON ? months : (1 + rate) * (growth - 1) / rate;
		return principal * growth + monthly * annuity;
	}

	/**
	 * 需要的年化報酬率：期滿金額 = 總投入 + 期望獲利
	 */
	public static double requiredRate(double principal, double monthly, int months, double profit) {
		if (profit <= 0) {
			return 0;
		}
		double target = principal + monthly * months + profit;
		if (monthly == 0) {
			return Math.pow(target / principal, MONTHS_PER_YEAR / (double) months) - 1;
		}
		double low = 0;
		double high = 1;
		//先倍增找到上界，極端目標（1 個月要上億倍）可能需要很大的報酬率
		for (int i = 0; i < MAX_DOUBLINGS && futureValue(principal, monthly, high, months) < target; i++) {
			high *= 2;
		}
		for (int i = 0; i < SOLVER_STEPS; i++) {
			double middle = (low + high) / 2;
			if (futureValue(principal, monthly, middle, months) < target) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return high;
	}

	private static BigDecimal invested(BigDecimal principal, BigDecimal monthly, int months) {
		return principal.add(monthly.multiply(BigDecimal.valueOf(months)));
	}

	private static InvestmentPlan.Case floatingCase(BigDecimal principal, BigDecimal monthly, int months, BigDecimal profit) {
		BigDecimal invested = invested(principal, monthly, months);
		if (invested.signum() <= 0) {
			return new InvestmentPlan.Case(monthly, invested, null, null);
		}
		double rate = round(requiredRate(principal.doubleValue(), monthly.doubleValue(), months, profit.doubleValue()));
		return new InvestmentPlan.Case(monthly, invested, decimal(rate), tierOf(rate, profit.doubleValue() / invested.doubleValue()));
	}

	/** 從風險最低、有機會達成的類別往上列，到第一個穩當的方案再多列一類 */
	private static List<InvestmentPlan.Option> options(InvestmentTier lowest, double principal, double monthly, int months, double rate,
			BigDecimal profit, boolean floating, BigDecimal lowRate, BigDecimal target) {
		List<InvestmentPlan.Option> options = new ArrayList<>();
		if (lowest.getMaxRate() == null) {
			options.add(new InvestmentPlan.Option(lowest, Fit.INFEASIBLE, true, true, null, null));
			return options;
		}
		boolean settled = false;
		for (InvestmentTier tier : InvestmentTier.values()) {
			if (tier.ordinal() < lowest.ordinal() || tier.getMaxRate() == null || options.size() == MAX_OPTIONS) {
				continue;
			}
			double max = tier.getMaxRate().doubleValue();
			Fit fit = rate <= tier.getTypicalRate().doubleValue() + EPSILON ? Fit.COMFORTABLE : Fit.STRETCH;
			boolean horizonOk = months >= tier.getMinYears() * MONTHS_PER_YEAR;
			boolean lowCaseOk = !floating || lowRate != null && lowRate.doubleValue() <= max + EPSILON;
			BigDecimal typicalEnd = decimal(futureValue(principal, monthly, tier.getTypicalRate().doubleValue(), months));
			//一般行情可達成時，浮點誤差不應讓期滿金額看起來差幾元沒達標
			if (fit == Fit.COMFORTABLE && typicalEnd.compareTo(target) < 0) {
				typicalEnd = target;
			}
			options.add(new InvestmentPlan.Option(tier, fit, horizonOk, lowCaseOk, typicalEnd, decimal(futureValue(principal, monthly, max, months))));
			//期望獲利為 0 只要保本，不必再列其他類別
			if (settled || profit.signum() == 0) {
				break;
			}
			settled = fit == Fit.COMFORTABLE && horizonOk;
		}
		return options;
	}

	/** 需要的報酬率介於兩類的一般行情報酬率之間時，算出高風險那一類至少要占多少比例 */
	private static InvestmentPlan.Allocation allocation(double principal, double monthly, int months, double rate) {
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
			BigDecimal endAmount = decimal(futureValue(principal, monthly, blended.doubleValue(), months));
			return new InvestmentPlan.Allocation(mix[0], mix[1], highPercent, blended, endAmount, horizonOk);
		}
		return null;
	}

	/** 每滿一年一列，最後不足一年的月份另列；最後一列剛好是目標金額 */
	private static List<InvestmentPlan.Row> rows(BigDecimal principal, BigDecimal monthly, BigDecimal target, int months, double rate) {
		List<InvestmentPlan.Row> rows = new ArrayList<>();
		for (int m = MONTHS_PER_YEAR; m <= months; m += MONTHS_PER_YEAR) {
			BigDecimal balance = m == months ? target : decimal(futureValue(principal.doubleValue(), monthly.doubleValue(), rate, m));
			rows.add(new InvestmentPlan.Row(m, invested(principal, monthly, m), balance));
		}
		if (months % MONTHS_PER_YEAR != 0) {
			rows.add(new InvestmentPlan.Row(months, invested(principal, monthly, months), target));
		}
		return rows;
	}

	/** 不可行時，以比較基準的一般行情報酬率估算四種替代方案 */
	private static void benchmark(InvestmentPlan plan, double principal, double monthly, int months, double profit) {
		double annual = BENCHMARK.getTypicalRate().doubleValue();
		double rate = Math.pow(1 + annual, 1.0 / MONTHS_PER_YEAR) - 1;
		double growth = Math.pow(1 + rate, months);
		//每 1 元本金、每月每 1 元投資在期滿時的獲利
		double principalGain = futureValue(1, 0, annual, months) - 1;
		double monthlyGain = (1 + rate) * (growth - 1) / rate - months;
		double monthlyProfit = monthly * monthlyGain;
		double principalProfit = principal * principalGain;

		plan.setBenchmark(BENCHMARK);
		plan.setBenchmarkMonths(monthsToProfit(principal, monthly, annual, profit));
		plan.setBenchmarkPrincipal(decimal(Math.max(0, (profit - monthlyProfit) / principalGain)).setScale(0, RoundingMode.HALF_UP));
		plan.setBenchmarkMonthly(decimal(Math.max(0, (profit - principalProfit) / monthlyGain)).setScale(0, RoundingMode.HALF_UP));
		plan.setBenchmarkProfit(decimal(principalProfit + monthlyProfit).setScale(0, RoundingMode.HALF_UP));
	}

	/** 每月照常投入、以年化報酬率 annual 達到期望獲利需要的月數，超過 1000 年時回傳 -1 */
	private static int monthsToProfit(double principal, double monthly, double annual, double profit) {
		if (profit <= 0) {
			return 0;
		}
		int high = 1;
		while (gain(principal, monthly, annual, high) < profit) {
			if (high >= MAX_SEARCH_MONTHS) {
				return -1;
			}
			high = Math.min(high * 2, MAX_SEARCH_MONTHS);
		}
		int low = 0;
		while (high - low > 1) {
			int middle = (low + high) / 2;
			if (gain(principal, monthly, annual, middle) < profit) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return high;
	}

	private static double gain(double principal, double monthly, double annual, int months) {
		return futureValue(principal, monthly, annual, months) - principal - monthly * months;
	}

	private static double round(double rate) {
		return rate < RATE_PRECISION ? Math.round(rate * RATE_PRECISION) / RATE_PRECISION : rate;
	}

	private static BigDecimal decimal(double value) {
		return BigDecimal.valueOf(value);
	}
}
