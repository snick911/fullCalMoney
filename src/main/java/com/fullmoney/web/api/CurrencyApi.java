package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.CurrencyType;
import com.fullmoney.bean.DepositReturn;
import com.fullmoney.bean.DepositRollover;
import com.fullmoney.bean.TimeDeposit;
import com.fullmoney.bean.TimeDepositPeriod;
import com.fullmoney.bean.TimeDepositResult;
import com.fullmoney.service.CurrencyConverter;
import com.fullmoney.service.TimeDepositCalculator;
import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.BadRequestException;
import com.fullmoney.web.Params;

/**
 * 貨幣投資：幣別轉換（透過新台幣換算），轉換後的金額依序定存，到期本息以預估匯率換回指定貨幣計算總報酬
 *
 * <p>參數：amount 金額、from / to 幣別、fromRate / toRate 1 單位該幣別可換多少新台幣（新台幣固定為 1，不必送出）、
 * round=1 結果四捨五入到整數（不送出時到小數兩位）</p>
 * <p>定存（選填）：depositTerm 存期（月，1～12）與 depositRate 年利率(%) 成對重複送出，依送出順序接續；
 * rollover 續存方式、settle 換回的貨幣（預設同 from）、settleRate 到期預估匯率（1 單位 to 可換多少 settle，settle 與 to 相同時不必送出）、
 * settleNowRate settle 目前 1 單位可換多少新台幣（只有 settle 不是 from、to 或新台幣時需要）</p>
 * <p>deposit.rows 每列為 [第幾筆, 開始月, 到期月, 存期(月), 年利率(%), 本金, 利息, 到期本息, 累計利息]，金額為定存幣別</p>
 */
public final class CurrencyApi extends ApiEndpoint {

	private static final int CROSS_RATE_SCALE = 6;
	/** 輸出匯率的有效位數 */
	private static final MathContext RATE_DIGITS = new MathContext(6, RoundingMode.HALF_UP);
	private static final MathContext PRECISION = MathContext.DECIMAL128;
	private static final int MONEY_SCALE = 2;
	private static final int MAX_DEPOSITS = 120;
	private static final int MAX_TERM = 12;
	/** 兩種外幣直接換算的匯率範圍 ex. 1 日圓 = 0.0065 美元、1 英鎊 = 200 日圓 */
	private static final String MIN_CROSS_RATE = "0.000001";
	private static final String MAX_CROSS_RATE = "1000000";
	/** 匯率變動試算：以目前匯率為準上下變動的比例(%) */
	private static final int[] SCENARIO_CHANGES = { -10, -5, -3, 0, 3, 5, 10 };
	/** 兩個匯率相差不到百萬分之一時視為同一列 */
	private static final BigDecimal SAME_RATE_TOLERANCE = new BigDecimal("0.000001");

	private final CurrencyConverter converter = new CurrencyConverter();
	private final TimeDepositCalculator depositCalculator = new TimeDepositCalculator();

	public CurrencyApi() {
		super("/api/currency");
	}

	@Override
	protected Object respond(Params params) {
		BigDecimal amount = params.decimal("amount", "金額", "0", Values.MAX_MONEY);
		CurrencyType from = params.choice("from", "原幣別", CurrencyType.values());
		CurrencyType to = params.choice("to", "目標幣別", CurrencyType.values());
		BigDecimal fromRate = rate(params, from, "fromRate");
		BigDecimal toRate = rate(params, to, "toRate");
		boolean whole = Values.wholeMoney(params);

		BigDecimal crossRate = converter.crossRate(fromRate, toRate).setScale(CROSS_RATE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
		BigDecimal converted = converter.convert(amount, fromRate, toRate);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("from", from.name());
		out.put("to", to.name());
		out.put("decimals", Values.moneyDecimals(whole));
		out.put("amount", Values.money(amount, whole));
		out.put("result", Values.money(converted, whole));
		out.put("crossRate", crossRate.toPlainString());
		out.put("deposit", deposit(params, amount, converted, from, to, fromRate, toRate, whole));
		return out;
	}

	/**
	 * @return 定存試算，沒有定存時為 null
	 */
	private Map<String, Object> deposit(Params params, BigDecimal amount, BigDecimal principal, CurrencyType from, CurrencyType to, BigDecimal fromRate,
			BigDecimal toRate, boolean whole) {
		List<TimeDeposit> deposits = deposits(params);
		if (deposits.isEmpty()) {
			return null;
		}
		DepositRollover rollover = params.choice("rollover", "續存方式", DepositRollover.values(), DepositRollover.COMPOUND);
		CurrencyType settle = params.choice("settle", "換回貨幣", CurrencyType.values(), from);
		boolean same = settle == to;
		BigDecimal settleNowRate = settleNowRate(params, settle, from, to, fromRate, toRate);
		//目前 1 單位定存幣別可換多少換回貨幣；投入成本是原來的金額依目前匯率換成換回貨幣
		BigDecimal nowRate = same ? BigDecimal.ONE : toRate.divide(settleNowRate, PRECISION);
		BigDecimal estimateRate = same ? BigDecimal.ONE : params.decimal("settleRate", "到期預估匯率", MIN_CROSS_RATE, MAX_CROSS_RATE);
		BigDecimal cost = (settle == from ? amount : amount.multiply(fromRate).divide(settleNowRate, PRECISION)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

		TimeDepositResult result = depositCalculator.calculate(principal, deposits, rollover);
		DepositReturn estimate = depositCalculator.settle(cost, result, estimateRate);
		BigDecimal breakEven = same ? null : depositCalculator.breakEvenRate(cost, result);

		List<Object[]> rows = new ArrayList<>(result.getPeriods().size());
		for (TimeDepositPeriod period : result.getPeriods()) {
			rows.add(new Object[] { period.getIndex(), period.getStartMonth(), period.getEndMonth(), period.getMonths(),
					period.getAnnualRate().movePointRight(2).stripTrailingZeros().toPlainString(), Values.money(period.getPrincipal(), whole),
					Values.money(period.getInterest(), whole), Values.money(period.getMaturity(), whole), Values.money(period.getCumulativeInterest(), whole) });
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("currency", to.name());
		out.put("currencyLabel", to.getLabel());
		out.put("settle", settle.name());
		out.put("settleLabel", settle.getLabel());
		out.put("sameCurrency", same);
		out.put("rollover", rollover.name());
		out.put("rolloverLabel", rollover.getLabel());
		out.put("count", deposits.size());
		out.put("months", result.getMonths());
		out.put("principal", Values.money(result.getPrincipal(), whole));
		out.put("interest", Values.money(result.getTotalInterest(), whole));
		out.put("finalAmount", Values.money(result.getFinalAmount(), whole));
		out.put("cost", Values.money(cost, whole));
		out.put("nowRate", rateText(nowRate));
		out.put("estimateRate", rateText(estimateRate));
		out.put("estimateChange", change(estimateRate, nowRate));
		out.put("breakEvenRate", breakEven == null ? null : rateText(breakEven));
		out.put("breakEvenChange", breakEven == null ? null : change(breakEven, nowRate));
		putReturn(out, estimate, whole);
		out.put("rows", rows);
		out.put("scenarios", same ? new ArrayList<>() : scenarios(cost, result, nowRate, estimateRate, breakEven, whole));
		return out;
	}

	private static List<TimeDeposit> deposits(Params params) {
		List<String> terms = params.list("depositTerm");
		List<String> rates = params.list("depositRate");
		if (terms.size() != rates.size()) {
			throw new BadRequestException(null, "定存的存期與年利率筆數不一致。");
		}
		if (terms.size() > MAX_DEPOSITS) {
			throw new BadRequestException(null, "定存最多 " + MAX_DEPOSITS + " 筆。");
		}
		List<TimeDeposit> deposits = new ArrayList<>(terms.size());
		for (int i = 0; i < terms.size(); i++) {
			String prefix = "第 " + (i + 1) + " 筆定存的";
			int months = Params.toInteger(terms.get(i), "depositTerm:" + i, prefix + "存期", 1, MAX_TERM);
			BigDecimal rate = Params.toDecimal(rates.get(i), "depositRate:" + i, prefix + "年利率", BigDecimal.ZERO, new BigDecimal("100"));
			deposits.add(new TimeDeposit(months, rate.movePointLeft(2)));
		}
		return deposits;
	}

	/**
	 * @return 換回貨幣目前 1 單位可換多少新台幣；與原幣別或定存幣別相同時沿用上方的匯率
	 */
	private static BigDecimal settleNowRate(Params params, CurrencyType settle, CurrencyType from, CurrencyType to, BigDecimal fromRate, BigDecimal toRate) {
		if (settle == from) {
			return fromRate;
		}
		if (settle == to) {
			return toRate;
		}
		if (settle == CurrencyType.TWD) {
			return BigDecimal.ONE;
		}
		return params.decimal("settleNowRate", settle.getLabel() + "目前匯率", Values.MIN_EXCHANGE_RATE, Values.MAX_EXCHANGE_RATE);
	}

	/**
	 * 到期匯率變動試算：目前匯率上下變動數個百分比，再加上預估匯率與損益兩平匯率，由低到高排列；相同匯率合併成一列
	 */
	private List<Map<String, Object>> scenarios(BigDecimal cost, TimeDepositResult result, BigDecimal nowRate, BigDecimal estimateRate, BigDecimal breakEven,
			boolean whole) {
		List<BigDecimal> rates = new ArrayList<>();
		for (int change : SCENARIO_CHANGES) {
			rates.add(nowRate.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(change).movePointLeft(2)), PRECISION));
		}
		rates.add(estimateRate);
		if (breakEven != null && breakEven.signum() > 0) {
			rates.add(breakEven);
		}
		Collections.sort(rates);

		List<Map<String, Object>> scenarios = new ArrayList<>();
		BigDecimal previous = null;
		for (BigDecimal rate : rates) {
			if (previous != null && sameRate(previous, rate)) {
				continue;
			}
			previous = rate;
			DepositReturn settled = depositCalculator.settle(cost, result, rate);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("rate", rateText(rate));
			row.put("change", change(rate, nowRate));
			putReturn(row, settled, whole);
			row.put("now", sameRate(rate, nowRate));
			row.put("estimate", sameRate(rate, estimateRate));
			row.put("breakEven", breakEven != null && sameRate(rate, breakEven));
			scenarios.add(row);
		}
		return scenarios;
	}

	private static void putReturn(Map<String, Object> out, DepositReturn settled, boolean whole) {
		out.put("value", Values.money(settled.getValue(), whole));
		out.put("principalValue", Values.money(settled.getPrincipalValue(), whole));
		out.put("interestValue", Values.money(settled.getInterestValue(), whole));
		out.put("exchangeGain", Values.money(settled.getExchangeGain(), whole));
		out.put("profit", Values.money(settled.getProfit(), whole));
		out.put("totalReturn", Values.ratio(settled.getTotalReturn()));
		out.put("annualReturn", Values.ratio(settled.getAnnualReturn()));
	}

	private static boolean sameRate(BigDecimal a, BigDecimal b) {
		return a.subtract(b).abs().compareTo(b.abs().multiply(SAME_RATE_TOLERANCE)) <= 0;
	}

	/** @return rate 相對 base 的變動比例 ex. 0.05 為升值 5%，base 為 0 時為 null */
	private static BigDecimal change(BigDecimal rate, BigDecimal base) {
		return base.signum() == 0 ? null : Values.ratio(rate.divide(base, PRECISION).subtract(BigDecimal.ONE));
	}

	/** 匯率輸出到 6 位有效數字 ex. 32.1235、0.00645161 */
	private static String rateText(BigDecimal rate) {
		return rate.round(RATE_DIGITS).stripTrailingZeros().toPlainString();
	}

	private static BigDecimal rate(Params params, CurrencyType currency, String name) {
		if (currency == CurrencyType.TWD) {
			return BigDecimal.ONE;
		}
		return params.decimal(name, currency.getLabel() + "匯率", Values.MIN_EXCHANGE_RATE, Values.MAX_EXCHANGE_RATE);
	}
}
