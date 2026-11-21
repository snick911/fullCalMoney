package com.fullmoney.bean;

import java.math.BigDecimal;
import java.util.List;

/**
 * 投資計畫試算結果
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

	/** 逐年試算的一列：第 months 個月底的金額 */
	public static final class Row {

		private final int months;
		private final BigDecimal balance;

		public Row(int months, BigDecimal balance) {
			this.months = months;
			this.balance = balance;
		}

		public int getMonths() {
			return months;
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
		private final BigDecimal typicalEnd;
		private final BigDecimal maxEnd;

		public Option(InvestmentTier tier, Fit fit, boolean horizonOk, BigDecimal typicalEnd, BigDecimal maxEnd) {
			this.tier = tier;
			this.fit = fit;
			this.horizonOk = horizonOk;
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

		/** 以一般行情報酬率計算的期滿金額，沒有可預期報酬率時為 null */
		public BigDecimal getTypicalEnd() {
			return typicalEnd;
		}

		/** 以行情好的報酬率上限計算的期滿金額，沒有可預期報酬率時為 null */
		public BigDecimal getMaxEnd() {
			return maxEnd;
		}
	}

	/** 配置建議：低風險與高風險兩類標的的比例，以一般行情報酬率加權後剛好達到需要的報酬率（比例取 10% 為單位，向上取） */
	public static final class Allocation {

		private final InvestmentTier low;
		private final InvestmentTier high;
		private final int highPercent;
		private final BigDecimal rate;
		private final BigDecimal endAmount;
		private final boolean horizonOk;

		public Allocation(InvestmentTier low, InvestmentTier high, int highPercent, BigDecimal rate, BigDecimal endAmount, boolean horizonOk) {
			this.low = low;
			this.high = high;
			this.highPercent = highPercent;
			this.rate = rate;
			this.endAmount = endAmount;
			this.horizonOk = horizonOk;
		}

		public InvestmentTier getLow() {
			return low;
		}

		public InvestmentTier getHigh() {
			return high;
		}

		/** 高風險那一類的比例（%），低風險為 100 減此值 */
		public int getHighPercent() {
			return highPercent;
		}

		/** 加權後的一般行情年報酬率 */
		public BigDecimal getRate() {
			return rate;
		}

		public BigDecimal getEndAmount() {
			return endAmount;
		}

		/** 投資期間是否達到高風險那一類建議的年數 */
		public boolean isHorizonOk() {
			return horizonOk;
		}
	}

	private BigDecimal principal;
	private BigDecimal profit;
	private BigDecimal target;
	private int months;
	private BigDecimal totalReturn;
	private BigDecimal multiple;
	private BigDecimal requiredRate;
	private InvestmentTier tier;
	private List<Option> options;
	private Allocation allocation;
	private List<Row> rows;
	private BigDecimal extendedRate;
	private InvestmentTier extendedTier;
	private InvestmentTier benchmark;
	private int benchmarkMonths;
	private BigDecimal benchmarkPrincipal;
	private BigDecimal benchmarkProfit;

	public BigDecimal getPrincipal() {
		return principal;
	}

	public void setPrincipal(BigDecimal principal) {
		this.principal = principal;
	}

	public BigDecimal getProfit() {
		return profit;
	}

	public void setProfit(BigDecimal profit) {
		this.profit = profit;
	}

	/** 目標金額 = 本金 + 期望獲利 */
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

	/** 總報酬率 = 期望獲利 / 本金 ex. 0.25 */
	public BigDecimal getTotalReturn() {
		return totalReturn;
	}

	public void setTotalReturn(BigDecimal totalReturn) {
		this.totalReturn = totalReturn;
	}

	/** 目標金額是本金的幾倍 */
	public BigDecimal getMultiple() {
		return multiple;
	}

	public void setMultiple(BigDecimal multiple) {
		this.multiple = multiple;
	}

	/** 需要的年化報酬率 ex. 0.0456 */
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

	/** 可考慮的方案，由低風險到高風險 */
	public List<Option> getOptions() {
		return options;
	}

	public void setOptions(List<Option> options) {
		this.options = options;
	}

	/** 配置建議，不需要混合兩類時為 null */
	public Allocation getAllocation() {
		return allocation;
	}

	public void setAllocation(Allocation allocation) {
		this.allocation = allocation;
	}

	public List<Row> getRows() {
		return rows;
	}

	public void setRows(List<Row> rows) {
		this.rows = rows;
	}

	/** 投資期間短於風險最低方案的建議年數時，延長到建議年數後需要的年化報酬率；期間足夠時為 null */
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

	/** 以比較基準的一般行情報酬率，達到目標需要的月數 */
	public int getBenchmarkMonths() {
		return benchmarkMonths;
	}

	public void setBenchmarkMonths(int benchmarkMonths) {
		this.benchmarkMonths = benchmarkMonths;
	}

	/** 以比較基準的一般行情報酬率，同期間達到期望獲利需要的本金 */
	public BigDecimal getBenchmarkPrincipal() {
		return benchmarkPrincipal;
	}

	public void setBenchmarkPrincipal(BigDecimal benchmarkPrincipal) {
		this.benchmarkPrincipal = benchmarkPrincipal;
	}

	/** 以比較基準的一般行情報酬率，同本金、同期間可期待的獲利 */
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
