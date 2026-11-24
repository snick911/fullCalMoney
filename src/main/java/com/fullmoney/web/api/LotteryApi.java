package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.LotteryGame;
import com.fullmoney.bean.LotteryPrizeValue;
import com.fullmoney.bean.PurchaseFrequency;
import com.fullmoney.service.LotteryCalculator;
import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.BadRequestException;
import com.fullmoney.web.Params;

/**
 * 樂透投資：定期買彩券的回報率
 *
 * <p>參數：game 彩券（DAILY_CASH 今彩539、LOTTO 大樂透、SUPER_LOTTO 威力彩）、amount 每次買入金額、frequency 買入頻率、
 * years 年數、jackpot 頭獎金額（選填，只用於大樂透與威力彩，空白時以長期平均估算）、compareRate 對照的定期投資年報酬率(%)、
 * round=1 金額四捨五入到整數</p>
 * <p>prizes 每個獎項的中獎機率、獎金與每注期望值；simulation 為 2,000 人照同樣方式買的稅後中獎總額分布；
 * rows 每列為 [年度, 累積投入, 平均拿回（稅後）, 平均虧損, 對照：定期投資的金額]</p>
 */
public final class LotteryApi extends ApiEndpoint {

	static final int SIMULATION_PATHS = 2000;
	/** 固定種子：相同條件每次結果相同，伺服器與 GitHub Pages 也相同 */
	private static final long SIMULATION_SEED = 539L;
	private static final int MAX_YEARS = 80;
	private static final String MAX_JACKPOT = "10000000000";
	private static final double PESSIMISTIC = 0.1;
	private static final double OPTIMISTIC = 0.9;

	private final LotteryCalculator calculator = new LotteryCalculator();

	public LotteryApi() {
		super("/api/lottery");
	}

	@Override
	protected Object respond(Params params) {
		LotteryGame game = params.choice("game", "彩券", LotteryGame.values());
		BigDecimal amount = params.decimal("amount", "每次買入金額", "0", Values.MAX_MONEY);
		PurchaseFrequency frequency = params.choice("frequency", "買入頻率", PurchaseFrequency.values());
		int years = params.integer("years", "年數", 1, MAX_YEARS);
		BigDecimal jackpot = null;
		if (game.hasPool()) {
			//頭獎有保底金額，低於保底的輸入不合理
			jackpot = params.optionalDecimal("jackpot", "頭獎金額", game.getJackpotMinimum().toPlainString(), MAX_JACKPOT, null);
		}
		BigDecimal compareRate = params.decimal("compareRate", "對照的年報酬率", "0", "30").movePointLeft(2);
		boolean whole = Values.wholeMoney(params);

		BigDecimal price = BigDecimal.valueOf(game.getPrice());
		long betsPerPurchase = amount.divide(price, 0, RoundingMode.FLOOR).longValue();
		if (betsPerPurchase < 1) {
			throw new BadRequestException("amount", game.getLabel() + "每注 " + game.getPrice() + " 元，每次買入金額至少要 " + game.getPrice() + " 元。");
		}
		BigDecimal spendPerPurchase = price.multiply(BigDecimal.valueOf(betsPerPurchase));
		int timesPerYear = frequency.timesPerYear(game);
		long totalBets = betsPerPurchase * timesPerYear * years;
		BigDecimal totalSpent = price.multiply(BigDecimal.valueOf(totalBets));

		List<LotteryPrizeValue> values = calculator.prizeValues(game, jackpot);
		BigDecimal perBet = calculator.expectedPerBet(game, values);
		BigDecimal perBetAfterTax = calculator.expectedPerBetAfterTax(values);
		BigDecimal expectedBack = perBetAfterTax.multiply(BigDecimal.valueOf(totalBets));

		double anyProbability = 0;
		List<Map<String, Object>> prizes = new ArrayList<>(values.size());
		for (LotteryPrizeValue value : values) {
			anyProbability += value.getProbability();
			Map<String, Object> prize = new LinkedHashMap<>();
			prize.put("name", value.getPrize().getName());
			prize.put("rule", value.getPrize().getRule());
			prize.put("odds", odds(game.getTotalCombinations(), value.getPrize().getCombinations()));
			prize.put("amount", Values.money(value.getAmount(), whole));
			prize.put("afterTax", Values.money(value.getAfterTax(), whole));
			prize.put("estimated", value.isEstimated());
			prize.put("taxed", value.isTaxed());
			prize.put("expectedValue", value.getExpectedValue().setScale(4, RoundingMode.HALF_UP));
			prize.put("share", Values.ratio(perBetAfterTax.signum() == 0 ? null
					: value.getExpectedValue().divide(perBetAfterTax, 10, RoundingMode.HALF_UP)));
			prize.put("expectedWins", value.getProbability() * totalBets);
			prize.put("chance", LotteryCalculator.atLeastOnce(value.getProbability(), totalBets));
			//只靠這個獎回本要中幾個；比總注數還多時不可能（每注獎金不到每注價格）
			long breakEven = LotteryCalculator.winsToBreakEven(totalSpent, value.getAfterTax());
			prize.put("breakEvenWins", breakEven);
			prize.put("breakEvenPossible", breakEven <= totalBets);
			prize.put("breakEvenChance", tiny(LotteryCalculator.atLeast(totalBets, value.getProbability(), breakEven)));
			prizes.add(prize);
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("decimals", Values.moneyDecimals(whole));
		out.put("game", game.name());
		out.put("gameName", game.getLabel());
		out.put("price", game.getPrice());
		out.put("frequency", frequency.getLabel());
		out.put("years", years);
		out.put("betsPerPurchase", betsPerPurchase);
		out.put("spendPerPurchase", Values.money(spendPerPurchase, whole));
		out.put("timesPerYear", timesPerYear);
		out.put("totalBets", totalBets);
		out.put("totalSpent", Values.money(totalSpent, whole));
		out.put("perBet", perBet.setScale(4, RoundingMode.HALF_UP));
		out.put("perBetAfterTax", perBetAfterTax.setScale(4, RoundingMode.HALF_UP));
		out.put("returnRate", Values.ratio(perBet.divide(price, 12, RoundingMode.HALF_UP)));
		out.put("returnRateAfterTax", Values.ratio(perBetAfterTax.divide(price, 12, RoundingMode.HALF_UP)));
		out.put("expectedBack", Values.money(expectedBack, whole));
		out.put("expectedLoss", Values.money(totalSpent.subtract(expectedBack), whole));
		out.put("payoutRate", Values.ratio(game.getPayoutRate()));
		out.put("jackpotInput", jackpot != null);
		out.put("jackpotAverage", Values.money(calculator.averageJackpot(game), whole));
		out.put("anyPrizeOdds", BigDecimal.valueOf(1 / anyProbability).setScale(1, RoundingMode.HALF_UP));
		out.put("expectedPrizeCount", anyProbability * totalBets);
		out.put("anyPrizeChance", LotteryCalculator.atLeastOnce(anyProbability, totalBets));
		out.put("prizes", prizes);
		out.put("simulation", simulation(values, totalBets, totalSpent, whole));
		out.put("compareRate", Values.ratio(compareRate));
		out.put("rows", rows(perBetAfterTax, betsPerPurchase, spendPerPurchase, price, timesPerYear, years, compareRate, whole));
		return out;
	}

	/** 2,000 人照同樣的方式買：稅後中獎總額的悲觀（最差 10%）、中位數、樂觀（最好 10%），以及沒有虧損的比例 */
	private Map<String, Object> simulation(List<LotteryPrizeValue> values, long totalBets, BigDecimal totalSpent, boolean whole) {
		double[] winnings = calculator.simulate(values, totalBets, SIMULATION_PATHS, SIMULATION_SEED);
		double spent = totalSpent.doubleValue();
		int breakEven = 0;
		double sum = 0;
		for (double winning : winnings) {
			sum += winning;
			if (winning >= spent) {
				breakEven++;
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("paths", winnings.length);
		out.put("pessimistic", money(LotteryCalculator.percentile(winnings, PESSIMISTIC), whole));
		out.put("median", money(LotteryCalculator.percentile(winnings, 0.5), whole));
		out.put("optimistic", money(LotteryCalculator.percentile(winnings, OPTIMISTIC), whole));
		out.put("pessimisticRate", ratio(LotteryCalculator.percentile(winnings, PESSIMISTIC), spent));
		out.put("medianRate", ratio(LotteryCalculator.percentile(winnings, 0.5), spent));
		out.put("optimisticRate", ratio(LotteryCalculator.percentile(winnings, OPTIMISTIC), spent));
		out.put("meanRate", ratio(sum / winnings.length, spent));
		out.put("breakEvenChance", (double) breakEven / winnings.length);
		return out;
	}

	private static List<Object[]> rows(BigDecimal perBetAfterTax, long betsPerPurchase, BigDecimal spendPerPurchase, BigDecimal price, int timesPerYear,
			int years, BigDecimal compareRate, boolean whole) {
		List<Object[]> rows = new ArrayList<>(years);
		double each = spendPerPurchase.doubleValue();
		double rate = compareRate.doubleValue();
		for (int year = 1; year <= years; year++) {
			long bets = betsPerPurchase * timesPerYear * year;
			BigDecimal spent = price.multiply(BigDecimal.valueOf(bets));
			BigDecimal back = perBetAfterTax.multiply(BigDecimal.valueOf(bets));
			double invested = LotteryCalculator.investedValue(each, timesPerYear, (double) timesPerYear * year, rate);
			rows.add(new Object[] { year, Values.money(spent, whole), Values.money(back, whole), Values.money(spent.subtract(back), whole),
					money(invested, whole) });
		}
		return rows;
	}

	/** @return 中獎機率的分母 ex. 頭獎 575,757（1/575,757），小於 100 時到小數一位 */
	private static BigDecimal odds(long total, long combinations) {
		BigDecimal value = BigDecimal.valueOf(total).divide(BigDecimal.valueOf(combinations), 1, RoundingMode.HALF_UP);
		return value.compareTo(BigDecimal.valueOf(100)) >= 0 ? value.setScale(0, RoundingMode.HALF_UP) : value.stripTrailingZeros();
	}

	/** 小於千兆分之一的機率輸出為 0，避免 JSON 出現幾百位小數 */
	private static double tiny(double probability) {
		return probability < 1e-15 ? 0 : probability;
	}

	private static BigDecimal money(double amount, boolean whole) {
		return Values.money(BigDecimal.valueOf(amount), whole);
	}

	private static BigDecimal ratio(double amount, double total) {
		return total <= 0 ? null : Values.ratio(BigDecimal.valueOf(amount / total));
	}
}
