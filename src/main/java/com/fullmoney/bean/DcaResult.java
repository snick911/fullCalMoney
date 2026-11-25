package com.fullmoney.bean;

import java.util.List;

/**
 * 定期定額一種情境（每期金額與年化報酬率固定）的試算結果
 */
public final class DcaResult {

	private final int count;
	private final double fee;
	private final double invested;
	private final double value;
	private final double sellCost;
	private final double irr;
	private final List<DcaYear> years;

	/**
	 * @param count 投入次數
	 * @param fee 每次買入的手續費
	 * @param invested 本金合計（不含手續費）
	 * @param value 期末市值
	 * @param sellCost 期末賣出費用
	 * @param irr 扣除買賣費用後的年化報酬率
	 */
	public DcaResult(int count, double fee, double invested, double value, double sellCost, double irr, List<DcaYear> years) {
		this.count = count;
		this.fee = fee;
		this.invested = invested;
		this.value = value;
		this.sellCost = sellCost;
		this.irr = irr;
		this.years = years;
	}

	public int getCount() {
		return count;
	}

	public double getFee() {
		return fee;
	}

	public double getInvested() {
		return invested;
	}

	/** @return 買入手續費合計 */
	public double getFees() {
		return fee * count;
	}

	/** @return 總投入成本 = 本金 + 買入手續費 */
	public double getCost() {
		return invested + getFees();
	}

	public double getValue() {
		return value;
	}

	/** @return 帳面獲利 = 期末市值 − 總投入成本 */
	public double getProfit() {
		return value - getCost();
	}

	/** @return 總報酬率 = 帳面獲利 ÷ 總投入成本 */
	public double getTotalReturn() {
		return getProfit() / getCost();
	}

	public double getSellCost() {
		return sellCost;
	}

	/** @return 賣出後拿回 = 期末市值 − 賣出費用 */
	public double getNet() {
		return value - sellCost;
	}

	public double getIrr() {
		return irr;
	}

	public List<DcaYear> getYears() {
		return years;
	}
}
