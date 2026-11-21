package com.fullmoney.bean;

/**
 * 資產配置類型：依股票比例分為保守型、穩健型、積極型，每型有一組參考配置（定存／債券／股票）
 *
 * <p>配置的年報酬率 = 各資產比例 × 該類標的的報酬率（定存用保本型、債券用債券、股票用股票／基金）。</p>
 */
public enum AllocationProfile {

	CONSERVATIVE("保守型", 30, 50, 20, 25, 1, "1 年以上", "以債券與定存為主，波動小，適合重視本金安全的資金。"),

	BALANCED("穩健型", 20, 45, 35, 45, 3, "3 年以上", "股債兼顧、追求穩定成長，短期仍可能小幅虧損。"),

	AGGRESSIVE("積極型", 10, 40, 50, 100, 5, "5 年以上", "股票占一半以上，長期報酬較高，但短期可能下跌 15%～25%。");

	private final String name;
	private final int deposit;
	private final int bond;
	private final int stock;
	private final int maxStock;
	private final int minYears;
	private final String horizon;
	private final String note;

	AllocationProfile(String name, int deposit, int bond, int stock, int maxStock, int minYears, String horizon, String note) {
		this.name = name;
		this.deposit = deposit;
		this.bond = bond;
		this.stock = stock;
		this.maxStock = maxStock;
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

	/** 參考配置的定存比例（%） */
	public int getDeposit() {
		return deposit;
	}

	/** 參考配置的債券比例（%） */
	public int getBond() {
		return bond;
	}

	/** 參考配置的股票比例（%） */
	public int getStock() {
		return stock;
	}

	/** 屬於此類型的股票比例上限（%） */
	public int getMaxStock() {
		return maxStock;
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
