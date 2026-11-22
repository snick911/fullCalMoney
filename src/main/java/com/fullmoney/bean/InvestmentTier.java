package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 投資計畫的標的類別，由低風險到高風險排列
 *
 * <p>每類有兩個假設的年報酬率：typicalRate 一般行情、maxRate 行情好時的上限。
 * 需要的年化報酬率不超過 maxRate 就有機會達成，不超過 typicalRate 則一般行情就能達成。
 * volatility 為預估年波動，用於蒙地卡羅模擬。高風險投機與彩券沒有可預期的報酬率，三者皆為 null。</p>
 *
 * <p>定存 0.5%～3%、公債 3%～5%、公司債 6%～8% 取區間中間為一般行情、上限為行情好；股票分 5 個等級。</p>
 */
public enum InvestmentTier {

	DEPOSIT("定存", "0.0175", "0.03", "0", "定存、貨幣市場基金、儲蓄險", "極低", "不限", 0,
			"保本，利率約 0.5%～3%，報酬有限，可能跟不上通膨。"),

	GOV_BOND("公債", "0.04", "0.05", "0.04", "國債、政府公債、公債 ETF", "低", "1 年以上", 1,
			"政府發行，違約風險極低，約 3%～5%；利率上升時價格會下跌，持有到期可避免。"),

	CORPORATE_BOND("公司債", "0.07", "0.08", "0.08", "公司債、高收益債、公司債 ETF", "中", "3 年以上", 3,
			"約 6%～8%，配息較高但有發行公司違約的信用風險；景氣差時價格可能大跌，應分散多家公司。"),

	STOCK_INCOME("定存型股票", "0.05", "0.08", "0.12", "高股息 ETF、金融股、公用事業股（存股）", "中", "3 年以上", 3,
			"以穩定配息為主、股價波動較小，適合長期存股；成長性較低。"),

	STOCK_CORE("穩健型股票", "0.07", "0.12", "0.16", "市值型 ETF、大型權值股（可定期定額）", "中高", "5 年以上", 5,
			"跟著整體市場成長，短期可能下跌 20%～30%；投資期間越長，越能度過景氣循環。"),

	STOCK_GROWTH("穩健成長型股票", "0.08", "0.14", "0.20", "績優成長股、產業型 ETF", "中高", "5 年以上", 5,
			"選擇獲利穩定成長的公司，報酬高於大盤的機會較大，但個股或產業風險也較高。"),

	STOCK_AGGRESSIVE("積極成長型股票", "0.10", "0.20", "0.28", "中小型成長股、科技股、主題型基金", "高", "7 年以上", 7,
			"成長潛力大、波動大，可能長期虧損，只適合能承受大幅下跌的資金，並應分散投資。"),

	STOCK_VOLATILE("高波動型股票", "0.12", "0.25", "0.40", "題材股、槓桿型 ETF", "很高", "7 年以上", 7,
			"漲跌劇烈，一年內腰斬也不少見；槓桿型 ETF 長期持有會有耗損，只適合小部分資金。"),

	SPECULATIVE("高風險投機", null, null, null, "期貨、選擇權、權證", "極高", "短線操作", 0,
			"具槓桿，適合短線操作，高風險高報酬，可能在短時間內損失全部本金；計畫可能不可行。"),

	LOTTERY("只能靠運氣", null, null, null, "大樂透、威力彩等彩券", "幾乎必定虧損", "—", 0,
			"彩券不是投資：期望報酬為負，買越多平均虧越多；頭獎機率大樂透約 1/1,398 萬、威力彩約 1/2,209 萬。只建議用小額娛樂預算，不要投入本金。");

	/** 資產配置可選的股票等級，由低風險到高風險 */
	public static final InvestmentTier[] STOCK_LEVELS = { STOCK_INCOME, STOCK_CORE, STOCK_GROWTH, STOCK_AGGRESSIVE, STOCK_VOLATILE };

	private final String name;
	private final BigDecimal typicalRate;
	private final BigDecimal maxRate;
	private final BigDecimal volatility;
	private final String instruments;
	private final String risk;
	private final String horizon;
	private final int minYears;
	private final String note;

	InvestmentTier(String name, String typicalRate, String maxRate, String volatility, String instruments, String risk, String horizon, int minYears,
			String note) {
		this.name = name;
		this.typicalRate = typicalRate == null ? null : new BigDecimal(typicalRate);
		this.maxRate = maxRate == null ? null : new BigDecimal(maxRate);
		this.volatility = volatility == null ? null : new BigDecimal(volatility);
		this.instruments = instruments;
		this.risk = risk;
		this.horizon = horizon;
		this.minYears = minYears;
		this.note = note;
	}

	public String getName() {
		return name;
	}

	/** @return 一般行情的年報酬率 ex. 0.04，沒有可預期報酬率時為 null */
	public BigDecimal getTypicalRate() {
		return typicalRate;
	}

	/** @return 行情好時的年報酬率上限 ex. 0.05，沒有可預期報酬率時為 null */
	public BigDecimal getMaxRate() {
		return maxRate;
	}

	/** @return 預估年波動 ex. 0.16，沒有可預期報酬率時為 null */
	public BigDecimal getVolatility() {
		return volatility;
	}

	public String getInstruments() {
		return instruments;
	}

	public String getRisk() {
		return risk;
	}

	public String getHorizon() {
		return horizon;
	}

	/** @return 建議的最短投資年數，0 表示不限 */
	public int getMinYears() {
		return minYears;
	}

	public String getNote() {
		return note;
	}

	/** @return 是否為股票等級之一 */
	public boolean isStock() {
		for (InvestmentTier level : STOCK_LEVELS) {
			if (level == this) {
				return true;
			}
		}
		return false;
	}
}
