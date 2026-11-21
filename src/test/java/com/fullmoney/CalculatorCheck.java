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
import com.fullmoney.service.BondCalculator;
import com.fullmoney.service.CurrencyConverter;
import com.fullmoney.service.MoneyCalculator;
import com.fullmoney.service.MortgageCalculator;
import com.fullmoney.web.BadRequestException;
import com.fullmoney.web.Json;
import com.fullmoney.web.Params;
import com.fullmoney.web.WebServer;

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
		mortgage();
		bond();
		currency();
		params();
		json();
		http();
		System.out.println();
		System.out.println(failures == 0 ? "ALL " + checks + " CHECKS PASSED" : failures + " OF " + checks + " CHECKS FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void compound() {
		var result = new MoneyCalculator().calculate(new BigDecimal("200000"), new BigDecimal("0.04"), new BigDecimal("10000"), 20);
		check("compound wealth", "8736140.77", result.getTotalSumAmount().setScale(2, RoundingMode.HALF_EVEN).toPlainString());
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
	}

	private static void json() {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("a", "x\"</script>");
		value.put("b", new Object[] { 1, null, new BigDecimal("2.50") });
		check("json escaping", "{\"a\":\"x\\\"\\u003c/script\\u003e\",\"b\":[1,null,2.50]}", Json.write(value));
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
