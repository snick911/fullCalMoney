package com.fullmoney.bean;

/**
 * 資產配置的再平衡方式：漲多的資產賣出一部分、跌多的買回，讓比例回到設定值
 *
 * <p>模擬以一年為一段，所以「偏離時再平衡」也是每年底檢查一次。逐年調整股票比例（{@link GlidePath}）時，
 * 每年都會依新比例調整，等同每年再平衡。</p>
 */
public enum RebalanceMode {

	/** 每年底調回設定比例 */
	YEARLY("每年再平衡"),

	/** 每年底檢查，有任一資產比例偏離超過 5 個百分點才調回 */
	BAND("偏離超過 5 個百分點才再平衡"),

	/** 買進後不再調整，漲多的資產比例會越來越高；每月投資仍依設定比例分配 */
	NONE("不再平衡");

	/** 偏離多少（比例的差）才再平衡 */
	public static final double BAND_WIDTH = 0.05;

	private final String label;

	RebalanceMode(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

	@Override
	public String toString() {
		return label;
	}
}
