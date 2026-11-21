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
		/** 一般投資無法達成，只剩高風險投機或彩券；用於資產配置時表示行情好也達不到 */
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

		public Option(InvestmentTier tier, Fit fit, boolean horizonOk, boolean lowCaseOk, BigDecimal typicalEnd, BigDecimal maxEnd) {
			this.tier = tier;
			this.fit = fit;
			this.horizonOk = horizonOk;
			this.lowCaseOk = lowCaseOk;
			this.typicalEnd = typicalEnd;
			this.maxEnd = maxEnd;
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

	/** 一組資產配置（定存／債券／股票）的評估，報酬率依比例加權 */
	public static final class Portfolio {

		private final AllocationProfile profile;
		private final boolean custom;
		private final int deposit;
		private final int bond;
		private final int stock;
		private final BigDecimal typicalRate;
		private final BigDecimal maxRate;
		private final BigDecimal typicalEnd;
		private final Fit fit;
		private final boolean horizonOk;

		public Portfolio(AllocationProfile profile, boolean custom, int deposit, int bond, int stock, BigDecimal typicalRate, BigDecimal maxRate,
				BigDecimal typicalEnd, Fit fit, boolean horizonOk) {
			this.profile = profile;
			this.custom = custom;
			this.deposit = deposit;
			this.bond = bond;
			this.stock = stock;
			this.typicalRate = typicalRate;
			this.maxRate = maxRate;
			this.typicalEnd = typicalEnd;
			this.fit = fit;
			this.horizonOk = horizonOk;
		}

		/** 配置類型；自訂配置為依股票比例分類的結果 */
		public AllocationProfile getProfile() {
			return profile;
		}

		/** 是否為使用者自訂的配置 */
		public boolean isCustom() {
			return custom;
		}

		public int getDeposit() {
			return deposit;
		}

		public int getBond() {
			return bond;
		}

		public int getStock() {
			return stock;
		}

		/** 一般行情的加權年報酬率 */
		public BigDecimal getTypicalRate() {
			return typicalRate;
		}

		/** 行情好的加權年報酬率 */
		public BigDecimal getMaxRate() {
			return maxRate;
		}

		/** 以一般行情加權報酬率計算的期滿金額 */
		public BigDecimal getTypicalEnd() {
			return typicalEnd;
		}

		/** COMFORTABLE 一般行情可達成、STRETCH 需行情較好、INFEASIBLE 行情好也達不到 */
		public Fit getFit() {
			return fit;
		}

		/** 投資期間是否達到此類型建議的年數 */
		public boolean isHorizonOk() {
			return horizonOk;
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

	/** 三種參考配置（保守型、穩健型、積極型），有自訂配置時接在最後 */
	public List<Portfolio> getPortfolios() {
		return portfolios;
	}

	public void setPortfolios(List<Portfolio> portfolios) {
		this.portfolios = portfolios;
	}

	/** 建議的參考配置，三種都達不到時為 null */
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
