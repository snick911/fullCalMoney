package com.fullmoney.bean;

/**
 * 資產配置類型：依股票比例分為保守型（25% 以下）、穩健型（26%～45%）、積極型（超過 45%）
 *
 * <p>各類型的最佳配置由 {@link com.fullmoney.service.InvestmentPlanner} 從每 5% 一級的所有組合中挑選。
 * 定存有上限（保守型 50%、穩健型 30%、積極型 20%）：現金保留適量即可，太多會被通膨侵蝕，也不符合該類型的性質。</p>
 */
public enum AllocationProfile {

	CONSERVATIVE("保守型", 25, 50, 1, "1 年以上", "以債券與定存為主，波動小，適合重視本金安全的資金。"),

	BALANCED("穩健型", 45, 30, 3, "3 年以上", "股債兼顧、追求穩定成長，短期仍可能小幅虧損。"),

	AGGRESSIVE("積極型", 100, 20, 5, "5 年以上", "股票占一半以上，長期報酬較高，但短期可能下跌 15%～25%。");

	private final String name;
	private final int maxStock;
	private final int maxDeposit;
	private final int minYears;
	private final String horizon;
	private final String note;

	AllocationProfile(String name, int maxStock, int maxDeposit, int minYears, String horizon, String note) {
		this.name = name;
		this.maxStock = maxStock;
		this.maxDeposit = maxDeposit;
		this.minYears = minYears;
		this.horizon = horizon;
		this.note = note;
	}

	/**
	 * 依股票比例分類：25% 以下保守型、45% 以下穩健型、超過 45% 積極型
	 *
	 * @param stockPercent 股票比例（%）
	 */
	public static AllocationProfile classify(int stockPercent) {
		for (AllocationProfile profile : values()) {
			if (stockPercent <= profile.maxStock) {
				return profile;
			}
		}
		return AGGRESSIVE;
	}

	public String getName() {
		return name;
	}

	/** 屬於此類型的股票比例上限（%） */
	public int getMaxStock() {
		return maxStock;
	}

	/** 此類型的定存比例上限（%） */
	public int getMaxDeposit() {
		return maxDeposit;
	}

	/** 建議的最短投資年數 */
	public int getMinYears() {
		return minYears;
	}

	public String getHorizon() {
		return horizon;
	}

	public String getNote() {
		return note;
	}
}
