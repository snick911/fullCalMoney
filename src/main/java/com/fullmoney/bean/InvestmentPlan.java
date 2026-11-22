package com.fullmoney.bean;

import java.math.BigDecimal;
import java.util.List;

/**
 * 投資計畫試算結果
 *
 * <p>投入方式：本金在第 1 個月初投入一次，之後每月月初投入「每月固定投資金額 + 每月浮動投資金額」。
 * 浮動金額為區間，預期情況取中間值，另外計算只投入下限與投入上限的情況。</p>
 */
public class InvestmentPlan {

	/** 方案能否達成目標 */
	public enum Fit {
		/** 一般行情的報酬率就能達成 */
		COMFORTABLE,
		/** 需要行情較好（報酬率接近上限）才能達成 */
		STRETCH,
		/** 一般投資無法達成，只剩高風險投機或彩券 */
		INFEASIBLE
	}

	/** 逐年試算的一列：第 months 個月底的累積投入與金額 */
	public static final class Row {

		private final int months;
		private final BigDecimal invested;
		private final BigDecimal balance;

		public Row(int months, BigDecimal invested, BigDecimal balance) {
			this.months = months;
			this.invested = invested;
			this.balance = balance;
		}

		public int getMonths() {
			return months;
		}

		public BigDecimal getInvested() {
			return invested;
		}

		public BigDecimal getBalance() {
			return balance;
		}
	}

	/** 一個可考慮的方案（一類標的） */
	public static final class Option {

		private final InvestmentTier tier;
		private final Fit fit;
		private final boolean horizonOk;
		private final boolean lowCaseOk;
		private final BigDecimal typicalEnd;
		private final BigDecimal maxEnd;
		private final BigDecimal probability;

		public Option(InvestmentTier tier, Fit fit, boolean horizonOk, boolean lowCaseOk, BigDecimal typicalEnd, BigDecimal maxEnd,
				BigDecimal probability) {
			this.tier = tier;
			this.fit = fit;
			this.horizonOk = horizonOk;
			this.lowCaseOk = lowCaseOk;
			this.typicalEnd = typicalEnd;
			this.maxEnd = maxEnd;
			this.probability = probability;
		}

		/** 蒙地卡羅模擬的達成機率 ex. 0.62，沒有可預期報酬率時為 null */
		public BigDecimal getProbability() {
			return probability;
		}

		public InvestmentTier getTier() {
			return tier;
		}

		public Fit getFit() {
			return fit;
		}

		/** 投資期間是否達到此類標的建議的年數 */
		public boolean isHorizonOk() {
			return horizonOk;
		}

		/** 每月浮動投資只投入下限時，行情好是否仍能達成；沒有浮動區間時為 true */
		public boolean isLowCaseOk() {
			return lowCaseOk;
		}

		/** 以一般行情報酬率計算的期滿金額，沒有可預期報酬率時為 null */
		public BigDecimal getTypicalEnd() {
			return typicalEnd;
		}

		/** 以行情好的報酬率上限計算的期滿金額，沒有可預期報酬率時為 null */
		public BigDecimal getMaxEnd() {
			return maxEnd;
		}
	}

	/**
	 * 資產配置的逐年試算一列：第 months 個月底，定存／公債／公司債／股票各自的累積投入與金額
	 *
	 * <p>本金與每月投資依配置比例分到四種資產，之後各自以一般行情報酬率逐月複利，不做再平衡。</p>
	 */
	public static final class AllocationRow {

		private final int months;
		private final BigDecimal[] invested;
		private final BigDecimal[] values;

		public AllocationRow(int months, BigDecimal[] invested, BigDecimal[] values) {
			this.months = months;
			this.invested = invested;
			this.values = values;
		}

		public int getMonths() {
			return months;
		}

		/** 定存、公債、公司債、股票的累積投入 */
		public BigDecimal[] getInvested() {
			return invested;
		}

		/** 定存、公債、公司債、股票的金額 */
		public BigDecimal[] getValues() {
			return values;
		}
	}

	/** 一種資產配置類型的最佳配置（定存／公債／公司債／股票＋股票等級）：模擬達成機率最高者 */
	public static final class Portfolio {

		private final AllocationProfile profile;
		private final int[] weights;
		private final InvestmentTier stockLevel;
		private final BigDecimal typicalRate;
		private final BigDecimal volatility;
		private final BigDecimal probability;
		private final BigDecimal pessimistic;
		private final BigDecimal median;
		private final BigDecimal optimistic;
		private final boolean horizonOk;
		private final int candidates;
		private final int reachable;
		private final int simulated;
		private final List<AllocationRow> rows;

		public Portfolio(Candidate best, BigDecimal typicalRate, BigDecimal volatility, BigDecimal[] outcomes, boolean horizonOk, int candidates,
				int reachable, int simulated, List<AllocationRow> rows) {
			this.profile = best.getProfile();
			this.weights = best.getWeights();
			this.stockLevel = best.getStockLevel();
			this.probability = BigDecimal.valueOf(best.getProbability());
			this.simulated = simulated;
			this.rows = rows;
			this.typicalRate = typicalRate;
			this.volatility = volatility;
			this.pessimistic = outcomes[0];
			this.median = outcomes[1];
			this.optimistic = outcomes[2];
			this.horizonOk = horizonOk;
			this.candidates = candidates;
			this.reachable = reachable;
		}

		public AllocationProfile getProfile() {
			return profile;
		}

		/** 預估年波動（標準差）ex. 0.075 */
		public BigDecimal getVolatility() {
			return volatility;
		}

		/** 模擬的達成機率 ex. 0.62 */
		public BigDecimal getProbability() {
			return probability;
		}

		/** 期滿金額的第 10 百分位（悲觀） */
		public BigDecimal getPessimistic() {
			return pessimistic;
		}

		/** 期滿金額的中位數 */
		public BigDecimal getMedian() {
			return median;
		}

		/** 期滿金額的第 90 百分位（樂觀） */
		public BigDecimal getOptimistic() {
			return optimistic;
		}

		/** 此類型共評估幾種配置 */
		public int getCandidates() {
			return candidates;
		}

		/** 其中約幾種（近似公式估計）達成機率超過一半 */
		public int getReachable() {
			return reachable;
		}

		/** 篩選後實際以蒙地卡羅模擬的配置數 */
		public int getSimulated() {
			return simulated;
		}

		/** 逐年試算：各資產的累積投入與金額（一般行情） */
		public List<AllocationRow> getRows() {
			return rows;
		}

		/** 定存、公債、公司債、股票的比例（%） */
		public int[] getWeights() {
			return weights;
		}

		/** 股票部位的等級，股票比例為 0 時為 null */
		public InvestmentTier getStockLevel() {
			return stockLevel;
		}

		/** 一般行情（中位數）的加權年報酬率 */
		public BigDecimal getTypicalRate() {
			return typicalRate;
		}

		/** 投資期間是否達到此類型建議的年數 */
		public boolean isHorizonOk() {
			return horizonOk;
		}
	}

	/** 一種試算過的配置（每 5% 一級搜尋中的一筆），用於挑選最佳配置 */
	public static final class Candidate {

		private final AllocationProfile profile;
		private final int[] weights;
		private final InvestmentTier stockLevel;
		private final double typicalRate;
		private final double volatility;
		private double probability;

		public Candidate(AllocationProfile profile, int[] weights, InvestmentTier stockLevel, double typicalRate, double volatility, double probability) {
			this.profile = profile;
			this.weights = weights;
			this.stockLevel = stockLevel;
			this.typicalRate = typicalRate;
			this.volatility = volatility;
			this.probability = probability;
		}

		public AllocationProfile getProfile() {
			return profile;
		}

		/** 定存、公債、公司債、股票的比例（%） */
		public int[] getWeights() {
			return weights;
		}

		/** 股票部位的等級，股票比例為 0 時為 null */
		public InvestmentTier getStockLevel() {
			return stockLevel;
		}

		public double getTypicalRate() {
			return typicalRate;
		}

		public double getVolatility() {
			return volatility;
		}

		/** 達成機率：篩選時為近似公式的估計，模擬後改為蒙地卡羅的結果 */
		public double getProbability() {
			return probability;
		}

		public void setProbability(double probability) {
			this.probability = probability;
		}
	}

	/** 每月浮動投資取某一端時需要的年化報酬率 */
	public static final class Case {

		private final BigDecimal monthly;
		private final BigDecimal invested;
		private final BigDecimal rate;
		private final InvestmentTier tier;

		public Case(BigDecimal monthly, BigDecimal invested, BigDecimal rate, InvestmentTier tier) {
			this.monthly = monthly;
			this.invested = invested;
			this.rate = rate;
			this.tier = tier;
		}

		/** 每月合計投資金額（固定 + 浮動） */
		public BigDecimal getMonthly() {
			return monthly;
		}

		/** 總投入金額 */
		public BigDecimal getInvested() {
			return invested;
		}

		/** 需要的年化報酬率，沒有投入任何資金時為 null */
		public BigDecimal getRate() {
			return rate;
		}

		/** 風險最低、有機會達成的類別，沒有投入任何資金時為 null */
		public InvestmentTier getTier() {
			return tier;
		}
	}

	private BigDecimal principal;
	private BigDecimal monthly;
	private BigDecimal invested;
	private BigDecimal profit;
	private BigDecimal target;
	private int months;
	private BigDecimal totalReturn;
	private BigDecimal multiple;
	private BigDecimal requiredRate;
	private InvestmentTier tier;
	private Case lowCase;
	private Case highCase;
	private List<Option> options;
	private List<Portfolio> portfolios;
	private AllocationProfile recommendedProfile;
	private List<Row> rows;
	private BigDecimal extendedRate;
	private InvestmentTier extendedTier;
	private InvestmentTier benchmark;
	private int benchmarkMonths;
	private BigDecimal benchmarkPrincipal;
	private BigDecimal benchmarkMonthly;
	private BigDecimal benchmarkProfit;

	public BigDecimal getPrincipal() {
		return principal;
	}

	public void setPrincipal(BigDecimal principal) {
		this.principal = principal;
	}

	/** 預期每月投資金額 = 每月固定 + 浮動區間的中間值 */
	public BigDecimal getMonthly() {
		return monthly;
	}

	public void setMonthly(BigDecimal monthly) {
		this.monthly = monthly;
	}

	/** 預期總投入金額 = 本金 + 每月投資 × 月數 */
	public BigDecimal getInvested() {
		return invested;
	}

	public void setInvested(BigDecimal invested) {
		this.invested = invested;
	}

	public BigDecimal getProfit() {
		return profit;
	}

	public void setProfit(BigDecimal profit) {
		this.profit = profit;
	}

	/** 目標金額 = 總投入 + 期望獲利 */
	public BigDecimal getTarget() {
		return target;
	}

	public void setTarget(BigDecimal target) {
		this.target = target;
	}

	/** 投資期間（月） */
	public int getMonths() {
		return months;
	}

	public void setMonths(int months) {
		this.months = months;
	}

	/** 總報酬率 = 期望獲利 / 總投入 ex. 0.25 */
	public BigDecimal getTotalReturn() {
		return totalReturn;
	}

	public void setTotalReturn(BigDecimal totalReturn) {
		this.totalReturn = totalReturn;
	}

	/** 目標金額是總投入的幾倍 */
	public BigDecimal getMultiple() {
		return multiple;
	}

	public void setMultiple(BigDecimal multiple) {
		this.multiple = multiple;
	}

	/** 預期情況需要的年化報酬率 ex. 0.0456 */
	public BigDecimal getRequiredRate() {
		return requiredRate;
	}

	public void setRequiredRate(BigDecimal requiredRate) {
		this.requiredRate = requiredRate;
	}

	/** 有機會達成的類別中風險最低的一類；一般投資無法達成時為高風險投機或彩券 */
	public InvestmentTier getTier() {
		return tier;
	}

	public void setTier(InvestmentTier tier) {
		this.tier = tier;
	}

	/** 每月浮動投資只投入下限的情況，沒有浮動區間時為 null */
	public Case getLowCase() {
		return lowCase;
	}

	public void setLowCase(Case lowCase) {
		this.lowCase = lowCase;
	}

	/** 每月浮動投資投入上限的情況，沒有浮動區間時為 null */
	public Case getHighCase() {
		return highCase;
	}

	public void setHighCase(Case highCase) {
		this.highCase = highCase;
	}

	/** 可考慮的方案，由低風險到高風險 */
	public List<Option> getOptions() {
		return options;
	}

	public void setOptions(List<Option> options) {
		this.options = options;
	}

	/** 保守型、穩健型、積極型各自的最佳配置 */
	public List<Portfolio> getPortfolios() {
		return portfolios;
	}

	public void setPortfolios(List<Portfolio> portfolios) {
		this.portfolios = portfolios;
	}

	/** 建議的配置類型，三種都達不到時為 null */
	public AllocationProfile getRecommendedProfile() {
		return recommendedProfile;
	}

	public void setRecommendedProfile(AllocationProfile recommendedProfile) {
		this.recommendedProfile = recommendedProfile;
	}

	public List<Row> getRows() {
		return rows;
	}

	public void setRows(List<Row> rows) {
		this.rows = rows;
	}

	/** 投資期間短於風險最低方案的建議年數時，延長到建議年數（每月照常投入）後需要的年化報酬率；期間足夠時為 null */
	public BigDecimal getExtendedRate() {
		return extendedRate;
	}

	public void setExtendedRate(BigDecimal extendedRate) {
		this.extendedRate = extendedRate;
	}

	public InvestmentTier getExtendedTier() {
		return extendedTier;
	}

	public void setExtendedTier(InvestmentTier extendedTier) {
		this.extendedTier = extendedTier;
	}

	/** 計畫不可行時用來估算替代方案的類別；可行時為 null */
	public InvestmentTier getBenchmark() {
		return benchmark;
	}

	public void setBenchmark(InvestmentTier benchmark) {
		this.benchmark = benchmark;
	}

	/** 以比較基準的一般行情報酬率、每月照常投入，達到期望獲利需要的月數；超過上限時為 -1 */
	public int getBenchmarkMonths() {
		return benchmarkMonths;
	}

	public void setBenchmarkMonths(int benchmarkMonths) {
		this.benchmarkMonths = benchmarkMonths;
	}

	/** 以比較基準的一般行情報酬率，每月投資不變、同期間達到期望獲利需要的本金 */
	public BigDecimal getBenchmarkPrincipal() {
		return benchmarkPrincipal;
	}

	public void setBenchmarkPrincipal(BigDecimal benchmarkPrincipal) {
		this.benchmarkPrincipal = benchmarkPrincipal;
	}

	/** 以比較基準的一般行情報酬率，本金不變、同期間達到期望獲利需要的每月投資金額 */
	public BigDecimal getBenchmarkMonthly() {
		return benchmarkMonthly;
	}

	public void setBenchmarkMonthly(BigDecimal benchmarkMonthly) {
		this.benchmarkMonthly = benchmarkMonthly;
	}

	/** 以比較基準的一般行情報酬率，同本金、同每月投資、同期間可期待的獲利 */
	public BigDecimal getBenchmarkProfit() {
		return benchmarkProfit;
	}

	public void setBenchmarkProfit(BigDecimal benchmarkProfit) {
		this.benchmarkProfit = benchmarkProfit;
	}

	/** 高風險投機與彩券都視為不可行 */
	public boolean isFeasible() {
		return tier.getMaxRate() != null;
	}
}
