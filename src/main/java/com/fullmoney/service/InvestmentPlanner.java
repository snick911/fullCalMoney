package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.fullmoney.bean.AllocationProfile;
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
 * <li>資產配置：定存／債券／股票以每 5% 一級列出所有組合，依股票比例分成保守型、穩健型、積極型（定存比例有上限，見 AllocationProfile）。
 * 每種組合以 {@link MarketSimulator} 模擬 500 種市場走勢，每型在達成機率與最高者相差不到 2 個百分點的配置中取波動最低者
 * （機率都是 0 時取報酬最高者）。
 * 報酬率依比例加權；預估波動假設定存 0%、債券 5%、股票 18%，股債相關係數 0.2。
 * 三型中依序建議：期間足夠且機率 75% 以上、期間足夠且機率 50% 以上（以上兩級取最保守的一型）、期間足夠中機率最高、不看期間機率最高。</li>
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

	/** 資產配置的三種資產，依序為定存、債券、股票 */
	private static final InvestmentTier[] ASSETS = { InvestmentTier.DEPOSIT, InvestmentTier.BOND, InvestmentTier.STOCK };

	/** 資產配置比例的間隔（%） */
	private static final int ALLOCATION_STEP = 5;

	/** 預估年波動的股債相關係數；各資產的波動見 InvestmentTier（定存 0、債券 5%、股票 18%） */
	private static final double STOCK_BOND_CORRELATION = 0.2;
	private static final int VOLATILITY_SCALE = 4;

	/** 建議資產配置時「把握高」與「有機會」的達成機率門檻 */
	private static final double CONFIDENT = 0.75;
	private static final double LIKELY = 0.5;

	/** 期滿金額分布：悲觀（第 10 百分位）、中位數、樂觀（第 90 百分位） */
	private static final double[] OUTCOME_PERCENTILES = { 0.1, 0.5, 0.9 };
	private static final int PROBABILITY_SCALE = 4;

	/** 達成機率相差不到 2 個百分點視為相同（500 次模擬的誤差約 ±2 個百分點） */
	private static final double PROBABILITY_TOLERANCE = 0.02;

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
		MarketSimulator simulator = new MarketSimulator(months);
		BigDecimal lowRate = plan.getLowCase() == null ? null : plan.getLowCase().getRate();
		plan.setOptions(options(tier, p, m, months, rate, profit, plan.getLowCase() != null, lowRate, target, simulator));
		List<InvestmentPlan.Portfolio> portfolios = new ArrayList<>();
		for (AllocationProfile profile : AllocationProfile.values()) {
			portfolios.add(bestPortfolio(profile, p, m, months, target.doubleValue(), simulator));
		}
		plan.setPortfolios(portfolios);
		plan.setRecommendedProfile(recommend(portfolios));
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
			BigDecimal profit, boolean floating, BigDecimal lowRate, BigDecimal target, MarketSimulator simulator) {
		List<InvestmentPlan.Option> options = new ArrayList<>();
		if (lowest.getMaxRate() == null) {
			options.add(new InvestmentPlan.Option(lowest, Fit.INFEASIBLE, true, true, null, null, null));
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
			double[] finals = simulator.simulate(principal, monthly, tier.getTypicalRate().doubleValue(), tier.getVolatility().doubleValue());
			BigDecimal probability = probability(MarketSimulator.probability(finals, target.doubleValue()));
			options.add(new InvestmentPlan.Option(tier, fit, horizonOk, lowCaseOk, typicalEnd, decimal(futureValue(principal, monthly, max, months)),
					probability));
			//期望獲利為 0 只要保本，不必再列其他類別
			if (settled || profit.signum() == 0) {
				break;
			}
			settled = fit == Fit.COMFORTABLE && horizonOk;
		}
		return options;
	}

	/**
	 * 從此類型每 5% 一級的所有配置中挑最佳：達成機率與最高者相差不到 2 個百分點的配置中，取波動最低者（同波動取報酬高）；
	 * 500 次模擬的誤差約 ±2 個百分點，不值得為了更小的差距多承擔風險。機率都是 0 時取一般行情報酬最高、最接近目標者
	 */
	private static InvestmentPlan.Portfolio bestPortfolio(AllocationProfile profile, double principal, double monthly, int months, double target,
			MarketSimulator simulator) {
		List<int[]> weightsList = new ArrayList<>();
		List<double[]> finalsList = new ArrayList<>();
		List<InvestmentPlan.Candidate> evaluated = new ArrayList<>();
		double topProbability = 0;
		int reachable = 0;
		for (int stock = 0; stock <= 100; stock += ALLOCATION_STEP) {
			if (AllocationProfile.classify(stock) != profile) {
				continue;
			}
			for (int bond = 0; bond <= 100 - stock; bond += ALLOCATION_STEP) {
				int[] weights = { 100 - stock - bond, bond, stock };
				if (weights[0] > profile.getMaxDeposit()) {
					continue;
				}
				double typical = weighted(weights).doubleValue();
				double volatility = volatility(bond, stock);
				double[] finals = simulator.simulate(principal, monthly, typical, volatility);
				double probability = MarketSimulator.probability(finals, target);
				weightsList.add(weights);
				finalsList.add(finals);
				evaluated.add(new InvestmentPlan.Candidate(profile, weights, typical, volatility, probability, MarketSimulator.percentile(finals, 0.5)));
				topProbability = Math.max(topProbability, probability);
				if (probability >= LIKELY) {
					reachable++;
				}
			}
		}
		int chosen = -1;
		for (int i = 0; i < evaluated.size(); i++) {
			InvestmentPlan.Candidate candidate = evaluated.get(i);
			if (topProbability > 0 && candidate.getProbability() < topProbability - PROBABILITY_TOLERANCE - EPSILON) {
				continue;
			}
			if (chosen < 0 || better(candidate, evaluated.get(chosen), topProbability > 0)) {
				chosen = i;
			}
		}
		InvestmentPlan.Candidate best = evaluated.get(chosen);
		double[] finals = finalsList.get(chosen);
		BigDecimal[] outcomes = new BigDecimal[OUTCOME_PERCENTILES.length];
		for (int i = 0; i < outcomes.length; i++) {
			outcomes[i] = decimal(MarketSimulator.percentile(finals, OUTCOME_PERCENTILES[i]));
		}
		boolean horizonOk = months >= profile.getMinYears() * MONTHS_PER_YEAR;
		int[] weights = weightsList.get(chosen);
		return new InvestmentPlan.Portfolio(profile, weights, weighted(weights), decimal(best.getVolatility()).setScale(VOLATILITY_SCALE, RoundingMode.HALF_UP),
				probability(best.getProbability()), outcomes, horizonOk, evaluated.size(), reachable, allocationRows(weights, principal, monthly, months));
	}

	/**
	 * 逐年試算：本金與每月投資依配置比例分到定存、債券、股票，之後各自以一般行情報酬率逐月複利（不做再平衡）；
	 * 每滿一年一列，最後不足一年的月份另列
	 */
	private static List<InvestmentPlan.AllocationRow> allocationRows(int[] weights, double principal, double monthly, int months) {
		List<InvestmentPlan.AllocationRow> rows = new ArrayList<>();
		for (int m = MONTHS_PER_YEAR; m <= months; m += MONTHS_PER_YEAR) {
			rows.add(allocationRow(weights, principal, monthly, m));
		}
		if (months % MONTHS_PER_YEAR != 0) {
			rows.add(allocationRow(weights, principal, monthly, months));
		}
		return rows;
	}

	private static InvestmentPlan.AllocationRow allocationRow(int[] weights, double principal, double monthly, int months) {
		BigDecimal[] invested = new BigDecimal[ASSETS.length];
		BigDecimal[] values = new BigDecimal[ASSETS.length];
		for (int i = 0; i < ASSETS.length; i++) {
			double share = weights[i] / 100.0;
			invested[i] = decimal((principal + monthly * months) * share);
			values[i] = decimal(futureValue(principal * share, monthly * share, ASSETS[i].getTypicalRate().doubleValue(), months));
		}
		return new InvestmentPlan.AllocationRow(months, invested, values);
	}

	/** 有機會達成時比波動低（再比報酬高）；機率都是 0 時比報酬高 */
	private static boolean better(InvestmentPlan.Candidate candidate, InvestmentPlan.Candidate current, boolean reachable) {
		if (reachable && Math.abs(candidate.getVolatility() - current.getVolatility()) > EPSILON) {
			return candidate.getVolatility() < current.getVolatility();
		}
		if (Math.abs(candidate.getTypicalRate() - current.getTypicalRate()) > EPSILON) {
			return candidate.getTypicalRate() > current.getTypicalRate();
		}
		return candidate.getVolatility() < current.getVolatility() - EPSILON;
	}

	/** 依比例加權的一般行情年報酬率 */
	private static BigDecimal weighted(int[] weights) {
		BigDecimal total = BigDecimal.ZERO;
		for (int i = 0; i < ASSETS.length; i++) {
			total = total.add(ASSETS[i].getTypicalRate().multiply(BigDecimal.valueOf(weights[i])));
		}
		return total.movePointLeft(2);
	}

	/** 預估年波動 = √((債券比例 × 債券波動)² + (股票比例 × 股票波動)² + 2 × 相關係數 × 兩者的乘積)，定存視為沒有波動 */
	private static double volatility(int bondPercent, int stockPercent) {
		double bond = bondPercent / 100.0 * InvestmentTier.BOND.getVolatility().doubleValue();
		double stock = stockPercent / 100.0 * InvestmentTier.STOCK.getVolatility().doubleValue();
		return Math.sqrt(bond * bond + stock * stock + 2 * STOCK_BOND_CORRELATION * bond * stock);
	}

	/**
	 * 建議的配置類型，依序找：期間足夠且機率 75% 以上、期間足夠且機率 50% 以上（以上取最保守的一型）、
	 * 期間足夠中機率最高、不看期間機率最高；三型機率都是 0 時為 null
	 */
	private static AllocationProfile recommend(List<InvestmentPlan.Portfolio> portfolios) {
		for (double threshold : new double[] { CONFIDENT, LIKELY }) {
			for (InvestmentPlan.Portfolio portfolio : portfolios) {
				if (portfolio.isHorizonOk() && portfolio.getProbability().doubleValue() >= threshold) {
					return portfolio.getProfile();
				}
			}
		}
		for (boolean needHorizon : new boolean[] { true, false }) {
			InvestmentPlan.Portfolio chosen = null;
			for (InvestmentPlan.Portfolio portfolio : portfolios) {
				if (needHorizon && !portfolio.isHorizonOk() || portfolio.getProbability().signum() == 0) {
					continue;
				}
				if (chosen == null || portfolio.getProbability().compareTo(chosen.getProbability()) > 0) {
					chosen = portfolio;
				}
			}
			if (chosen != null) {
				return chosen.getProfile();
			}
		}
		return null;
	}

	private static BigDecimal probability(double probability) {
		return decimal(probability).setScale(PROBABILITY_SCALE, RoundingMode.HALF_UP);
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
