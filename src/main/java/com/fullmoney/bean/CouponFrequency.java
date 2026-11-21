package com.fullmoney.bean;

/**
 * 債券配息頻率
 */
public enum CouponFrequency {

	ANNUAL("每年", 1),
	SEMI_ANNUAL("每半年", 2),
	QUARTERLY("每季", 4),
	MONTHLY("每月", 12);

	private final String label;
	private final int timesPerYear;

	CouponFrequency(String label, int timesPerYear) {
		this.label = label;
		this.timesPerYear = timesPerYear;
	}

	public String getLabel() {
		return label;
	}

	public int getTimesPerYear() {
		return timesPerYear;
	}

	@Override
	public String toString() {
		return label;
	}
}
