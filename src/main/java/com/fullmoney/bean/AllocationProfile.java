package com.fullmoney.bean;

/**
 * 資產配置類型：依股票比例分為保守型（25% 以下）、穩健型（26%～45%）、積極型（超過 45%）
 *
 * <p>各類型的最佳配置由 {@link com.fullmoney.service.InvestmentPlanner} 從每 5% 一級的所有組合中挑選，並有以下限制：</p>
 * <ul>
 * <li>定存上限（保守型 50%、穩健型 30%、積極型 20%）：現金保留適量即可，太多會被通膨侵蝕。</li>
 * <li>公司債上限（保守型 20%、穩健型 30%、積極型 30%）：6%～8% 的公司債有信用風險，不宜集中。</li>
 * <li>可用的股票等級：保守型只用定存型、穩健型股票；穩健型可再加穩健成長型；積極型用穩健型以上的四個等級。</li>
 * </ul>
 */
public enum AllocationProfile {

	CONSERVATIVE("保守型", 25, 50, 20, new InvestmentTier[] { InvestmentTier.STOCK_INCOME, InvestmentTier.STOCK_CORE }, 1, "1 年以上",
			"以定存與公債為主，股票只選定存型、穩健型，波動小，適合重視本金安全的資金。"),

	BALANCED("穩健型", 45, 30, 30, new InvestmentTier[] { InvestmentTier.STOCK_INCOME, InvestmentTier.STOCK_CORE, InvestmentTier.STOCK_GROWTH }, 3,
			"3 年以上", "股債兼顧、追求穩定成長，短期仍可能小幅虧損。"),

	AGGRESSIVE("積極型", 100, 20, 30, new InvestmentTier[] { InvestmentTier.STOCK_CORE, InvestmentTier.STOCK_GROWTH, InvestmentTier.STOCK_AGGRESSIVE,
			InvestmentTier.STOCK_VOLATILE }, 5, "5 年以上", "股票占一半以上，長期報酬較高，但短期可能下跌 20%～30%。");

	private final String name;
	private final int maxStock;
	private final int maxDeposit;
	private final int maxCorporate;
	private final InvestmentTier[] stockLevels;
	private final int minYears;
	private final String horizon;
	private final String note;

	AllocationProfile(String name, int maxStock, int maxDeposit, int maxCorporate, InvestmentTier[] stockLevels, int minYears, String horizon,
			String note) {
		this.name = name;
		this.maxStock = maxStock;
		this.maxDeposit = maxDeposit;
		this.maxCorporate = maxCorporate;
		this.stockLevels = stockLevels;
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

	/** 此類型的公司債比例上限（%） */
	public int getMaxCorporate() {
		return maxCorporate;
	}

	/** 此類型可用的股票等級 */
	public InvestmentTier[] getStockLevels() {
		return stockLevels;
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
