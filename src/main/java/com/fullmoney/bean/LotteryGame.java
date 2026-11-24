package com.fullmoney.bean;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 台灣彩券的三種電腦型彩券：每注價格、開獎頻率、各獎項的中獎組合數與獎金
 *
 * <p>今彩539 全部是固定獎金。大樂透、威力彩的總獎金為當期銷售金額 × 總獎金支出率（大樂透 56%、威力彩 55%），
 * 先付固定獎金，剩下的依比率分給獎金池的獎項（大樂透頭獎 82%、貳獎 6.5%、參獎 7%、肆獎 4.5%；威力彩頭獎 89%、貳獎 11%）；
 * 無人中獎時累積到下一期。規則以台灣彩券公告為準，加碼活動與熱門號碼時普獎改為均分的規則未計入。</p>
 */
public enum LotteryGame {

	DAILY_CASH("今彩539", 50, 6, "每週一至週六開獎", "從 1～39 選 5 個號碼", 575_757L, null, null,
			LotteryPrize.fixed("頭獎", "5 個號碼全中", 1, "8000000"),
			LotteryPrize.fixed("貳獎", "中 4 個", 170, "20000"),
			LotteryPrize.fixed("參獎", "中 3 個", 5_610, "300"),
			LotteryPrize.fixed("肆獎", "中 2 個", 59_840, "50")),

	LOTTO("大樂透", 50, 2, "每週二、五開獎", "從 1～49 選 6 個號碼，另開 1 個特別號", 13_983_816L, "0.56", "100000000",
			LotteryPrize.pool("頭獎", "6 個號碼全中", 1, "0.82"),
			LotteryPrize.pool("貳獎", "中 5 個＋特別號", 6, "0.065"),
			LotteryPrize.pool("參獎", "中 5 個", 252, "0.07"),
			LotteryPrize.pool("肆獎", "中 4 個＋特別號", 630, "0.045"),
			LotteryPrize.fixed("伍獎", "中 4 個", 12_915, "2000"),
			LotteryPrize.fixed("陸獎", "中 3 個＋特別號", 17_220, "1000"),
			LotteryPrize.fixed("柒獎", "中 2 個＋特別號", 172_200, "400"),
			LotteryPrize.fixed("普獎", "中 3 個", 229_600, "400")),

	SUPER_LOTTO("威力彩", 100, 2, "每週一、四開獎", "第 1 區從 1～38 選 6 個號碼，第 2 區從 1～8 選 1 個", 22_085_448L, "0.55", "200000000",
			LotteryPrize.pool("頭獎", "第 1 區 6 個全中＋第 2 區", 1, "0.89"),
			LotteryPrize.pool("貳獎", "第 1 區 6 個全中", 7, "0.11"),
			LotteryPrize.fixed("參獎", "第 1 區中 5 個＋第 2 區", 192, "150000"),
			LotteryPrize.fixed("肆獎", "第 1 區中 5 個", 1_344, "20000"),
			LotteryPrize.fixed("伍獎", "第 1 區中 4 個＋第 2 區", 7_440, "4000"),
			LotteryPrize.fixed("陸獎", "第 1 區中 4 個", 52_080, "800"),
			LotteryPrize.fixed("柒獎", "第 1 區中 3 個＋第 2 區", 99_200, "400"),
			LotteryPrize.fixed("捌獎", "第 1 區中 2 個＋第 2 區", 539_400, "200"),
			LotteryPrize.fixed("玖獎", "第 1 區中 3 個", 694_400, "100"),
			LotteryPrize.fixed("普獎", "第 1 區中 1 個＋第 2 區", 1_208_256, "100"));

	/** 單注獎金超過這個金額才扣稅（含 5,000 元以下免稅） */
	public static final BigDecimal TAX_FREE_LIMIT = new BigDecimal("5000");

	/** 機會中獎所得稅 20%，加上兌領時的印花稅千分之四 */
	public static final BigDecimal TAX_RATE = new BigDecimal("0.204");

	private final String label;
	private final int price;
	private final int drawsPerWeek;
	private final String schedule;
	private final String howTo;
	private final long totalCombinations;
	private final BigDecimal payoutRate;
	private final BigDecimal jackpotMinimum;
	private final List<LotteryPrize> prizes;

	LotteryGame(String label, int price, int drawsPerWeek, String schedule, String howTo, long totalCombinations, String payoutRate,
			String jackpotMinimum, LotteryPrize... prizes) {
		this.label = label;
		this.price = price;
		this.drawsPerWeek = drawsPerWeek;
		this.schedule = schedule;
		this.howTo = howTo;
		this.totalCombinations = totalCombinations;
		this.payoutRate = payoutRate == null ? null : new BigDecimal(payoutRate);
		this.jackpotMinimum = jackpotMinimum == null ? null : new BigDecimal(jackpotMinimum);
		this.prizes = Collections.unmodifiableList(Arrays.asList(prizes));
	}

	public String getLabel() {
		return label;
	}

	/** @return 每注價格（元） */
	public int getPrice() {
		return price;
	}

	/** @return 每週開獎期數 */
	public int getDrawsPerWeek() {
		return drawsPerWeek;
	}

	public String getSchedule() {
		return schedule;
	}

	/** @return 玩法 ex. 從 1～39 選 5 個號碼 */
	public String getHowTo() {
		return howTo;
	}

	/** @return 所有號碼組合數，頭獎機率為 1 / 此值 */
	public long getTotalCombinations() {
		return totalCombinations;
	}

	/** @return 總獎金支出率 ex. 0.56，全部是固定獎金時為 null */
	public BigDecimal getPayoutRate() {
		return payoutRate;
	}

	/** @return 頭獎保底金額，頭獎是固定獎金時為 null */
	public BigDecimal getJackpotMinimum() {
		return jackpotMinimum;
	}

	/** @return 由頭獎到最小獎排列 */
	public List<LotteryPrize> getPrizes() {
		return prizes;
	}

	/** @return 頭獎是否由獎金池分配（金額每期不同） */
	public boolean hasPool() {
		return payoutRate != null;
	}

	@Override
	public String toString() {
		return label;
	}
}
