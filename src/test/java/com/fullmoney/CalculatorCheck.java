package com.fullmoney;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.AllocationProfile;
import com.fullmoney.bean.BondCondition;
import com.fullmoney.bean.BondResult;
import com.fullmoney.bean.CouponFrequency;
import com.fullmoney.bean.DepositReturn;
import com.fullmoney.bean.DepositRollover;
import com.fullmoney.bean.GlidePath;
import com.fullmoney.bean.InvestmentPlan;
import com.fullmoney.bean.InvestmentTier;
import com.fullmoney.bean.LotteryGame;
import com.fullmoney.bean.LotteryPrize;
import com.fullmoney.bean.MortgagePrepayment;
import com.fullmoney.bean.PlanSettings;
import com.fullmoney.bean.MortgageRateChange;
import com.fullmoney.bean.MortgageResult;
import com.fullmoney.bean.PrepaymentMode;
import com.fullmoney.bean.PurchaseFrequency;
import com.fullmoney.bean.RebalanceMode;
import com.fullmoney.bean.RepaymentMethod;
import com.fullmoney.bean.ReturnDistribution;
import com.fullmoney.bean.SaveMoney;
import com.fullmoney.bean.StressScenario;
import com.fullmoney.bean.StressTiming;
import com.fullmoney.bean.TimeDeposit;
import com.fullmoney.bean.TimeDepositResult;
import com.fullmoney.service.Allocation;
import com.fullmoney.service.BondCalculator;
import com.fullmoney.service.CashFlows;
import com.fullmoney.service.CurrencyConverter;
import com.fullmoney.service.FloatingMoneyCalculator;
import com.fullmoney.service.InvestmentPlanner;
import com.fullmoney.service.LotteryCalculator;
import com.fullmoney.service.MarketSimulator;
import com.fullmoney.service.RangeRandom;
import com.fullmoney.service.MoneyCalculator;
import com.fullmoney.service.MortgageCalculator;
import com.fullmoney.service.TimeDepositCalculator;
import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.ApiResponse;
import com.fullmoney.web.BadRequestException;
import com.fullmoney.web.Json;
import com.fullmoney.web.Numbers;
import com.fullmoney.web.Params;
import com.fullmoney.web.WebServer;
import com.fullmoney.web.api.ApiEndpoints;

/**
 * 不依賴測試框架的自我檢查：試算結果須與桌面版文件記載的數值相同，並實際呼叫一次 HTTP API
 *
 * <p>執行：test.bat，全部通過時結束代碼為 0</p>
 */
public final class CalculatorCheck {

	private static int checks;
	private static int failures;

	private CalculatorCheck() {
	}

	public static void main(String[] args) throws Exception {
		compound();
		floating();
		mortgage();
		bond();
		currency();
		lottery();
		params();
		json();
		endpoints();
		http();
		System.out.println();
		System.out.println(failures == 0 ? "ALL " + checks + " CHECKS PASSED" : failures + " OF " + checks + " CHECKS FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void compound() {
		MoneyCalculator calculator = new MoneyCalculator();
		BigDecimal rate = new BigDecimal("0.04");
		var result = calculator.calculate(BigDecimal.ZERO, new BigDecimal("200000"), rate, new BigDecimal("10000"), 20);
		check("compound wealth", "8736140.77", result.getTotalSumAmount().setScale(2, RoundingMode.HALF_EVEN).toPlainString());

		//原始本金 100 萬只在第 1 年投入：100 萬 × 1.04^20 + 上面的 8,736,140.77
		var initial = calculator.calculate(new BigDecimal("1000000"), new BigDecimal("200000"), rate, new BigDecimal("10000"), 20);
		check("compound initial wealth", "10927263.92", initial.getTotalSumAmount().setScale(2, RoundingMode.HALF_EVEN).toPlainString());
		check("compound initial base", "6900000", initial.getTotalBaseAmount().toPlainString());
		check("compound year 1 amount", "1200000", initial.getYearList().get(0).getAmount().toPlainString());
		check("compound year 2 amount", "210000", initial.getYearList().get(1).getAmount().toPlainString());
	}

	private static void floating() {
		FloatingMoneyCalculator calculator = new FloatingMoneyCalculator();
		BigDecimal base = new BigDecimal("200000");
		//區間上下限相同時與固定的複利試算相同，與種子無關
		var fixed = calculator.calculate(BigDecimal.ZERO, base, new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("0.04"),
				new BigDecimal("0.04"), 20, 987654321);
		check("floating fixed range = compound", "8736140.77", fixed.getTotalSumAmount().setScale(2, RoundingMode.HALF_EVEN).toPlainString());

		BigDecimal addMin = new BigDecimal("5000");
		BigDecimal addMax = new BigDecimal("15000");
		BigDecimal rateMin = new BigDecimal("-0.03");
		BigDecimal rateMax = new BigDecimal("0.08");
		var first = calculator.calculate(BigDecimal.ZERO, base, addMin, addMax, rateMin, rateMax, 100, 42);
		var again = calculator.calculate(BigDecimal.ZERO, base, addMin, addMax, rateMin, rateMax, 100, 42);
		var other = calculator.calculate(BigDecimal.ZERO, base, addMin, addMax, rateMin, rateMax, 100, 43);
		check("floating same seed", first.getTotalSumAmount().toPlainString(), again.getTotalSumAmount().toPlainString());
		check("floating other seed differs", true, first.getTotalSumAmount().compareTo(other.getTotalSumAmount()) != 0);

		boolean inRange = true;
		boolean hitLow = false;
		boolean hitHigh = false;
		for (SaveMoney year : first.getYearList()) {
			BigDecimal rate = year.getInterestRate();
			BigDecimal add = year.getAddAmount();
			inRange &= rate.compareTo(rateMin) >= 0 && rate.compareTo(rateMax) <= 0 && rate.scale() <= 4;
			hitLow |= rate.signum() < 0;
			hitHigh |= rate.compareTo(new BigDecimal("0.06")) > 0;
			if (year.getTheYear() == 1) {
				inRange &= add.signum() == 0;
			} else {
				inRange &= add.compareTo(addMin) >= 0 && add.compareTo(addMax) <= 0 && add.scale() <= 0;
			}
		}
		check("floating values in range", true, inRange);
		check("floating values spread", true, hitLow && hitHigh);
	}

	private static void mortgage() {
		MortgageCalculator calculator = new MortgageCalculator();
		BigDecimal loan = new BigDecimal("10000000");
		BigDecimal rate = new BigDecimal("0.022");
		MortgageResult base = calculator.calculate(loan, rate, 30, 0, RepaymentMethod.EQUAL_PAYMENT);
		check("mortgage payment", "37970", base.getFirstPayment().toPlainString());
		check("mortgage interest", "3669232", base.getTotalInterest().toPlainString());

		List<MortgageRateChange> change = List.of(new MortgageRateChange(25, new BigDecimal("0.025")));
		MortgageResult changed = calculator.calculate(loan, rate, 30, 0, RepaymentMethod.EQUAL_PAYMENT, change, Collections.emptyList(),
				PrepaymentMode.SHORTEN_TERM);
		check("mortgage rate change interest", "4156549", changed.getTotalInterest().toPlainString());

		List<MortgagePrepayment> prepay = List.of(new MortgagePrepayment(25, new BigDecimal("1000000")));
		MortgageResult shorten = calculator.calculate(loan, rate, 30, 0, RepaymentMethod.EQUAL_PAYMENT, Collections.emptyList(), prepay,
				PrepaymentMode.SHORTEN_TERM);
		check("mortgage shorten periods (26y2m)", "314", String.valueOf(shorten.getPayments().size()));
		check("mortgage shorten saved", "770918", base.getTotalInterest().subtract(shorten.getTotalInterest()).toPlainString());

		MortgageResult reduce = calculator.calculate(loan, rate, 30, 0, RepaymentMethod.EQUAL_PAYMENT, Collections.emptyList(), prepay,
				PrepaymentMode.REDUCE_PAYMENT);
		check("mortgage reduce periods", "360", String.valueOf(reduce.getPayments().size()));
		check("mortgage reduce payment", "33972", reduce.getPayments().get(25).getPayment().toPlainString());
		check("mortgage reduce saved", "339153", base.getTotalInterest().subtract(reduce.getTotalInterest()).toPlainString());
	}

	private static void bond() {
		BondCalculator calculator = new BondCalculator();
		BondResult same = calculator.calculate(bondCondition("32", "32", "32"));
		check("bond cost", "9850.00", same.getBondReturn().getCost().toPlainString());
		check("bond coupon total", "4500.00", same.getBondReturn().getCouponTotal().toPlainString());
		check("bond total return", "47.21%", percent(same.getBondReturn().getTotalReturn()));
		check("bond YTM", "4.69%", percent(same.getYieldToMaturity()));
		check("bond annual return", "4.74%", percent(same.getBondReturn().getAnnualReturn()));

		BondResult moved = calculator.calculate(bondCondition("32", "31", "30"));
		check("bond home annual return", "4.07%", percent(moved.getHomeReturn().getAnnualReturn()));
		check("bond exchange gain", "-24500.00", moved.getExchangeGain().toPlainString());
	}

	private static BondCondition bondCondition(String buyRate, String couponRate, String redemptionRate) {
		return new BondCondition(new BigDecimal("10000"), new BigDecimal("0.985"), new BigDecimal("1.00"), new BigDecimal("0.045"),
				CouponFrequency.SEMI_ANNUAL, 10, BigDecimal.ZERO, BigDecimal.ZERO,
				new BigDecimal(buyRate), new BigDecimal(couponRate), new BigDecimal(redemptionRate));
	}

	private static void currency() {
		check("currency 1000 USD", "32000.00", new CurrencyConverter().convert(new BigDecimal("1000"), new BigDecimal("32"), BigDecimal.ONE).toPlainString());
		deposit();
	}

	/**
	 * 依序定存：USD 31,250 先存 3 個月 4%、再存 9 個月 3.6%
	 * 本息續存：312.50 + 31,562.50 × 3.6% × 9 ÷ 12 = 852.19 → 利息 1,164.69；本金續存：312.50 + 843.75 = 1,156.25
	 */
	private static void deposit() {
		TimeDepositCalculator calculator = new TimeDepositCalculator();
		BigDecimal principal = new BigDecimal("31250.00");
		List<TimeDeposit> deposits = List.of(new TimeDeposit(3, new BigDecimal("0.04")), new TimeDeposit(9, new BigDecimal("0.036")));
		TimeDepositResult compound = calculator.calculate(principal, deposits, DepositRollover.COMPOUND);
		check("deposit first interest", "312.50", compound.getPeriods().get(0).getInterest().toPlainString());
		check("deposit second principal", "31562.50", compound.getPeriods().get(1).getPrincipal().toPlainString());
		check("deposit compound interest", "1164.69", compound.getTotalInterest().toPlainString());
		check("deposit months", 12, compound.getMonths());
		check("deposit second span", "4-12", compound.getPeriods().get(1).getStartMonth() + "-" + compound.getPeriods().get(1).getEndMonth());
		TimeDepositResult simple = calculator.calculate(principal, deposits, DepositRollover.PRINCIPAL);
		check("deposit principal-only interest", "1156.25", simple.getTotalInterest().toPlainString());

		//新台幣 100 萬以 32 換成 USD 31,250，到期以 31 換回：本金 968,750 + 利息 36,105.39
		DepositReturn settled = calculator.settle(new BigDecimal("1000000.00"), compound, new BigDecimal("31"));
		check("deposit value", "1004855.39", settled.getValue().toPlainString());
		check("deposit exchange gain", "-31250.00", settled.getExchangeGain().toPlainString());
		check("deposit profit", "4855.39", settled.getProfit().toPlainString());
		check("deposit total return", "0.49%", percent(settled.getTotalReturn()));
		check("deposit annual = total for 12 months", percent(settled.getTotalReturn()), percent(settled.getAnnualReturn()));
		check("deposit break-even", "30.8502", calculator.breakEvenRate(new BigDecimal("1000000.00"), compound).setScale(4, RoundingMode.HALF_UP).toPlainString());
		//半年賺 2%：年化 1.02² − 1 = 4.04%
		TimeDepositResult half = calculator.calculate(new BigDecimal("100"), List.of(new TimeDeposit(6, new BigDecimal("0.04"))), DepositRollover.COMPOUND);
		check("deposit annualized half year", "4.04%", percent(calculator.settle(new BigDecimal("100"), half, BigDecimal.ONE).getAnnualReturn()));

		String api = call("/api/currency", "amount=1%2C000%2C000&from=TWD&to=USD&toRate=32&depositTerm=3&depositRate=4&depositTerm=9&depositRate=3.6"
				+ "&rollover=COMPOUND&settle=TWD&settleRate=31");
		check("deposit api interest", true, api.contains("\"interest\":1164.69,"));
		check("deposit api value", true, api.contains("\"value\":1004855.39,"));
		check("deposit api rates", true, api.contains("\"nowRate\":\"32\",\"estimateRate\":\"31\",") && api.contains("\"breakEvenRate\":\"30.8502\""));
		check("deposit api row", true, api.contains("[2,4,12,9,\"3.6\",31562.50,852.19,32414.69,1164.69]"));
		//目前匯率 ±3、5、10% 共 7 列，加上預估與損益兩平
		check("deposit api scenarios", 9, api.split("\"rate\":").length - 1);
		check("deposit api estimate once", 1, api.split("\"estimate\":true").length - 1);

		//換回定存幣別：不換匯，報酬只有利息
		String same = call("/api/currency", "amount=1000000&from=TWD&to=USD&toRate=32&depositTerm=3&depositRate=4&depositTerm=9&depositRate=3.6&settle=USD");
		check("deposit same currency", true, same.contains("\"sameCurrency\":true") && same.contains("\"profit\":1164.69,") && same.contains("\"scenarios\":[]"));
		//換回第三種貨幣需要它的目前匯率
		String missing = call("/api/currency", "amount=1000&from=TWD&to=USD&toRate=32&depositTerm=12&depositRate=4&settle=EUR&settleRate=0.9");
		check("deposit settle now required", true, missing.contains("\"field\":\"settleNowRate\""));
		String euro = call("/api/currency", "amount=1000&from=TWD&to=USD&toRate=32&depositTerm=12&depositRate=4&settle=EUR&settleNowRate=35&settleRate=0.9");
		check("deposit third currency cost", true, euro.contains("\"cost\":28.57,") && euro.contains("\"nowRate\":\"0.914286\""));
		String badTerm = call("/api/currency", "amount=1000&from=TWD&to=USD&toRate=32&depositTerm=12&depositRate=4&depositTerm=13&depositRate=4&settleRate=31");
		check("deposit bad term field", true, badTerm.contains("\"field\":\"depositTerm:1\""));
		String none = call("/api/currency", "amount=1000&from=USD&fromRate=32&to=TWD");
		check("deposit none", true, none.contains("\"deposit\":null"));
	}

	private static void lottery() {
		//各獎項的中獎組合數要與排列組合公式相同，打錯一個數字回報率就會錯
		check("lottery 539 combinations", "1 170 5610 59840 / 575757", combinations(LotteryGame.DAILY_CASH));
		check("lottery 539 formula", combinations(LotteryGame.DAILY_CASH), choose(5, 5) + " " + choose(5, 4) * choose(34, 1) + " "
				+ choose(5, 3) * choose(34, 2) + " " + choose(5, 2) * choose(34, 3) + " / " + choose(39, 5));
		check("lotto formula", combinations(LotteryGame.LOTTO), "1 " + choose(6, 5) + " " + choose(6, 5) * 42 + " " + choose(6, 4) * 42 + " "
				+ choose(6, 4) * choose(42, 2) + " " + choose(6, 3) * choose(42, 2) + " " + choose(6, 2) * choose(42, 3) + " "
				+ choose(6, 3) * choose(42, 3) + " / " + choose(49, 6));
		long first = choose(6, 6);
		check("super lotto formula", combinations(LotteryGame.SUPER_LOTTO), first + " " + first * 7 + " " + choose(6, 5) * 32 + " " + choose(6, 5) * 32 * 7
				+ " " + choose(6, 4) * choose(32, 2) + " " + choose(6, 4) * choose(32, 2) * 7 + " " + choose(6, 3) * choose(32, 3) + " "
				+ choose(6, 2) * choose(32, 4) + " " + choose(6, 3) * choose(32, 3) * 7 + " " + choose(6, 1) * choose(32, 5) + " / " + choose(38, 6) * 8);

		//今彩539 全部固定獎金：(800 萬 + 170 × 2 萬 + 5,610 × 300 + 59,840 × 50) ÷ 575,757 = 27.92 元
		String daily = lottery("game=DAILY_CASH&amount=230&frequency=EVERY_DRAW&years=10&compareRate=7&round=1&jackpot=1");
		check("lottery 539 per bet", true, daily.contains("\"perBet\":27.9198,"));
		check("lottery 539 return", "55.84% 47.76%", rates(daily));
		check("lottery 539 bets floor", true, daily.contains("\"betsPerPurchase\":4,\"spendPerPurchase\":200,\"timesPerYear\":312,\"totalBets\":12480,"));
		check("lottery 539 ignores jackpot", true, daily.contains("\"jackpotInput\":false"));
		check("lottery 539 jackpot taxed", true, daily.contains("\"amount\":8000000,\"afterTax\":6368000,"));
		check("lottery 539 small prize untaxed", true, daily.contains("\"amount\":300,\"afterTax\":300,\"estimated\":false,\"taxed\":false"));
		//中幾個才回本：投入 624,000 ÷ 稅後獎金（6,368,000、15,920、300、50）無條件進位；肆獎 50 元要每一注都中
		check("lottery 539 break-even wins", "1 40 2080 12480", breakEvenWins(daily));
		check("lottery 539 break-even possible", true, !daily.contains("\"breakEvenPossible\":false"));
		check("lottery 539 break-even jackpot = at least once", true,
				number(daily, "\"breakEvenChance\":") == number(daily, "\"chance\":"));

		//大樂透、威力彩：稅前回報率等於總獎金支出率（獎金池獎項以長期平均估算）
		String lotto = lottery("game=LOTTO&amount=200&frequency=EVERY_DRAW&years=10&compareRate=7&round=1");
		check("lotto return", "56.00% 50.52%", rates(lotto));
		check("lotto jackpot average", true, lotto.contains("\"jackpotAverage\":153977015,"));
		check("lotto any prize odds", true, lotto.contains("\"anyPrizeOdds\":32.3,"));
		check("lotto simulation pinned", true, lotto.contains("\"median\":62600,"));
		check("lotto break-even wins", "1 1 6 20 104 208 520 520", breakEvenWins(lotto));
		//參獎要中 6 個：卜瓦松（平均 0.075 次）至少 6 次約 2.31 × 10⁻¹⁰
		check("lotto break-even third prize chance", true, lotto.contains("\"breakEvenWins\":6,\"breakEvenPossible\":true,\"breakEvenChance\":0.000000000231"));
		check("lotto simulation reproducible", lotto, lottery("game=LOTTO&amount=200&frequency=EVERY_DRAW&years=10&compareRate=7&round=1"));
		String superLotto = lottery("game=SUPER_LOTTO&amount=100&frequency=MONTHLY&years=1&compareRate=7&round=1");
		check("super lotto return", "55.00% 47.56%", rates(superLotto));
		check("super lotto any prize odds", true, superLotto.contains("\"anyPrizeOdds\":8.5,"));
		check("super lotto compare", true, superLotto.contains("\"rows\":[[1,1200,571,629,1245]]"));
		String input = lottery("game=SUPER_LOTTO&amount=100&frequency=WEEKLY&years=1&compareRate=7&round=1&jackpot=1%2C000%2C000%2C000");
		check("super lotto jackpot input", true, input.contains("\"jackpotInput\":true") && input.contains("\"amount\":1000000000,\"afterTax\":796000000,\"estimated\":false"));

		//買很多注時（常態近似）模擬的平均回報率應接近期望值
		String huge = lottery("game=LOTTO&amount=1%2C000%2C000%2C000&frequency=EVERY_DRAW&years=80&compareRate=7");
		double expected = number(huge, "\"returnRateAfterTax\":");
		double simulated = number(huge, "\"meanRate\":");
		check("lottery simulation mean = expected", true, Math.abs(simulated - expected) < 0.002);

		check("lottery amount below price", true, lottery("game=SUPER_LOTTO&amount=99&frequency=WEEKLY&years=1&compareRate=7").contains("\"field\":\"amount\""));
		check("lottery jackpot below minimum", true,
				lottery("game=LOTTO&amount=100&frequency=WEEKLY&years=1&compareRate=7&jackpot=99%2C999%2C999").contains("\"field\":\"jackpot\""));
		check("lottery game required", true, lottery("amount=100&frequency=WEEKLY&years=1&compareRate=7").contains("\"field\":\"game\""));

		check("lottery at least once", "0.000297443", BigDecimal.valueOf(LotteryCalculator.atLeastOnce(1.0 / 13_983_816, 4160)).round(new java.math.MathContext(6))
				.toPlainString());
		check("lottery invested no return", true, LotteryCalculator.investedValue(100, 12, 24, 0) == 2400);
		check("lottery invested yearly", true, Math.abs(LotteryCalculator.investedValue(100, 1, 1, 0.07) - 107) < 1e-9);
		check("lottery every draw 539", 312, PurchaseFrequency.EVERY_DRAW.timesPerYear(LotteryGame.DAILY_CASH));

		//至少 k 次的機率：精確二項、卜瓦松近似、常態近似與邊界
		check("binomial tail exact", true, Math.abs(LotteryCalculator.atLeast(10, 0.5, 8) - 56.0 / 1024) < 1e-12);
		check("binomial tail lower side", true, Math.abs(LotteryCalculator.atLeast(10, 0.5, 3) - (1 - 56.0 / 1024)) < 1e-12);
		check("binomial tail poisson", true, Math.abs(LotteryCalculator.atLeast(1e6, 1e-6, 2) - (1 - 2 / Math.E)) < 1e-6);
		check("binomial tail normal", true, Math.abs(LotteryCalculator.atLeast(1e12, 0.5, 5e11) - 0.5) < 1e-3);
		check("binomial tail bounds", "1.0 0.0 1.0", LotteryCalculator.atLeast(5, 0.1, 0) + " " + LotteryCalculator.atLeast(5, 0.1, 6) + " "
				+ LotteryCalculator.atLeast(5, 1, 5));
		check("lottery break-even ceiling", 3, (int) LotteryCalculator.winsToBreakEven(new BigDecimal("1001"), new BigDecimal("500")));
	}

	/** 各獎項回本要中幾個，以空白分隔 */
	private static String breakEvenWins(String json) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"breakEvenWins\":(\\d+)").matcher(json);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			out.append(out.length() == 0 ? "" : " ").append(matcher.group(1));
		}
		return out.toString();
	}

	private static String lottery(String query) {
		return call("/api/lottery", query);
	}

	/** 一種彩券各獎項的中獎組合數 ex. 1 170 5610 59840 / 575757 */
	private static String combinations(LotteryGame game) {
		StringBuilder out = new StringBuilder();
		for (LotteryPrize prize : game.getPrizes()) {
			out.append(out.length() == 0 ? "" : " ").append(prize.getCombinations());
		}
		return out.append(" / ").append(game.getTotalCombinations()).toString();
	}

	private static long choose(int n, int k) {
		long result = 1;
		for (int i = 1; i <= k; i++) {
			result = result * (n - k + i) / i;
		}
		return result;
	}

	/** 稅前與稅後回報率 ex. 56.00% 50.52% */
	private static String rates(String json) {
		return percent(BigDecimal.valueOf(number(json, "\"returnRate\":"))) + " " + percent(BigDecimal.valueOf(number(json, "\"returnRateAfterTax\":")));
	}

	/** JSON 中 key 後面的數字 */
	private static double number(String json, String key) {
		int start = json.indexOf(key) + key.length();
		int end = start;
		while (end < json.length() && "-0123456789.E".indexOf(json.charAt(end)) >= 0) {
			end++;
		}
		return Double.parseDouble(json.substring(start, end));
	}

	private static void params() {
		check("params full-width and commas", "12345",
				Params.toDecimal("１２,３４５", "x", "x", BigDecimal.ZERO, BigDecimal.TEN.pow(9)).toPlainString());
		rejects("params exponent", () -> Params.toDecimal("1e-999999999", "x", "x", BigDecimal.ZERO, BigDecimal.ONE));
		rejects("params below min", () -> Params.toDecimal("-1", "x", "x", BigDecimal.ZERO, BigDecimal.ONE));
		rejects("params too many digits", () -> Params.toDecimal("12345678901234", "x", "x", BigDecimal.ZERO, BigDecimal.TEN.pow(20)));
		rejects("params integer decimal", () -> Params.toInteger("1.5", "x", "x", 0, 10));
		check("params repeated keys", "[13, 25]", Params.parse("p=13&p=25&q=%E5%B9%B4").list("p").toString());
		Params flags = Params.parse("a=1&b=0&c=on");
		check("params flag", "true false true false", flags.flag("a") + " " + flags.flag("b") + " " + flags.flag("c") + " " + flags.flag("d"));
	}

	private static void json() {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("a", "x\"</script>");
		value.put("b", new Object[] { 1, null, new BigDecimal("2.50") });
		check("json escaping", "{\"a\":\"x\\\"\\u003c/script\\u003e\",\"b\":[1,null,2.50]}", Json.write(value));
	}

	/** 瀏覽器端引擎（GitHub Pages）與伺服器共用的路徑 */
	private static void endpoints() {
		ApiEndpoint mortgage = ApiEndpoints.all().stream().filter(e -> e.getPath().equals("/api/mortgage")).findFirst().orElseThrow();
		ApiResponse ok = mortgage.execute("loanAmount=10%2C000%2C000&rate=2.2&years=30&graceYears=0&method=EQUAL_PAYMENT");
		check("endpoint status", "200", String.valueOf(ok.getStatus()));
		check("endpoint payment", true, Json.write(ok.getBody()).contains("\"firstPayment\":37970"));
		ApiResponse bad = mortgage.execute("loanAmount=1&rate=2.2&years=30&graceYears=0&method=NOPE");
		check("endpoint bad choice", "400", String.valueOf(bad.getStatus()));
		check("endpoint bad choice field", true, Json.write(bad.getBody()).contains("\"field\":\"method\""));

		String compoundQuery = "initialAmount=1%2C000%2C000&baseAmount=200%2C000&rate=4&addAmount=10%2C000&years=20";
		String cents = compound(compoundQuery);
		check("endpoint compound cents", true, cents.contains("\"decimals\":2,") && cents.contains("\"totalSumAmount\":10927263.92,"));
		String whole = compound(compoundQuery + "&round=1");
		check("endpoint compound whole", true, whole.contains("\"decimals\":0,") && whole.contains("\"totalSumAmount\":10927264,"));
		//四捨五入要從完整數值計算：2.495 到整數是 2，不能先變成 2.50 再進位成 3
		check("endpoint round from full precision", true, compound("initialAmount=0&baseAmount=2.495&rate=0&addAmount=0&years=1&round=1")
				.contains("\"totalSumAmount\":2,"));
		check("endpoint round half up", true, compound("initialAmount=0&baseAmount=2.5&rate=0&addAmount=0&years=1&round=1")
				.contains("\"totalSumAmount\":3,"));
		check("endpoint compound initial required", true, compound("baseAmount=1&rate=0&addAmount=0&years=1").contains("\"field\":\"initialAmount\""));

		String floatingQuery = "initialAmount=0&baseAmount=200%2C000&addMin=5%2C000&addMax=15%2C000&rateMin=2&rateMax=6&years=20&round=1";
		String seeded = call("/api/floating", floatingQuery + "&seed=123456789");
		check("endpoint floating seed echoed", true, seeded.contains("\"seed\":123456789,"));
		check("endpoint floating reproducible", seeded, call("/api/floating", floatingQuery + "&seed=123456789"));
		//固定種子的結果不可改變，否則使用者保存的種子重新開啟時會看到不同的結果
		check("endpoint floating pinned", true, seeded.contains("\"totalSumAmount\":8514979,"));
		check("endpoint floating random seed", true, call("/api/floating", floatingQuery).contains("\"seed\":"));
		check("endpoint floating add range", true, call("/api/floating", floatingQuery.replace("addMax=15%2C000", "addMax=4000"))
				.contains("\"field\":\"addMax\""));
		check("endpoint floating rate range", true, call("/api/floating", floatingQuery.replace("rateMax=6", "rateMax=1"))
				.contains("\"field\":\"rateMax\""));

		//投資計畫：依需要的年化報酬率套用建議規則
		check("plan 5% is gov bond", true, plan("years=5&months=0&profit=276281.5625").contains("\"tier\":\"GOV_BOND\""));
		check("plan 5.02% is corporate bond", true, plan("years=5&months=0&profit=277600").contains("\"tier\":\"CORPORATE_BOND\""));
		check("plan long 150% is core stock", true, plan("years=10&months=0&profit=1500000").contains("\"tier\":\"STOCK_CORE\""));
		String doubled = plan("years=2&months=0&profit=1000000");
		check("plan 2y double speculative", true, doubled.contains("\"tier\":\"SPECULATIVE\"") && doubled.contains("\"feasible\":false")
				&& doubled.contains("延長期間"));
		check("plan 1y x4 lottery", true, plan("years=1&months=0&profit=3000000").contains("\"tier\":\"LOTTERY\""));
		check("plan short month not lottery", true, plan("years=0&months=1&profit=100000").contains("\"tier\":\"SPECULATIVE\""));
		check("plan zero profit deposit", true, plan("years=3&months=6&profit=0").contains("\"tier\":\"DEPOSIT\""));
		String shortStock = plan("years=3&months=0&profit=400000");
		check("plan short horizon warns", true, shortStock.contains("\"tier\":\"STOCK_CORE\"") && shortStock.contains("\"feasible\":true")
				&& shortStock.contains("短於"));
		check("plan last row is target", true, plan("years=2&months=6&profit=250000").contains("[30,1000000.00,1250000.00,250000.00,"));
		check("plan needs period", true, plan("years=0&months=0&profit=1").contains("\"field\":\"years\""));
		check("plan tier boundaries", "DEPOSIT GOV_BOND SPECULATIVE LOTTERY", InvestmentPlanner.tierOf(0.03, 0.1) + " " + InvestmentPlanner.tierOf(0.030001, 0.1)
				+ " " + InvestmentPlanner.tierOf(1.5, 0.5) + " " + InvestmentPlanner.tierOf(1.5, 1.5));

		//可達成的方案：從風險最低的往上列到第一個一般行情可達成的，再多列一類，最多 3 個
		String bondOrStock = plan("years=5&months=0&profit=250000");
		check("plan options bond or stock", "GOV_BOND CORPORATE_BOND STOCK_INCOME", optionCodes(bondOrStock));
		//資產配置：定存／公債／公司債／股票每 5% 一級（股票再選等級），近似篩選後模擬，每型取機率最高（2 個百分點內取波動低）
		check("plan portfolio conservative best", true, bondOrStock.contains("\"code\":\"CONSERVATIVE\",\"name\":\"保守型\",\"recommended\":true,"
				+ "\"weights\":[0,60,20,20],\"stockLevel\":\"STOCK_CORE\"") && bondOrStock.contains("\"probability\":0.6300000000,\"chance\":\"LIKELY\""));
		check("plan portfolio balanced best", true, bondOrStock.contains("\"code\":\"BALANCED\",\"name\":\"穩健型\",\"recommended\":false,"
				+ "\"weights\":[0,40,30,30],\"stockLevel\":\"STOCK_GROWTH\""));
		check("plan portfolio aggressive best", true, bondOrStock.contains("\"code\":\"AGGRESSIVE\",\"name\":\"積極型\",\"recommended\":false,"
				+ "\"weights\":[0,25,25,50],\"stockLevel\":\"STOCK_AGGRESSIVE\""));
		check("plan portfolio search size", true, bondOrStock.contains("\"candidates\":605,") && bondOrStock.contains("\"candidates\":585,")
				&& bondOrStock.contains("\"candidates\":840,") && bondOrStock.contains("試算 2,030 種") && bondOrStock.contains("模擬 500 種市場走勢"));
		//三種配置都有逐年試算；保守型 0/60/20/20、100 萬、5 年、每年再平衡：前 4 年整體以加權 5.2% 成長，第 5 年各資產依自己的報酬率成長
		check("plan allocation rows for each profile", 4, bondOrStock.split("\"rows\":\\[").length - 1);
		check("plan no candidate list", false, bondOrStock.contains("\"candidates\":["));
		double fourYears = 1000000 * Math.pow(1.052, 4);
		double[] yearFive = numbers(bondOrStock, "[60,1000000.00,0.00,");
		check("plan allocation year 5 rebalanced", true, Math.abs(yearFive[3] - fourYears * 0.6 * 1.04) < 0.02 && Math.abs(yearFive[4] - fourYears * 0.2 * 1.07) < 0.02
				&& Math.abs(yearFive[5] - fourYears * 0.2 * 1.07) < 0.02);
		//各資產的累積獲利加總 = 總金額 − 投入；再平衡只搬動金額
		check("plan allocation gains add up", true, Math.abs(yearFive[6] + yearFive[7] + yearFive[8] + yearFive[9] - (yearFive[10] - 1000000)) < 0.05
				&& Math.abs(yearFive[11] - (yearFive[10] - 1000000)) < 0.05);
		check("plan option probability", true, bondOrStock.contains("\"code\":\"GOV_BOND\"") && bondOrStock.contains("\"probability\":0.4000000000"));
		check("plan strategies default selected", true, bondOrStock.contains("\"code\":\"FIXED_YEARLY\",\"name\":\"固定比例（每年再平衡）\",\"selected\":true")
				&& bondOrStock.contains("\"code\":\"TARGET_DATE\",") && bondOrStock.contains("\"code\":\"AGE_RULE\",") && bondOrStock.contains("\"stress\":null"));
		check("plan portfolio deterministic", bondOrStock, plan("years=5&months=0&profit=250000"));
		check("plan portfolio short horizon conservative", true, plan("years=2&months=0&profit=93330")
				.contains("\"code\":\"CONSERVATIVE\",\"name\":\"保守型\",\"recommended\":true"));
		check("plan portfolio hard target aggressive", true, plan("years=20&months=0&profit=3000000")
				.contains("\"code\":\"AGGRESSIVE\",\"name\":\"積極型\",\"recommended\":true"));
		check("plan portfolio none possible", true, plan("years=1&months=0&profit=3000000").contains("所有配置的達成機率都是 0"));
		check("allocation classify", "CONSERVATIVE CONSERVATIVE BALANCED BALANCED AGGRESSIVE", AllocationProfile.classify(20) + " " + AllocationProfile.classify(25)
				+ " " + AllocationProfile.classify(30) + " " + AllocationProfile.classify(45) + " " + AllocationProfile.classify(50));
		check("plan options long horizon", "STOCK_CORE STOCK_GROWTH STOCK_AGGRESSIVE STOCK_VOLATILE", optionCodes(plan("years=10&months=0&profit=1500000")));
		String small = plan("years=5&months=0&profit=51010");
		check("plan options small target", "DEPOSIT GOV_BOND", optionCodes(small));
		//目標很容易時，定存上限讓配置不會變成「定存 70%」這種組合
		check("plan portfolio deposit cap", true, small.contains("\"weights\":[50,45,0,5]") && small.contains("\"weights\":[25,45,0,30]"));
		check("plan zero profit only deposit", "DEPOSIT", optionCodes(plan("years=3&months=6&profit=0")));
		String shortBond = plan("years=2&months=0&profit=93330");
		check("plan short horizon options capped", "GOV_BOND CORPORATE_BOND STOCK_INCOME STOCK_CORE", optionCodes(shortBond));
		check("plan short horizon stock flagged", true, shortBond.contains("\"code\":\"STOCK_CORE\",\"name\":\"穩健型股票\",\"fit\":\"COMFORTABLE\",\"horizonOk\":false"));
		check("plan speculative single option", "SPECULATIVE", optionCodes(doubled));

		//蒙地卡羅模擬：波動為 0 時與逐月複利公式相同；常態亂數的平均與標準差；中位數接近一般行情
		MarketSimulator noRisk = new MarketSimulator(CashFlows.of(1000000, 10000, 30), ReturnDistribution.NORMAL);
		double flat = noRisk.simulate(Allocation.single(InvestmentTier.DEPOSIT, noRisk.getSteps()), 0).getFinals()[0];
		check("simulator zero volatility = formula", true, Math.abs(flat - InvestmentPlanner.futureValue(1000000, 10000, 0.0175, 30)) < 1e-4);
		RangeRandom gaussian = new RangeRandom(1);
		double sum = 0;
		double squares = 0;
		for (int i = 0; i < 20000; i++) {
			double z = gaussian.nextGaussian();
			sum += z;
			squares += z * z;
		}
		double mean = sum / 20000;
		check("random gaussian mean and sd", true, Math.abs(mean) < 0.03 && Math.abs(Math.sqrt(squares / 20000 - mean * mean) - 1) < 0.03);
		double median = new MarketSimulator(CashFlows.of(1000000, 0, 60), ReturnDistribution.NORMAL)
				.simulate(Allocation.single(InvestmentTier.STOCK_CORE, 5), 0).percentile(0.5);
		check("simulator median near typical", true, Math.abs(median / (1000000 * Math.pow(1.07, 5)) - 1) < 0.05);
		check("simulator probability and percentile", "0.5 5.0", MarketSimulator.probability(new double[] { 1, 2, 3, 4 }, 2.5) + " "
				+ MarketSimulator.percentile(new double[] { 0, 10 }, 0.5));

		//每月投資：以二分法求出的報酬率，期滿金額要剛好等於總投入 + 期望獲利
		double monthlyRate = InvestmentPlanner.requiredRate(0, 10000, 120, 500000);
		check("plan solver hits target", true, Math.abs(InvestmentPlanner.futureValue(0, 10000, monthlyRate, 120) - 1700000) < 0.01);
		check("plan solver no monthly = formula", true, Math.abs(InvestmentPlanner.requiredRate(1000000, 0, 60, 250000) - (Math.pow(1.25, 0.2) - 1)) < 1e-12);
		String monthlyOnly = call("/api/plan", "principal=0&monthlyFixed=10%2C000&years=10&months=0&profit=500%2C000");
		check("plan monthly only", true, monthlyOnly.contains("\"invested\":1200000.00,") && monthlyOnly.contains("\"requiredRate\":0.0677440000,")
				&& monthlyOnly.contains("\"tier\":\"CORPORATE_BOND\""));
		String ranged = call("/api/plan", "principal=1%2C000%2C000&monthlyFixed=10%2C000&floatMin=0&floatMax=5%2C000&years=5&months=0&profit=400%2C000&round=1");
		check("plan floating expected midpoint", true, ranged.contains("\"monthly\":12500,\"invested\":1750000,"));
		check("plan floating high and low", true, ranged.contains("\"high\":{\"monthly\":15000,\"invested\":1900000,\"rate\":0.0502740000,")
				&& ranged.contains("\"low\":{\"monthly\":10000,\"invested\":1600000,\"rate\":0.0554440000,"));
		String floatOnly = call("/api/plan", "principal=0&floatMin=0&floatMax=5%2C000&years=1&months=0&profit=1%2C000&round=1");
		check("plan floating low case empty", true, floatOnly.contains("\"low\":{\"monthly\":0,\"invested\":0,\"rate\":null,")
				&& floatOnly.contains("\"lowCaseOk\":false") && floatOnly.contains("沒有投入任何資金"));
		check("plan floating range order", true, call("/api/plan", "principal=1&floatMin=5000&floatMax=1000&years=1&months=0&profit=1")
				.contains("\"field\":\"floatMax\""));
		check("plan needs money", true, call("/api/plan", "principal=0&years=1&months=0&profit=1").contains("\"field\":\"principal\""));
		check("plan infeasible monthly suggestion", true, call("/api/plan", "principal=1%2C000%2C000&monthlyFixed=5%2C000&years=2&months=0&profit=1%2C000%2C000")
				.contains("增加每月投資"));

		//配置調整：目標日期在最後 5 年逐年降到一半；年齡法則股票 ≤ 100 − 當年年齡；固定比例不變
		int[] quarterStock = { 0, 55, 20, 25 };
		Allocation targetDate = Allocation.of(quarterStock, InvestmentTier.STOCK_CORE, GlidePath.TARGET_DATE, RebalanceMode.YEARLY, 35, 10);
		check("glide target date end and average", true, Math.abs(targetDate.endStockPercent() - 12.5) < 1e-9
				&& Math.abs(targetDate.averageWeights()[3] - 0.2125) < 1e-9 && Math.abs(targetDate.averageWeights()[1] - (0.55 + 0.0375)) < 1e-9);
		check("glide age rule caps stock", true, Math.abs(Allocation.of(new int[] { 0, 30, 0, 70 }, InvestmentTier.STOCK_CORE, GlidePath.AGE_RULE,
				RebalanceMode.YEARLY, 40, 10).endStockPercent() - 51) < 1e-9);
		check("glide fixed keeps stock", true, Math.abs(Allocation.of(quarterStock, InvestmentTier.STOCK_CORE, GlidePath.FIXED, RebalanceMode.NONE, 35, 10)
				.endStockPercent() - 25) < 1e-9);
		//不再平衡時，一般行情下各資產各自複利（與舊版逐年試算相同）
		List<MarketSimulator.Snapshot> drift = MarketSimulator.typical(CashFlows.of(1000000, 0, 60)).trace(Allocation.of(new int[] { 0, 60, 20, 20 },
				InvestmentTier.STOCK_CORE, GlidePath.FIXED, RebalanceMode.NONE, 35, 5));
		double[] driftEnd = drift.get(drift.size() - 1).getValues();
		check("rebalance none drifts", true, drift.size() == 5 && Math.abs(driftEnd[1] - 600000 * Math.pow(1.04, 5)) < 1e-6
				&& Math.abs(driftEnd[3] - 200000 * Math.pow(1.07, 5)) < 1e-6);

		//中途提領：月份排序合併；需要的報酬率讓期滿金額 = 投入 − 提領 + 獲利；不可提領尚未投入的錢
		CashFlows withdrawFlows = new CashFlows(1000000, 10000, 120, new int[] { 96, 36, 96 }, new double[] { 200000, 500000, 100000 });
		double withdrawRate = InvestmentPlanner.requiredRate(withdrawFlows, 1000000);
		check("withdraw flows sorted and merged", "36 96 800000.0", withdrawFlows.getWithdrawMonths()[0] + " " + withdrawFlows.getWithdrawMonths()[1] + " "
				+ withdrawFlows.withdrawn());
		check("withdraw solver hits target", true, Math.abs(withdrawFlows.futureValue(withdrawRate) - 2400000) < 0.01);
		check("withdraw overdrawn month", 12, new CashFlows(100000, 10000, 60, new int[] { 12 }, new double[] { 300000 }).firstOverdrawnMonth());
		String withdraw = call("/api/plan", "principal=1%2C000%2C000&monthlyFixed=10%2C000&years=10&months=0&profit=1%2C000%2C000"
				+ "&withdrawMonth=36&withdrawAmount=500%2C000&withdrawMonth=96&withdrawAmount=300%2C000");
		check("plan withdraw target", true, withdraw.contains("\"withdrawn\":800000.00,") && withdraw.contains("\"target\":2400000.00,")
				&& withdraw.contains("[120,2200000.00,2400000.00,1000000.00,") && withdraw.contains("中途提領 2 筆共 $800,000"));
		check("plan withdraw lowers rate need", true, withdraw.contains("\"requiredRate\":0.0632590000,"));
		check("plan withdraw overdrawn field", true, call("/api/plan", "principal=100000&monthlyFixed=10000&years=5&months=0&profit=100000"
				+ "&withdrawMonth=12&withdrawAmount=300000").contains("\"field\":\"withdrawAmount:0\""));
		check("plan withdraw month range", true, call("/api/plan", "principal=1000000&years=5&months=0&profit=100000&withdrawMonth=61&withdrawAmount=1000")
				.contains("\"field\":\"withdrawMonth:0\""));

		//年齡法則：60 歲股票上限 40%，積極型沒有可用配置，穩健型只剩股票 30%～40%
		String aged = call("/api/plan", "principal=1000000&monthlyFixed=10000&years=10&months=0&profit=1000000&glide=AGE_RULE&age=60");
		check("plan age rule excludes aggressive", true, !aged.contains("\"code\":\"AGGRESSIVE\",\"name\":\"積極型\"") && aged.contains("積極型沒有可用的配置")
				&& aged.contains("\"candidates\":441,") && aged.contains("\"code\":\"AGE_RULE\",\"name\":\"依年齡法則（股票 ≤ 100 − 年齡）\",\"selected\":true"));
		check("plan invalid glide", true, call("/api/plan", "principal=1000000&years=5&months=0&profit=1&glide=FOO").contains("\"field\":\"glide\""));
		check("plan invalid age", true, call("/api/plan", "principal=1000000&years=5&months=0&profit=1&age=0").contains("\"field\":\"age\""));

		//壓力測試：同一組走勢，情境年份換成情境報酬；金融海嘯發生在期滿前一年時每型的達成機率都下降
		PlanSettings crashSettings = new PlanSettings();
		crashSettings.setStress(StressScenario.CRASH);
		crashSettings.setStressTiming(StressTiming.END);
		InvestmentPlan crash = new InvestmentPlanner().plan(new BigDecimal("1000000"), new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, 120,
				new BigDecimal("1500000"), crashSettings);
		boolean allLower = crash.getStressFrom() == 9 && crash.getStressYears() == 1;
		for (InvestmentPlan.Portfolio portfolio : crash.getPortfolios()) {
			allLower &= portfolio.getStressOutcome().getProbability().compareTo(portfolio.getProbability()) < 0
					&& portfolio.getStressOutcome().getMedian().compareTo(portfolio.getMedian()) < 0;
		}
		check("stress crash lowers every profile", true, allLower);
		PlanSettings stagnation = new PlanSettings();
		stagnation.setStress(StressScenario.STAGNATION);
		stagnation.setStressTiming(StressTiming.START);
		InvestmentPlan flat5 = new InvestmentPlanner().plan(new BigDecimal("1000000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 36,
				new BigDecimal("100000"), stagnation);
		check("stress stagnation span capped", "0 3", flat5.getStressFrom() + " " + flat5.getStressYears());
		String stressQuery = "principal=1000000&monthlyFixed=10000&years=10&months=0&profit=1500000&stress=CRASH&stressTiming=END&distribution=FAT_TAIL";
		String stressed = call("/api/plan", stressQuery);
		check("plan stress output", true, stressed.contains("\"stress\":{\"code\":\"CRASH\",\"name\":\"金融海嘯\"") && stressed.contains("\"period\":\"第 10 年\"")
				&& stressed.contains("假設在期滿前（第 10 年）發生金融海嘯"));
		check("plan stress deterministic", stressed, call("/api/plan", stressQuery));

		//厚尾分布：標準差相同，但極端年份較多，最差 1% 的結果更差
		CashFlows thirtyYears = CashFlows.of(1000000, 0, 360);
		Allocation volatile30 = Allocation.single(InvestmentTier.STOCK_VOLATILE, 30);
		MarketSimulator.Outcome normal30 = new MarketSimulator(thirtyYears, ReturnDistribution.NORMAL).simulate(volatile30, 0);
		MarketSimulator.Outcome fat30 = new MarketSimulator(thirtyYears, ReturnDistribution.FAT_TAIL).simulate(volatile30, 0);
		check("fat tail worse worst case", true, fat30.percentile(0.01) < normal30.percentile(0.01));
		check("meta plan options", true, call("/api/meta", "").contains("\"glidePaths\":[{\"code\":\"FIXED\"")
				&& call("/api/meta", "").contains("\"stressScenarios\":[{\"code\":\"NONE\""));
		check("numbers group negative", "-1,234,567.5", Numbers.group(new BigDecimal("-1234567.50"), 2));
		check("numbers group small", "0.0001", Numbers.group(new BigDecimal("0.0001"), 4));
		check("numbers group billion", "1,000,000,000", Numbers.group(new BigDecimal("1000000000"), 4));
	}

	private static String compound(String query) {
		return call("/api/compound", query);
	}

	/** 與 API 相同的金額格式：小數兩位、HALF_EVEN */
	private static String money(double amount) {
		return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
	}

	private static String plan(String query) {
		return call("/api/plan", "principal=1%2C000%2C000&" + query);
	}

	/** JSON 中以 prefix 開頭的陣列的所有數字（含 prefix 內的數字）；找不到時為空陣列 */
	private static double[] numbers(String json, String prefix) {
		int start = json.indexOf(prefix);
		if (start < 0) {
			return new double[13];
		}
		String[] parts = json.substring(start + 1, json.indexOf(']', start)).split(",");
		double[] values = new double[parts.length];
		for (int i = 0; i < parts.length; i++) {
			values[i] = Double.parseDouble(parts[i]);
		}
		return values;
	}

	/** 投資計畫回應中 options 的類別代碼，以空白分隔 */
	private static String optionCodes(String json) {
		String options = json.substring(json.indexOf("\"options\":"), json.indexOf("\"portfolioSummary\":"));
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"code\":\"([A-Z_]+)\"").matcher(options);
		StringBuilder codes = new StringBuilder();
		while (matcher.find()) {
			codes.append(codes.length() == 0 ? "" : " ").append(matcher.group(1));
		}
		return codes.toString();
	}

	private static String call(String path, String query) {
		ApiEndpoint endpoint = ApiEndpoints.all().stream().filter(e -> e.getPath().equals(path)).findFirst().orElseThrow();
		return Json.write(endpoint.execute(query).getBody());
	}

	private static void http() throws Exception {
		WebServer server = WebServer.start(new InetSocketAddress("127.0.0.1", 0));
		try {
			String base = "http://127.0.0.1:" + server.getPort();
			HttpClient client = HttpClient.newHttpClient();

			HttpResponse<String> mortgage = post(client, base + "/api/mortgage",
					"loanAmount=10%2C000%2C000&rate=2.2&years=30&graceYears=0&method=EQUAL_PAYMENT&prepaymentMode=SHORTEN_TERM"
							+ "&ratePeriod=25&rateValue=2.5");
			check("http mortgage status", "200", String.valueOf(mortgage.statusCode()));
			check("http mortgage interest", true, mortgage.body().contains("\"totalInterest\":4156549"));

			HttpResponse<String> invalid = post(client, base + "/api/mortgage",
					"loanAmount=10000000&rate=2.2&years=3&graceYears=3&method=EQUAL_PAYMENT");
			check("http grace invalid status", "400", String.valueOf(invalid.statusCode()));
			check("http grace invalid field", true, invalid.body().contains("\"field\":\"graceYears\""));

			HttpResponse<String> bond = get(client, base + "/api/bond?currency=TWD&faceValue=10000&buyPrice=98.5&redemptionPrice=100"
					+ "&couponRate=4.5&frequency=SEMI_ANNUAL&years=10&feeRate=0&taxRate=0");
			check("http bond TWD", true, bond.statusCode() == 200 && bond.body().contains("\"foreign\":false"));

			HttpResponse<String> lottery = post(client, base + "/api/lottery", "game=DAILY_CASH&amount=50&frequency=MONTHLY&years=1&compareRate=7");
			check("http lottery", true, lottery.statusCode() == 200 && lottery.body().contains("\"perBet\":27.9198,"));

			HttpResponse<String> currency = get(client, base + "/api/currency?amount=1000&from=USD&fromRate=32&to=TWD");
			check("http currency", true, currency.body().contains("\"result\":32000.00"));
			HttpResponse<String> currencyWhole = get(client, base + "/api/currency?amount=1000&from=TWD&to=USD&toRate=32&round=1");
			check("http currency whole", true, currencyWhole.body().contains("\"decimals\":0,\"amount\":1000,\"result\":31,"));
			//貨幣投資的線上匯率由瀏覽器直接讀取，CSP 要允許這些網址
			check("http csp rate sources", true, currencyWhole.headers().firstValue("Content-Security-Policy").orElse("").contains("https://open.er-api.com"));

			check("http meta", true, get(client, base + "/api/meta").body().contains("\"version\":\"" + AppVersion.VERSION + "\""));
			check("http unknown api", "404", String.valueOf(get(client, base + "/api/compoundx").statusCode()));
			check("http index", "200", String.valueOf(get(client, base + "/").statusCode()));
			check("http traversal", "404", String.valueOf(get(client, base + "/%2e%2e/com/fullmoney/WebApp.class").statusCode()));
			check("http class file", "404", String.valueOf(get(client, base + "/com/fullmoney/WebApp.class").statusCode()));
		} finally {
			server.stop();
		}
	}

	private static HttpResponse<String> post(HttpClient client, String url, String form) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String percent(BigDecimal ratio) {
		return ratio.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "%";
	}

	private static void rejects(String name, Runnable action) {
		try {
			action.run();
			check(name, "BadRequestException", "accepted");
		} catch (BadRequestException e) {
			check(name, true, true);
		}
	}

	private static void check(String name, int expected, int actual) {
		check(name, String.valueOf(expected), String.valueOf(actual));
	}

	private static void check(String name, boolean expected, boolean actual) {
		check(name, String.valueOf(expected), String.valueOf(actual));
	}

	private static void check(String name, String expected, String actual) {
		checks++;
		boolean ok = expected.equals(actual);
		if (!ok) {
			failures++;
		}
		System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok ? "" : "  expected=" + expected + " actual=" + actual));
	}
}
