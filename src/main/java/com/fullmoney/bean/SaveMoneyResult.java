package com.fullmoney.bean;

import java.math.BigDecimal;
import java.util.List;

/**
 * 複利試算結果
 */
public class SaveMoneyResult {

	private final List<SaveMoney> yearList;

	private final BigDecimal totalBaseAmount;

	private final BigDecimal totalSumAmount;

	private final BigDecimal gains;

	private final BigDecimal gainRate;

	public SaveMoneyResult(List<SaveMoney> yearList, BigDecimal totalBaseAmount, BigDecimal totalSumAmount,
			BigDecimal gains, BigDecimal gainRate) {
		this.yearList = yearList;
		this.totalBaseAmount = totalBaseAmount;
		this.totalSumAmount = totalSumAmount;
		this.gains = gains;
		this.gainRate = gainRate;
	}

	public List<SaveMoney> getYearList() {
		return yearList;
	}

	public BigDecimal getTotalBaseAmount() {
		return totalBaseAmount;
	}

	public BigDecimal getTotalSumAmount() {
		return totalSumAmount;
	}

	public BigDecimal getGains() {
		return gains;
	}

	public BigDecimal getGainRate() {
		return gainRate;
	}
}
