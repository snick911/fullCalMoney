package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.DcaResult;
import com.fullmoney.bean.DcaTarget;
import com.fullmoney.bean.DcaYear;
import com.fullmoney.service.DcaCalculator;
import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.BadRequestException;
import com.fullmoney.web.Params;

/**
 * 定期定額：基金、ETF、股票每隔固定月數投入，每期金額與年化報酬率都可輸入區間
 *
 * <p>參數：target 標的類型（FUND 基金、ETF、STOCK 股票）、amountMin / amountMax 每期投資金額的區間、
 * interval 每隔幾個月投入一次（1～12）、years 投資年數、rateMin / rateMax 預估年化報酬率的區間(%)，可為負數、
 * buyFee 買入手續費(%)、minFee 每筆最低手續費（元）、sellFee 期末賣出費用(%)，三種費用沒有送出時用標的類型的預設值、
 * round=1 金額四捨五入到整數</p>
 * <p>scenarios 為保守（最低金額、最低報酬率）、中間、樂觀（最高金額、最高報酬率）三種情境，兩個區間都只有一個值時只有中間；
 * 每種情境的 rows 每列為 [年度, 累計投入次數, 累計投入成本, 年底市值, 帳面獲利]。
 * matrix 為每期金額（最低、中間、最高）× 年化報酬率（最低到最高分 5 級）的期末市值與帳面獲利</p>
 */
public final class DcaApi extends ApiEndpoint {

	private static final int MAX_INTERVAL = 12;
	private static final int MAX_YEARS = 80;
	private static final String MIN_RATE = "-90";
	private static final String MAX_RATE = "100";
	private static final String MAX_FEE_PERCENT = "10";
	private static final String MAX_MIN_FEE = "10000";
	/** 年化報酬率區間分成幾段：最低、25%、中間、75%、最高 */
	private static final int RATE_STEPS = 4;
	private static final BigDecimal TWO = BigDecimal.valueOf(2);

	private final DcaCalculator calculator = new DcaCalculator();

	public DcaApi() {
		super("/api/dca");
	}

	@Override
	protected Object respond(Params params) {
		DcaTarget target = params.choice("target", "標的類型", DcaTarget.values(), DcaTarget.ETF);
		int interval = params.integer("interval", "投入間隔", 1, MAX_INTERVAL);
		int years = params.integer("years", "投資年數", 1, MAX_YEARS);
		BigDecimal amountMin = params.decimal("amountMin", "每期投資金額的最低值", "1", Values.MAX_MONEY);
		BigDecimal amountMax = params.decimal("amountMax", "每期投資金額的最高值", "1", Values.MAX_MONEY);
		BigDecimal rateMin = params.decimal("rateMin", "預估年化報酬率的最低值", MIN_RATE, MAX_RATE);
		BigDecimal rateMax = params.decimal("rateMax", "預估年化報酬率的最高值", MIN_RATE, MAX_RATE);
		BigDecimal buyFee = feePercent(params, "buyFee", "買入手續費", MAX_FEE_PERCENT, target.getBuyFeeRate());
		BigDecimal minFee = params.list("minFee").isEmpty() ? target.getMinFee() : params.decimal("minFee", "每筆最低手續費", "0", MAX_MIN_FEE);
		BigDecimal sellFee = feePercent(params, "sellFee", "期末賣出費用", MAX_FEE_PERCENT, target.getSellFeeRate());
		boolean whole = Values.wholeMoney(params);
		if (amountMax.compareTo(amountMin) < 0) {
			throw new BadRequestException("amountMax", "每期投資金額的最高值不可小於最低值。");
		}
		if (rateMax.compareTo(rateMin) < 0) {
			throw new BadRequestException("rateMax", "預估年化報酬率的最高值不可小於最低值。");
		}

		BigDecimal amountMid = amountMin.add(amountMax).divide(TWO);
		BigDecimal rateMid = rateMin.add(rateMax).divide(TWO);
		boolean single = amountMin.compareTo(amountMax) == 0 && rateMin.compareTo(rateMax) == 0;
		Fees fees = new Fees(buyFee.doubleValue(), minFee.doubleValue(), sellFee.doubleValue());

		List<Map<String, Object>> scenarios = new ArrayList<>(3);
		if (!single) {
			scenarios.add(scenario("LOW", "保守", amountMin, rateMin, interval, years, fees, whole));
		}
		scenarios.add(scenario("MID", single ? "試算" : "中間", amountMid, rateMid, interval, years, fees, whole));
		if (!single) {
			scenarios.add(scenario("HIGH", "樂觀", amountMax, rateMax, interval, years, fees, whole));
		}

		int months = years * DcaCalculator.MONTHS_PER_YEAR;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("decimals", Values.moneyDecimals(whole));
		out.put("target", target.name());
		out.put("targetName", target.getLabel());
		out.put("interval", interval);
		out.put("years", years);
		out.put("count", DcaCalculator.count(months, interval));
		out.put("amountMin", Values.money(amountMin, whole));
		out.put("amountMax", Values.money(amountMax, whole));
		out.put("rateMin", Values.ratio(rateMin.movePointLeft(2)));
		out.put("rateMax", Values.ratio(rateMax.movePointLeft(2)));
		out.put("buyFee", Values.ratio(buyFee));
		out.put("minFee", minFee);
		out.put("sellFee", Values.ratio(sellFee));
		out.put("scenarios", scenarios);
		out.put("matrix", matrix(amountMin, amountMid, amountMax, rateMin, rateMax, interval, months, fees, whole));
		return out;
	}

	/** 費用以百分比輸入；沒有送出時用標的類型的預設值，送出空白時要求輸入 */
	private static BigDecimal feePercent(Params params, String name, String label, String max, BigDecimal fallback) {
		return params.list(name).isEmpty() ? fallback : params.decimal(name, label, "0", max).movePointLeft(2);
	}

	private Map<String, Object> scenario(String code, String name, BigDecimal amount, BigDecimal ratePercent, int interval, int years, Fees fees,
			boolean whole) {
		BigDecimal rate = ratePercent.movePointLeft(2);
		DcaResult result = calculator.calculate(amount.doubleValue(), rate.doubleValue(), interval, years, fees.buy, fees.min, fees.sell);
		List<Object[]> rows = new ArrayList<>(years);
		for (DcaYear year : result.getYears()) {
			rows.add(new Object[] { year.getYear(), year.getCount(), money(year.getCost(), whole), money(year.getValue(), whole),
					money(year.getProfit(), whole) });
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("code", code);
		out.put("name", name);
		out.put("amount", Values.money(amount, whole));
		out.put("rate", Values.ratio(rate));
		//每次手續費常只有幾元，固定到小數兩位
		out.put("fee", Values.money(BigDecimal.valueOf(result.getFee())));
		out.put("invested", money(result.getInvested(), whole));
		out.put("fees", money(result.getFees(), whole));
		out.put("cost", money(result.getCost(), whole));
		out.put("value", money(result.getValue(), whole));
		out.put("profit", money(result.getProfit(), whole));
		out.put("totalReturn", ratio(result.getTotalReturn()));
		out.put("sellCost", money(result.getSellCost(), whole));
		out.put("net", money(result.getNet(), whole));
		out.put("irr", ratio(result.getIrr()));
		out.put("rows", rows);
		return out;
	}

	/**
	 * 期末市值對照表：每期金額（最低、中間、最高，相同的只列一次）× 年化報酬率（最低到最高平均分 5 級）
	 *
	 * <p>rateSpread：中間金額下，最高與最低報酬率的期末市值差；amountSpread：中間報酬率下，最高與最低金額的期末市值差</p>
	 */
	private static Map<String, Object> matrix(BigDecimal amountMin, BigDecimal amountMid, BigDecimal amountMax, BigDecimal rateMin, BigDecimal rateMax,
			int interval, int months, Fees fees, boolean whole) {
		List<BigDecimal> amounts = new ArrayList<>(3);
		amounts.add(amountMin);
		if (amountMax.compareTo(amountMin) != 0) {
			amounts.add(amountMid);
			amounts.add(amountMax);
		}
		List<BigDecimal> rates = new ArrayList<>(RATE_STEPS + 1);
		rates.add(rateMin);
		if (rateMax.compareTo(rateMin) != 0) {
			BigDecimal step = rateMax.subtract(rateMin).divide(BigDecimal.valueOf(RATE_STEPS));
			for (int i = 1; i <= RATE_STEPS; i++) {
				rates.add(rateMin.add(step.multiply(BigDecimal.valueOf(i))));
			}
		}

		int count = DcaCalculator.count(months, interval);
		List<Object> amountOut = new ArrayList<>(amounts.size());
		List<Object> costOut = new ArrayList<>(amounts.size());
		double[] costs = new double[amounts.size()];
		for (int a = 0; a < amounts.size(); a++) {
			double amount = amounts.get(a).doubleValue();
			costs[a] = (amount + DcaCalculator.fee(amount, fees.buy, fees.min)) * count;
			amountOut.add(Values.money(amounts.get(a), whole));
			costOut.add(money(costs[a], whole));
		}
		double[][] values = new double[rates.size()][amounts.size()];
		List<Object> rateOut = new ArrayList<>(rates.size());
		List<Object> valueOut = new ArrayList<>(rates.size());
		List<Object> profitOut = new ArrayList<>(rates.size());
		for (int r = 0; r < rates.size(); r++) {
			BigDecimal rate = rates.get(r).movePointLeft(2);
			rateOut.add(Values.ratio(rate));
			List<Object> valueRow = new ArrayList<>(amounts.size());
			List<Object> profitRow = new ArrayList<>(amounts.size());
			for (int a = 0; a < amounts.size(); a++) {
				values[r][a] = DcaCalculator.futureValue(amounts.get(a).doubleValue(), interval, months, rate.doubleValue());
				valueRow.add(money(values[r][a], whole));
				profitRow.add(money(values[r][a] - costs[a], whole));
			}
			valueOut.add(valueRow);
			profitOut.add(profitRow);
		}

		int midAmount = amounts.size() / 2;
		int midRate = rates.size() / 2;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("amounts", amountOut);
		out.put("costs", costOut);
		out.put("rates", rateOut);
		out.put("values", valueOut);
		out.put("profits", profitOut);
		out.put("midAmount", midAmount);
		out.put("midRate", midRate);
		out.put("rateSpread", rates.size() > 1 ? money(values[rates.size() - 1][midAmount] - values[0][midAmount], whole) : null);
		out.put("amountSpread", amounts.size() > 1 ? money(values[midRate][amounts.size() - 1] - values[midRate][0], whole) : null);
		return out;
	}

	private static BigDecimal money(double amount, boolean whole) {
		return Values.money(BigDecimal.valueOf(amount), whole);
	}

	private static BigDecimal ratio(double ratio) {
		return Double.isFinite(ratio) ? Values.ratio(BigDecimal.valueOf(ratio)) : null;
	}

	/** 買入手續費率、每筆最低手續費、期末賣出費用率 */
	private static final class Fees {
		private final double buy;
		private final double min;
		private final double sell;

		Fees(double buy, double min, double sell) {
			this.buy = buy;
			this.min = min;
			this.sell = sell;
		}
	}
}
