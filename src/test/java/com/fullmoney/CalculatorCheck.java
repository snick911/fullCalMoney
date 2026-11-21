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

import com.fullmoney.bean.BondCondition;
import com.fullmoney.bean.BondResult;
import com.fullmoney.bean.CouponFrequency;
import com.fullmoney.bean.MortgagePrepayment;
import com.fullmoney.bean.MortgageRateChange;
import com.fullmoney.bean.MortgageResult;
import com.fullmoney.bean.PrepaymentMode;
import com.fullmoney.bean.RepaymentMethod;
import com.fullmoney.bean.SaveMoney;
import com.fullmoney.service.BondCalculator;
import com.fullmoney.service.CurrencyConverter;
import com.fullmoney.service.FloatingMoneyCalculator;
import com.fullmoney.service.InvestmentPlanner;
import com.fullmoney.service.MoneyCalculator;
import com.fullmoney.service.MortgageCalculator;
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
		check("plan 5% is bond", true, plan("years=5&months=0&profit=276281.5625").contains("\"tier\":\"BOND\""));
		check("plan 5.01% is balanced", true, plan("years=5&months=0&profit=277600").contains("\"tier\":\"BALANCED\""));
		check("plan long 150% is stock", true, plan("years=10&months=0&profit=1500000").contains("\"tier\":\"STOCK\""));
		String doubled = plan("years=2&months=0&profit=1000000");
		check("plan 2y double speculative", true, doubled.contains("\"tier\":\"SPECULATIVE\"") && doubled.contains("\"feasible\":false")
				&& doubled.contains("延長期間"));
		check("plan 1y x4 lottery", true, plan("years=1&months=0&profit=3000000").contains("\"tier\":\"LOTTERY\""));
		check("plan short month not lottery", true, plan("years=0&months=1&profit=100000").contains("\"tier\":\"SPECULATIVE\""));
		check("plan zero profit deposit", true, plan("years=3&months=6&profit=0").contains("\"tier\":\"DEPOSIT\""));
		String shortStock = plan("years=3&months=0&profit=400000");
		check("plan short horizon warns", true, shortStock.contains("\"tier\":\"STOCK\"") && shortStock.contains("\"feasible\":true")
				&& shortStock.contains("短於"));
		check("plan last row is target", true, plan("years=2&months=6&profit=250000").contains("[30,1250000.00,250000.00,"));
		check("plan needs period", true, plan("years=0&months=0&profit=1").contains("\"field\":\"years\""));
		check("plan tier boundaries", "DEPOSIT BOND SPECULATIVE LOTTERY", InvestmentPlanner.tierOf(0.02, 0.1) + " " + InvestmentPlanner.tierOf(0.020001, 0.1)
				+ " " + InvestmentPlanner.tierOf(1.5, 0.5) + " " + InvestmentPlanner.tierOf(1.5, 1.5));

		//可達成的方案：從風險最低的往上列到第一個一般行情可達成的，再多列一類，最多 3 個
		String bondOrStock = plan("years=5&months=0&profit=250000");
		check("plan options bond or stock", "BOND BALANCED STOCK", optionCodes(bondOrStock));
		check("plan allocation bond 60 stock 40", true, bondOrStock.contains("{\"code\":\"BOND\",\"name\":\"債券\",\"percent\":60},{\"code\":\"STOCK\"")
				&& bondOrStock.contains("\"rate\":0.0490000000"));
		check("plan options long horizon", "STOCK AGGRESSIVE", optionCodes(plan("years=10&months=0&profit=1500000")));
		String small = plan("years=5&months=0&profit=51010");
		check("plan options small target", "DEPOSIT BOND", optionCodes(small));
		check("plan small target no allocation", true, small.contains("\"allocation\":null"));
		check("plan zero profit only deposit", "DEPOSIT", optionCodes(plan("years=3&months=6&profit=0")));
		String shortBond = plan("years=2&months=0&profit=93330");
		check("plan short horizon options capped", "BOND BALANCED STOCK", optionCodes(shortBond));
		check("plan short horizon stock flagged", true, shortBond.contains("\"code\":\"STOCK\",\"name\":\"股票／基金\",\"fit\":\"COMFORTABLE\",\"horizonOk\":false"));
		check("plan speculative single option", "SPECULATIVE", optionCodes(doubled));
		check("plan speculative no allocation", true, doubled.contains("\"allocation\":null"));
		check("numbers group negative", "-1,234,567.5", Numbers.group(new BigDecimal("-1234567.50"), 2));
		check("numbers group small", "0.0001", Numbers.group(new BigDecimal("0.0001"), 4));
		check("numbers group billion", "1,000,000,000", Numbers.group(new BigDecimal("1000000000"), 4));
	}

	private static String compound(String query) {
		return call("/api/compound", query);
	}

	private static String plan(String query) {
		return call("/api/plan", "principal=1%2C000%2C000&" + query);
	}

	/** 投資計畫回應中 options 的類別代碼，以空白分隔 */
	private static String optionCodes(String json) {
		String options = json.substring(json.indexOf("\"options\":"), json.indexOf("\"allocation\":"));
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"code\":\"([A-Z]+)\"").matcher(options);
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

			HttpResponse<String> currency = get(client, base + "/api/currency?amount=1000&from=USD&fromRate=32&to=TWD");
			check("http currency", true, currency.body().contains("\"result\":32000.00"));
			HttpResponse<String> currencyWhole = get(client, base + "/api/currency?amount=1000&from=TWD&to=USD&toRate=32&round=1");
			check("http currency whole", true, currencyWhole.body().contains("\"decimals\":0,\"result\":31,"));

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
