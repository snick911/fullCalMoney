package com.fullmoney.bean;

import java.math.BigDecimal;
import java.util.List;

/**
 * 投資計畫試算結果
 */
public class InvestmentPlan {

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

	/** 各類標的試算：以該類的年報酬率上限計算 */
	public static final class Scenario {

		private final InvestmentTier tier;
		private final BigDecimal endAmount;
		private final boolean reached;
		private final int monthsNeeded;

		public Scenario(InvestmentTier tier, BigDecimal endAmount, boolean reached, int monthsNeeded) {
			this.tier = tier;
			this.endAmount = endAmount;
			this.reached = reached;
			this.monthsNeeded = monthsNeeded;
		}

		public InvestmentTier getTier() {
			return tier;
		}

		/** 期滿金額 */
		public BigDecimal getEndAmount() {
			return endAmount;
		}

		/** 期滿時是否達到目標金額 */
		public boolean isReached() {
			return reached;
		}

		/** 以此報酬率達到目標需要的月數，期望獲利為 0 時為 0 */
		public int getMonthsNeeded() {
			return monthsNeeded;
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
	private List<Row> rows;
	private List<Scenario> scenarios;
	private BigDecimal extendedRate;
	private InvestmentTier extendedTier;
	private Scenario benchmark;
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

	/** 建議的標的類別 */
	public InvestmentTier getTier() {
		return tier;
	}

	public void setTier(InvestmentTier tier) {
		this.tier = tier;
	}

	public List<Row> getRows() {
		return rows;
	}

	public void setRows(List<Row> rows) {
		this.rows = rows;
	}

	public List<Scenario> getScenarios() {
		return scenarios;
	}

	public void setScenarios(List<Scenario> scenarios) {
		this.scenarios = scenarios;
	}

	/** 投資期間短於建議年數時，延長到建議年數後需要的年化報酬率；期間足夠時為 null */
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

	/** 計畫不可行時用來估算替代方案的標的（股票／基金）試算；可行時為 null */
	public Scenario getBenchmark() {
		return benchmark;
	}

	public void setBenchmark(Scenario benchmark) {
		this.benchmark = benchmark;
	}

	/** 以比較基準的報酬率，同期間達到期望獲利需要的本金 */
	public BigDecimal getBenchmarkPrincipal() {
		return benchmarkPrincipal;
	}

	public void setBenchmarkPrincipal(BigDecimal benchmarkPrincipal) {
		this.benchmarkPrincipal = benchmarkPrincipal;
	}

	/** 以比較基準的報酬率，同本金、同期間可期待的獲利 */
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
