package com.fullmoney.bean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 投資計畫的模擬設定：年齡、配置調整、再平衡、報酬分布、壓力測試與中途提領
 */
public final class PlanSettings {

	/** 中途提領：第 month 個月底提領 amount */
	public static final class Withdrawal {

		private final int month;
		private final BigDecimal amount;

		public Withdrawal(int month, BigDecimal amount) {
			this.month = month;
			this.amount = amount;
		}

		public int getMonth() {
			return month;
		}

		public BigDecimal getAmount() {
			return amount;
		}
	}

	public static final int DEFAULT_AGE = 35;

	private int age = DEFAULT_AGE;
	private GlidePath glidePath = GlidePath.FIXED;
	private RebalanceMode rebalance = RebalanceMode.YEARLY;
	private ReturnDistribution distribution = ReturnDistribution.NORMAL;
	private StressScenario stress = StressScenario.NONE;
	private StressTiming stressTiming = StressTiming.END;
	private List<Withdrawal> withdrawals = new ArrayList<>();

	/** 目前年齡 */
	public int getAge() {
		return age;
	}

	public void setAge(int age) {
		this.age = age;
	}

	public GlidePath getGlidePath() {
		return glidePath;
	}

	public void setGlidePath(GlidePath glidePath) {
		this.glidePath = glidePath;
	}

	public RebalanceMode getRebalance() {
		return rebalance;
	}

	public void setRebalance(RebalanceMode rebalance) {
		this.rebalance = rebalance;
	}

	public ReturnDistribution getDistribution() {
		return distribution;
	}

	public void setDistribution(ReturnDistribution distribution) {
		this.distribution = distribution;
	}

	public StressScenario getStress() {
		return stress;
	}

	public void setStress(StressScenario stress) {
		this.stress = stress;
	}

	public StressTiming getStressTiming() {
		return stressTiming;
	}

	public void setStressTiming(StressTiming stressTiming) {
		this.stressTiming = stressTiming;
	}

	public List<Withdrawal> getWithdrawals() {
		return Collections.unmodifiableList(withdrawals);
	}

	public void setWithdrawals(List<Withdrawal> withdrawals) {
		this.withdrawals = new ArrayList<>(withdrawals);
	}

	/** 年齡法則的股票比例上限（%），以目前年齡計算 */
	public int ageStockCap() {
		return Math.max(0, GlidePath.AGE_RULE_BASE - age);
	}
}
