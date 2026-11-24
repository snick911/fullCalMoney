package com.fullmoney.bean;

/**
 * 定期買彩券的頻率，一年以 52 週計算
 */
public enum PurchaseFrequency {

	/** 每期開獎都買：今彩539 每週 6 期、大樂透與威力彩每週 2 期 */
	EVERY_DRAW("每期開獎都買"),

	WEEKLY("每週買一次"),

	MONTHLY("每月買一次");

	private static final int WEEKS_PER_YEAR = 52;
	private static final int MONTHS_PER_YEAR = 12;

	private final String label;

	PurchaseFrequency(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

	/** @return 一年買幾次 ex. 大樂透每期都買為 104 次 */
	public int timesPerYear(LotteryGame game) {
		return switch (this) {
		case EVERY_DRAW -> game.getDrawsPerWeek() * WEEKS_PER_YEAR;
		case WEEKLY -> WEEKS_PER_YEAR;
		case MONTHLY -> MONTHS_PER_YEAR;
		};
	}

	@Override
	public String toString() {
		return label;
	}
}
