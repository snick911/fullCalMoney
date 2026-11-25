package com.fullmoney.bean;

/**
 * 定期定額某一年年底的累計結果
 */
public final class DcaYear {

	private final int year;
	private final int count;
	private final double cost;
	private final double value;

	/**
	 * @param count 到這年年底累計投入幾次
	 * @param cost 累計投入成本（本金加買入手續費）
	 * @param value 年底市值
	 */
	public DcaYear(int year, int count, double cost, double value) {
		this.year = year;
		this.count = count;
		this.cost = cost;
		this.value = value;
	}

	public int getYear() {
		return year;
	}

	public int getCount() {
		return count;
	}

	public double getCost() {
		return cost;
	}

	public double getValue() {
		return value;
	}

	/** @return 帳面獲利 = 市值 − 投入成本 */
	public double getProfit() {
		return value - cost;
	}
}
