package com.fullmoney.service;

/**
 * 投資計畫的現金流：本金在第 1 個月初投入，每月月初投入 monthly，指定月份的月底提領
 *
 * <p>提領發生在第 m 個月底（該月已成長之後），之後少了這筆錢的複利。
 * 期滿金額 = Σ 每筆投入 × g^(到期滿的月數) − Σ 每筆提領 × g^(M − m)，g 為月成長率。</p>
 */
public final class CashFlows {

	private static final int MONTHS_PER_YEAR = 12;
	private static final double EPSILON = 1e-9;

	private final double principal;
	private final double monthly;
	private final int months;
	private final int[] withdrawMonths;
	private final double[] withdrawAmounts;

	/**
	 * @param withdrawMonths 提領的月份（第幾個月底，1～months），同一個月多筆會合併
	 * @param withdrawAmounts 提領金額，與 withdrawMonths 一一對應
	 */
	public CashFlows(double principal, double monthly, int months, int[] withdrawMonths, double[] withdrawAmounts) {
		this.principal = principal;
		this.monthly = monthly;
		this.months = months;
		//依月份排序並合併同月；筆數很少，用插入排序（瀏覽器端引擎不必載入排序比較器）
		int[] sortedMonths = new int[withdrawMonths.length];
		double[] sortedAmounts = new double[withdrawMonths.length];
		int count = 0;
		for (int i = 0; i < withdrawMonths.length; i++) {
			int month = withdrawMonths[i];
			int at = 0;
			while (at < count && sortedMonths[at] < month) {
				at++;
			}
			if (at < count && sortedMonths[at] == month) {
				sortedAmounts[at] += withdrawAmounts[i];
				continue;
			}
			for (int j = count; j > at; j--) {
				sortedMonths[j] = sortedMonths[j - 1];
				sortedAmounts[j] = sortedAmounts[j - 1];
			}
			sortedMonths[at] = month;
			sortedAmounts[at] = withdrawAmounts[i];
			count++;
		}
		this.withdrawMonths = new int[count];
		this.withdrawAmounts = new double[count];
		System.arraycopy(sortedMonths, 0, this.withdrawMonths, 0, count);
		System.arraycopy(sortedAmounts, 0, this.withdrawAmounts, 0, count);
	}

	/** 沒有提領 */
	public static CashFlows of(double principal, double monthly, int months) {
		return new CashFlows(principal, monthly, months, new int[0], new double[0]);
	}

	public double getPrincipal() {
		return principal;
	}

	public double getMonthly() {
		return monthly;
	}

	public int getMonths() {
		return months;
	}

	/** 提領月份（由小到大，不重複） */
	public int[] getWithdrawMonths() {
		return withdrawMonths.clone();
	}

	/** 與 {@link #getWithdrawMonths()} 對應的提領金額 */
	public double[] getWithdrawAmounts() {
		return withdrawAmounts.clone();
	}

	public boolean hasWithdrawals() {
		return withdrawMonths.length > 0;
	}

	/** 第 month 個月底的提領金額，沒有時為 0 */
	public double withdrawalAt(int month) {
		for (int i = 0; i < withdrawMonths.length; i++) {
			if (withdrawMonths[i] == month) {
				return withdrawAmounts[i];
			}
		}
		return 0;
	}

	/** 提領合計 */
	public double withdrawn() {
		return withdrawnBy(months);
	}

	/** 到第 month 個月底（含）為止的提領合計 */
	public double withdrawnBy(int month) {
		double total = 0;
		for (int i = 0; i < withdrawMonths.length && withdrawMonths[i] <= month; i++) {
			total += withdrawAmounts[i];
		}
		return total;
	}

	/** 總投入 = 本金 + 每月投資 × 月數 */
	public double invested() {
		return investedBy(months);
	}

	/** 到第 month 個月為止的累積投入 */
	public double investedBy(int month) {
		return principal + monthly * month;
	}

	/** 期間改為 months，只保留該月以前的提領 */
	public CashFlows truncate(int newMonths) {
		int count = 0;
		while (count < withdrawMonths.length && withdrawMonths[count] <= newMonths) {
			count++;
		}
		int[] keptMonths = new int[count];
		double[] keptAmounts = new double[count];
		System.arraycopy(withdrawMonths, 0, keptMonths, 0, count);
		System.arraycopy(withdrawAmounts, 0, keptAmounts, 0, count);
		return new CashFlows(principal, monthly, newMonths, keptMonths, keptAmounts);
	}

	/** 同樣的本金與提領，改變每月投資金額 */
	public CashFlows withMonthly(double newMonthly) {
		return new CashFlows(principal, newMonthly, months, withdrawMonths, withdrawAmounts);
	}

	/** 同樣的提領，改變期間（期間延長時提領月份不變） */
	public CashFlows withMonths(int newMonths) {
		return newMonths < months ? truncate(newMonths) : new CashFlows(principal, monthly, newMonths, withdrawMonths, withdrawAmounts);
	}

	/** 以年化報酬率 annualRate 逐月複利的期滿金額 */
	public double futureValue(double annualRate) {
		return InvestmentPlanner.futureValue(principal, monthly, annualRate, months) - withdrawalLostValue(annualRate);
	}

	/** 提領造成的期滿金額減少：Σ 提領 × g^(M − m) */
	private double withdrawalLostValue(double annualRate) {
		if (withdrawMonths.length == 0) {
			return 0;
		}
		double monthGrowth = Math.pow(1 + annualRate, 1.0 / MONTHS_PER_YEAR);
		double lost = 0;
		for (int i = 0; i < withdrawMonths.length; i++) {
			lost += withdrawAmounts[i] * Math.pow(monthGrowth, months - withdrawMonths[i]);
		}
		return lost;
	}

	/** 提領後少賺的獲利：Σ 提領 × (g^(M − m) − 1) */
	public double withdrawalLostGain(double annualRate) {
		return withdrawalLostValue(annualRate) - withdrawn();
	}

	/**
	 * 第一筆讓「累計提領 &gt; 累計投入」的提領月份；都沒有時為 0。
	 * 不允許提領尚未投入的錢（報酬率為 0 時也付得出來），需要的報酬率才有唯一解
	 */
	public int firstOverdrawnMonth() {
		double withdrawnSoFar = 0;
		for (int i = 0; i < withdrawMonths.length; i++) {
			withdrawnSoFar += withdrawAmounts[i];
			if (withdrawnSoFar > investedBy(withdrawMonths[i]) + EPSILON) {
				return withdrawMonths[i];
			}
		}
		return 0;
	}
}
