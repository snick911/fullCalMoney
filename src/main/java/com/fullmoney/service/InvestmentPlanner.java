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
 * <li>列到第一個「一般行情就能達成、且投資期間足夠」的類別，再多列一類較高報酬的選擇，最多 4 個方案。</li>
 * <li>資產配置：定存／公債／公司債／股票以每 5% 一級列出所有組合，股票部位再從該類型可用的股票等級中選一種，
 * 依股票比例分成保守型、穩健型、積極型（定存、公司債有上限，見 AllocationProfile）。
 * 先以近似公式（對數常態）估計每種組合的達成機率篩選，再把每型較好的組合以 {@link MarketSimulator} 模擬 500 種市場走勢，
 * 在達成機率與最高者相差不到 2 個百分點的配置中取波動最低者（機率都是 0 時取報酬最高者）。
 * 報酬率依比例加權；預估波動見 InvestmentTier，相關係數：公債與公司債 0.5、公債與股票 0.1、公司債與股票 0.5。
 * 三型中依序建議：期間足夠且機率 75% 以上、期間足夠且機率 50% 以上（以上兩級取最保守的一型）、期間足夠中機率最高、不看期間機率最高。</li>
 * <li>連積極成長的上限都達不到時為高風險投機；年化與總報酬都超過 100% 時只能靠運氣（彩券）。</li>
 * <li>每月浮動投資以區間中間值為預期情況，另外計算只投入下限、投入上限時需要的報酬率。</li>
 * </ol>
 */
public class InvestmentPlanner {

	private static final int MONTHS_PER_YEAR = 12;

	/** 最多列出幾個方案 */
	private static final int MAX_OPTIONS = 4;

	/** 需要的年化報酬率與總報酬率都超過 100% 時（ex. 1 年內要翻倍以上），只能靠運氣 */
	private static final double LOTTERY_RATE = 1.0;

	/** 計畫不可行時，以股票／基金的一般行情報酬率估算延長期間、增加本金或每月投資、降低目標的替代方案 */
	private static final InvestmentTier BENCHMARK = InvestmentTier.STOCK_CORE;

	/** 估算延長期間時最多找到 1000 年 */
	private static final int MAX_SEARCH_MONTHS = 12_000;

	/** 資產配置的資產數：定存、公債、公司債、股票（股票再選等級） */
	private static final int ASSET_COUNT = 4;
	private static final int DEPOSIT = 0;
	private static final int GOV_BOND = 1;
	private static final int CORPORATE_BOND = 2;
	private static final int STOCK = 3;

	/** 資產配置比例的間隔（%） */
	private static final int ALLOCATION_STEP = 5;

	/** 相關係數：公債與公司債、公債與股票、公司債與股票；定存視為沒有波動 */
	private static final double GOV_CORPORATE_CORRELATION = 0.5;
	private static final double GOV_STOCK_CORRELATION = 0.1;
	private static final double CORPORATE_STOCK_CORRELATION = 0.5;
	private static final int VOLATILITY_SCALE = 4;

	/** 近似公式篩選後，每型最多送幾種配置做蒙地卡羅模擬：機率最高的 15 種，加上機率與最高者相差 6 個百分點內波動最低的 20 種 */
	private static final int SHORTLIST_TOP = 15;
	private static final int SHORTLIST_CALM = 20;
	private static final double SHORTLIST_MARGIN = 0.06;

	/** 近似機率都低於此值時視為達不到，改挑一般行情報酬最高的配置模擬 */
	private static final double UNREACHABLE = 1e-4;

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
			//風險較高的類別報酬率不一定較高（ex. 定存型股票低於公司債），行情好也達不到的略過
			if (tier.ordinal() < lowest.ordinal() || tier.getMaxRate() == null || options.size() == MAX_OPTIONS
					|| rate > tier.getMaxRate().doubleValue() + EPSILON) {
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
	 * 從此類型每 5% 一級的所有配置（股票部位再選 5 個等級之一）中挑最佳：
	 * <ol>
	 * <li>以近似公式估計每種配置的達成機率（{@link #approximateProbability}）。</li>
	 * <li>機率最高的 15 種，加上與最高者相差 6 個百分點內波動最低的 20 種，以蒙地卡羅模擬 500 種走勢。</li>
	 * <li>模擬機率與最高者相差不到 2 個百分點的配置中取波動最低者（同波動取報酬高）；500 次模擬的誤差約 ±2 個百分點，
	 * 不值得為了更小的差距多承擔風險。機率都是 0 時取一般行情報酬最高、最接近目標者。</li>
	 * </ol>
	 */
	private static InvestmentPlan.Portfolio bestPortfolio(AllocationProfile profile, double principal, double monthly, int months, double target,
			MarketSimulator simulator) {
		List<InvestmentPlan.Candidate> all = new ArrayList<>();
		int reachable = 0;
		for (int stock = 0; stock <= 100; stock += ALLOCATION_STEP) {
			if (AllocationProfile.classify(stock) != profile) {
				continue;
			}
			int maxCorporate = Math.min(profile.getMaxCorporate(), 100 - stock);
			for (int corporate = 0; corporate <= maxCorporate; corporate += ALLOCATION_STEP) {
				int maxDeposit = Math.min(profile.getMaxDeposit(), 100 - stock - corporate);
				for (int deposit = 0; deposit <= maxDeposit; deposit += ALLOCATION_STEP) {
					int[] weights = { deposit, 100 - stock - corporate - deposit, corporate, stock };
					//沒有股票時不必區分股票等級
					InvestmentTier[] levels = stock == 0 ? new InvestmentTier[] { null } : profile.getStockLevels();
					for (InvestmentTier level : levels) {
						double typical = weighted(weights, level).doubleValue();
						double volatility = volatility(weights, level);
						double probability = approximateProbability(principal, monthly, months, typical, volatility, target);
						all.add(new InvestmentPlan.Candidate(profile, weights, level, typical, volatility, probability));
						if (probability >= LIKELY) {
							reachable++;
						}
					}
				}
			}
		}

		List<InvestmentPlan.Candidate> shortlist = shortlist(all);
		double top = 0;
		for (InvestmentPlan.Candidate candidate : shortlist) {
			candidate.setProbability(MarketSimulator.probability(simulator.simulate(principal, monthly, candidate.getTypicalRate(), candidate.getVolatility()),
					target));
			top = Math.max(top, candidate.getProbability());
		}
		//機率很低時容許的差距也縮小（最多為最高機率的 1/4），避免把機率 0 的配置當成差不多
		double tolerance = Math.min(PROBABILITY_TOLERANCE, top / 4);
		InvestmentPlan.Candidate best = null;
		for (InvestmentPlan.Candidate candidate : shortlist) {
			if (top > 0 && candidate.getProbability() < top - tolerance - EPSILON) {
				continue;
			}
			if (best == null || better(candidate, best, top > 0)) {
				best = candidate;
			}
		}

		double[] finals = simulator.simulate(principal, monthly, best.getTypicalRate(), best.getVolatility());
		BigDecimal[] outcomes = new BigDecimal[OUTCOME_PERCENTILES.length];
		for (int i = 0; i < outcomes.length; i++) {
			outcomes[i] = decimal(MarketSimulator.percentile(finals, OUTCOME_PERCENTILES[i]));
		}
		boolean horizonOk = months >= profile.getMinYears() * MONTHS_PER_YEAR;
		return new InvestmentPlan.Portfolio(best, weighted(best.getWeights(), best.getStockLevel()),
				decimal(best.getVolatility()).setScale(VOLATILITY_SCALE, RoundingMode.HALF_UP), outcomes, horizonOk, all.size(), reachable, shortlist.size(),
				allocationRows(best, principal, monthly, months));
	}

	/** 近似機率最高的幾種，加上機率接近最高者中波動最低的幾種；都幾乎不可能時取一般行情報酬最高的幾種 */
	private static List<InvestmentPlan.Candidate> shortlist(List<InvestmentPlan.Candidate> all) {
		List<InvestmentPlan.Candidate> sorted = new ArrayList<>(all);
		sorted.sort((a, b) -> a.getProbability() != b.getProbability() ? Double.compare(b.getProbability(), a.getProbability())
				: Double.compare(a.getVolatility(), b.getVolatility()));
		double top = sorted.get(0).getProbability();
		List<InvestmentPlan.Candidate> list = new ArrayList<>();
		if (top < UNREACHABLE) {
			sorted.sort((a, b) -> a.getTypicalRate() != b.getTypicalRate() ? Double.compare(b.getTypicalRate(), a.getTypicalRate())
					: Double.compare(a.getVolatility(), b.getVolatility()));
			list.addAll(sorted.subList(0, Math.min(SHORTLIST_TOP, sorted.size())));
			return list;
		}
		list.addAll(sorted.subList(0, Math.min(SHORTLIST_TOP, sorted.size())));
		List<InvestmentPlan.Candidate> calm = new ArrayList<>();
		for (InvestmentPlan.Candidate candidate : sorted) {
			if (candidate.getProbability() >= top - SHORTLIST_MARGIN) {
				calm.add(candidate);
			}
		}
		calm.sort((a, b) -> a.getVolatility() != b.getVolatility() ? Double.compare(a.getVolatility(), b.getVolatility())
				: Double.compare(b.getTypicalRate(), a.getTypicalRate()));
		for (int i = 0; i < Math.min(SHORTLIST_CALM, calm.size()); i++) {
			if (!list.contains(calm.get(i))) {
				list.add(calm.get(i));
			}
		}
		return list;
	}

	/**
	 * 近似達成機率：把期滿金額視為對數常態分布，中位數為一般行情的逐月複利金額，
	 * 變異以「各筆投入依期滿價值加權的平均剩餘年數」計算；沒有每月投資時與模擬的模型完全相同
	 */
	static double approximateProbability(double principal, double monthly, int months, double rate, double volatility, double target) {
		double median = futureValue(principal, monthly, rate, months);
		if (volatility < EPSILON) {
			return median >= target * (1 - EPSILON) ? 1 : 0;
		}
		double z = Math.log(median / target) / (volatility * Math.sqrt(effectiveYears(principal, monthly, rate, months)));
		return normalCdf(z);
	}

	/** 各筆投入（本金與每月投資）依期滿價值加權的平均剩餘年數 */
	private static double effectiveYears(double principal, double monthly, double rate, int months) {
		if (monthly == 0) {
			return months / (double) MONTHS_PER_YEAR;
		}
		double monthGrowth = Math.pow(1 + rate, 1.0 / MONTHS_PER_YEAR);
		double growth = 1;
		double weightSum = 0;
		double weightedYears = 0;
		for (int k = months - 1; k >= 0; k--) {
			growth *= monthGrowth;
			double amount = monthly + (k == 0 ? principal : 0);
			weightSum += amount * growth;
			weightedYears += amount * growth * (months - k) / MONTHS_PER_YEAR;
		}
		return weightedYears / weightSum;
	}

	/** 標準常態分布的累積機率（Abramowitz & Stegun 26.2.17，誤差小於 7.5e-8） */
	static double normalCdf(double z) {
		double t = 1 / (1 + 0.2316419 * Math.abs(z));
		double density = 0.3989422804014327 * Math.exp(-z * z / 2);
		double tail = density * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
		return z >= 0 ? 1 - tail : tail;
	}

	/**
	 * 逐年試算：本金與每月投資依配置比例分到定存、公債、公司債、股票，之後各自以一般行情報酬率逐月複利（不做再平衡）；
	 * 每滿一年一列，最後不足一年的月份另列
	 */
	private static List<InvestmentPlan.AllocationRow> allocationRows(InvestmentPlan.Candidate best, double principal, double monthly, int months) {
		double[] rates = rates(best.getStockLevel());
		List<InvestmentPlan.AllocationRow> rows = new ArrayList<>();
		for (int m = MONTHS_PER_YEAR; m <= months; m += MONTHS_PER_YEAR) {
			rows.add(allocationRow(best.getWeights(), rates, principal, monthly, m));
		}
		if (months % MONTHS_PER_YEAR != 0) {
			rows.add(allocationRow(best.getWeights(), rates, principal, monthly, months));
		}
		return rows;
	}

	private static InvestmentPlan.AllocationRow allocationRow(int[] weights, double[] rates, double principal, double monthly, int months) {
		BigDecimal[] invested = new BigDecimal[ASSET_COUNT];
		BigDecimal[] values = new BigDecimal[ASSET_COUNT];
		for (int i = 0; i < ASSET_COUNT; i++) {
			double share = weights[i] / 100.0;
			invested[i] = decimal((principal + monthly * months) * share);
			values[i] = decimal(futureValue(principal * share, monthly * share, rates[i], months));
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

	/** 四種資產的一般行情報酬率：定存、公債、公司債、股票（依等級，沒有股票時為 0） */
	private static double[] rates(InvestmentTier stockLevel) {
		return new double[] { InvestmentTier.DEPOSIT.getTypicalRate().doubleValue(), InvestmentTier.GOV_BOND.getTypicalRate().doubleValue(),
				InvestmentTier.CORPORATE_BOND.getTypicalRate().doubleValue(), stockLevel == null ? 0 : stockLevel.getTypicalRate().doubleValue() };
	}

	/** 依比例加權的一般行情年報酬率 */
	private static BigDecimal weighted(int[] weights, InvestmentTier stockLevel) {
		BigDecimal total = InvestmentTier.DEPOSIT.getTypicalRate().multiply(BigDecimal.valueOf(weights[DEPOSIT]))
				.add(InvestmentTier.GOV_BOND.getTypicalRate().multiply(BigDecimal.valueOf(weights[GOV_BOND])))
				.add(InvestmentTier.CORPORATE_BOND.getTypicalRate().multiply(BigDecimal.valueOf(weights[CORPORATE_BOND])));
		if (stockLevel != null) {
			total = total.add(stockLevel.getTypicalRate().multiply(BigDecimal.valueOf(weights[STOCK])));
		}
		return total.movePointLeft(2);
	}

	/** 預估年波動 = √(Σ 比例ᵢ × 比例ⱼ × 波動ᵢ × 波動ⱼ × 相關係數ᵢⱼ)，定存視為沒有波動 */
	private static double volatility(int[] weights, InvestmentTier stockLevel) {
		double gov = weights[GOV_BOND] / 100.0 * InvestmentTier.GOV_BOND.getVolatility().doubleValue();
		double corporate = weights[CORPORATE_BOND] / 100.0 * InvestmentTier.CORPORATE_BOND.getVolatility().doubleValue();
		double stock = stockLevel == null ? 0 : weights[STOCK] / 100.0 * stockLevel.getVolatility().doubleValue();
		return Math.sqrt(gov * gov + corporate * corporate + stock * stock + 2 * GOV_CORPORATE_CORRELATION * gov * corporate
				+ 2 * GOV_STOCK_CORRELATION * gov * stock + 2 * CORPORATE_STOCK_CORRELATION * corporate * stock);
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
