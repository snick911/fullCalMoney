package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 定期定額的標的類型：決定買入手續費、每筆最低手續費與期末賣出費用的預設值，網頁可再自行修改
 *
 * <p>ETF 與股票：券商手續費上限 0.1425%，定期定額每筆最低手續費多為 1 元；賣出時另扣證交稅（ETF 0.1%、股票 0.3%）。
 * 基金：申購手續費依銷售通路與優惠差很多，預設 1%；贖回通常不收費。</p>
 */
public enum DcaTarget {

	FUND("基金", "0.01", "0", "0", "申購手續費依銷售通路與優惠而不同，請改成實際費率；贖回通常不收費"),

	ETF("ETF", "0.001425", "1", "0.002425", "券商手續費 0.1425%（常有折扣），賣出時另扣證交稅 0.1%"),

	STOCK("股票", "0.001425", "1", "0.004425", "券商手續費 0.1425%（常有折扣），賣出時另扣證交稅 0.3%");

	private final String label;
	private final BigDecimal buyFeeRate;
	private final BigDecimal minFee;
	private final BigDecimal sellFeeRate;
	private final String description;

	DcaTarget(String label, String buyFeeRate, String minFee, String sellFeeRate, String description) {
		this.label = label;
		this.buyFeeRate = new BigDecimal(buyFeeRate);
		this.minFee = new BigDecimal(minFee);
		this.sellFeeRate = new BigDecimal(sellFeeRate);
		this.description = description;
	}

	public String getLabel() {
		return label;
	}

	/** @return 買入手續費率 ex. 0.001425 */
	public BigDecimal getBuyFeeRate() {
		return buyFeeRate;
	}

	/** @return 每筆最低手續費（元） */
	public BigDecimal getMinFee() {
		return minFee;
	}

	/** @return 期末賣出費用率（手續費加證交稅） ex. 0.002425 */
	public BigDecimal getSellFeeRate() {
		return sellFeeRate;
	}

	public String getDescription() {
		return description;
	}

	@Override
	public String toString() {
		return label;
	}
}
