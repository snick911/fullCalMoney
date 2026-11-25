package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 投資計畫的推薦範例：保守、穩健、積極各一個常見的理財目標，網頁按一下就帶入所有條件與模擬設定（並清空中途提領）
 *
 * <p>代碼與 {@link AllocationProfile} 相同，網頁沿用同一組類型顏色。數值挑選成試算後建議的配置類型剛好是同名的那一型：
 * 保守（5 年存到 120 萬，需要年化約 2.4%）、穩健（10 年存到 200 萬，約 4.7%）、積極（20 年存到 1,000 萬，約 6%），
 * 由 CalculatorCheck 檢查。</p>
 */
public enum PlanPreset {

	CONSERVATIVE("保守", "5 年後的購屋頭期款", "500000", "10000", 5, "100000", 45, GlidePath.TARGET_DATE),

	BALANCED("穩健", "10 年後的子女教育金", "300000", "10000", 10, "500000", 35, GlidePath.FIXED),

	AGGRESSIVE("積極", "20 年後的退休金", "1000000", "15000", 20, "5400000", 40, GlidePath.FIXED);

	private static final int MONTHS_PER_YEAR = 12;

	private final String label;
	private final String description;
	private final BigDecimal principal;
	private final BigDecimal monthlyFixed;
	private final int years;
	private final BigDecimal profit;
	private final int age;
	private final GlidePath glide;

	PlanPreset(String label, String description, String principal, String monthlyFixed, int years, String profit, int age, GlidePath glide) {
		this.label = label;
		this.description = description;
		this.principal = new BigDecimal(principal);
		this.monthlyFixed = new BigDecimal(monthlyFixed);
		this.years = years;
		this.profit = new BigDecimal(profit);
		this.age = age;
		this.glide = glide;
	}

	public String getLabel() {
		return label;
	}

	/** @return 理財目標 ex. 10 年後的子女教育金 */
	public String getDescription() {
		return description;
	}

	public BigDecimal getPrincipal() {
		return principal;
	}

	public BigDecimal getMonthlyFixed() {
		return monthlyFixed;
	}

	/** @return 每月浮動投資的最低值，範例都不使用浮動投資 */
	public BigDecimal getFloatMin() {
		return BigDecimal.ZERO;
	}

	public BigDecimal getFloatMax() {
		return BigDecimal.ZERO;
	}

	public int getYears() {
		return years;
	}

	/** @return 年數以外的月數，範例都是整年 */
	public int getMonths() {
		return 0;
	}

	public BigDecimal getProfit() {
		return profit;
	}

	/** @return 目標金額 = 本金 + 每月投資 × 月數 + 期望獲利 */
	public BigDecimal getTarget() {
		return principal.add(monthlyFixed.multiply(BigDecimal.valueOf((long) years * MONTHS_PER_YEAR + getMonths()))).add(profit);
	}

	public int getAge() {
		return age;
	}

	public GlidePath getGlide() {
		return glide;
	}

	/** 以下模擬設定三個範例相同，都用網頁的預設值 */
	public RebalanceMode getRebalance() {
		return RebalanceMode.YEARLY;
	}

	public ReturnDistribution getDistribution() {
		return ReturnDistribution.NORMAL;
	}

	public StressScenario getStress() {
		return StressScenario.NONE;
	}

	public StressTiming getStressTiming() {
		return StressTiming.END;
	}

	@Override
	public String toString() {
		return label;
	}
}
