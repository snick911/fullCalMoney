package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 定期定額的推薦範例：保守、穩健、積極各一組，網頁按一下就帶入所有條件（費用用標的類型的預設值）
 *
 * <p>代碼與投資計畫的 {@link AllocationProfile} 相同，網頁沿用同一組類型顏色。年化報酬率都是假設值，
 * 風險越高區間越寬：保守 2%～4%、穩健 4%～8%、積極 2%～14%。</p>
 */
public enum DcaPreset {

	CONSERVATIVE("保守", "債券型或平衡型基金", DcaTarget.FUND, 1, 10, "3000", "5000", "2", "4"),

	BALANCED("穩健", "追蹤大盤的市值型 ETF", DcaTarget.ETF, 1, 15, "5000", "10000", "4", "8"),

	AGGRESSIVE("積極", "個股或成長型股票", DcaTarget.STOCK, 1, 20, "10000", "20000", "2", "14");

	private final String label;
	private final String description;
	private final DcaTarget target;
	private final int interval;
	private final int years;
	private final BigDecimal amountMin;
	private final BigDecimal amountMax;
	private final BigDecimal rateMin;
	private final BigDecimal rateMax;

	/**
	 * @param interval 每隔幾個月投入一次
	 * @param rateMin 預估年化報酬率的最低值(%)
	 */
	DcaPreset(String label, String description, DcaTarget target, int interval, int years, String amountMin, String amountMax, String rateMin,
			String rateMax) {
		this.label = label;
		this.description = description;
		this.target = target;
		this.interval = interval;
		this.years = years;
		this.amountMin = new BigDecimal(amountMin);
		this.amountMax = new BigDecimal(amountMax);
		this.rateMin = new BigDecimal(rateMin);
		this.rateMax = new BigDecimal(rateMax);
	}

	public String getLabel() {
		return label;
	}

	/** @return 適合的標的 ex. 追蹤大盤的市值型 ETF */
	public String getDescription() {
		return description;
	}

	public DcaTarget getTarget() {
		return target;
	}

	public int getInterval() {
		return interval;
	}

	public int getYears() {
		return years;
	}

	public BigDecimal getAmountMin() {
		return amountMin;
	}

	public BigDecimal getAmountMax() {
		return amountMax;
	}

	/** @return 預估年化報酬率的最低值(%) */
	public BigDecimal getRateMin() {
		return rateMin;
	}

	/** @return 預估年化報酬率的最高值(%) */
	public BigDecimal getRateMax() {
		return rateMax;
	}

	@Override
	public String toString() {
		return label;
	}
}
