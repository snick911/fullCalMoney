package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 投資計畫的標的類別，由低風險到高風險排列
 *
 * <p>每類有兩個假設的年報酬率：typicalRate 一般行情、maxRate 行情好時的上限。
 * 需要的年化報酬率不超過 maxRate 就有機會達成，不超過 typicalRate 則一般行情就能達成。
 * 高風險投機與彩券沒有可預期的報酬率，兩者皆為 null。</p>
 */
public enum InvestmentTier {

	DEPOSIT("保本型", "0.015", "0.02", "定存、貨幣市場基金、儲蓄險", "極低", "不限", 0,
			"幾乎不會虧損本金，但報酬有限，可能跟不上通膨。"),

	BOND("債券", "0.035", "0.05", "政府公債、投資等級公司債、債券型 ETF／基金", "低", "1 年以上", 1,
			"定期配息、波動較小；持有到期可降低價格波動的影響，需留意利率與發行機構的信用風險。"),

	BALANCED("股債平衡", "0.05", "0.08", "股債平衡配置、平衡型基金、高股息 ETF", "中", "3 年以上", 3,
			"以股票提高報酬、以債券降低波動，適合想穩健成長的資金。"),

	STOCK("股票／基金", "0.07", "0.12", "市值型 ETF、股票型基金、績優股（可定期定額）", "中高", "5 年以上", 5,
			"長期報酬較高，但短期可能下跌 20%～30% 以上；投資期間越長，越能度過景氣循環。"),

	AGGRESSIVE("積極成長", "0.10", "0.20", "成長股、產業／主題型基金、槓桿型 ETF", "高", "5 年以上", 5,
			"波動大、可能長期虧損，只適合能承受大幅下跌的資金，並應分散投資。"),

	SPECULATIVE("高風險投機", null, null, "期貨、選擇權、權證", "極高", "短線操作", 0,
			"具槓桿，適合短線操作，高風險高報酬，可能在短時間內損失全部本金；計畫可能不可行。"),

	LOTTERY("只能靠運氣", null, null, "大樂透、威力彩等彩券", "幾乎必定虧損", "—", 0,
			"彩券不是投資：期望報酬為負，買越多平均虧越多；頭獎機率大樂透約 1/1,398 萬、威力彩約 1/2,209 萬。只建議用小額娛樂預算，不要投入本金。");

	private final String name;
	private final BigDecimal typicalRate;
	private final BigDecimal maxRate;
	private final String instruments;
	private final String risk;
	private final String horizon;
	private final int minYears;
	private final String note;

	InvestmentTier(String name, String typicalRate, String maxRate, String instruments, String risk, String horizon, int minYears, String note) {
		this.name = name;
		this.typicalRate = typicalRate == null ? null : new BigDecimal(typicalRate);
		this.maxRate = maxRate == null ? null : new BigDecimal(maxRate);
		this.instruments = instruments;
		this.risk = risk;
		this.horizon = horizon;
		this.minYears = minYears;
		this.note = note;
	}

	public String getName() {
		return name;
	}

	/** @return 一般行情的年報酬率 ex. 0.035，沒有可預期報酬率時為 null */
	public BigDecimal getTypicalRate() {
		return typicalRate;
	}

	/** @return 行情好時的年報酬率上限 ex. 0.05，沒有可預期報酬率時為 null */
	public BigDecimal getMaxRate() {
		return maxRate;
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
}
