package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import com.fullmoney.bean.MortgagePayment;
import com.fullmoney.bean.MortgagePrepayment;
import com.fullmoney.bean.MortgageRateChange;
import com.fullmoney.bean.MortgageResult;
import com.fullmoney.bean.PrepaymentMode;
import com.fullmoney.bean.RepaymentMethod;
import com.fullmoney.service.MortgageCalculator;
import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.BadRequestException;
import com.fullmoney.web.Numbers;
import com.fullmoney.web.Params;

/**
 * 房貸計算，可加入分段利率與提前還款
 *
 * <p>參數：loanAmount 貸款金額、rate 年利率(%)、years 貸款年限(含寬限期)、graceYears 寬限期、
 * method 還款方式、prepaymentMode 提前還款後的方式；
 * 分段利率以 ratePeriod 與 rateValue(%) 成對重複送出，提前還款以 prepayPeriod 與 prepayAmount 成對重複送出。</p>
 * <p>rows 每列為 [期數, 年度, 年利率(%), 月付金, 償還本金, 償還利息, 提前還款, 剩餘本金]</p>
 */
public final class MortgageApi extends ApiEndpoint {

	private static final int MONTHS_PER_YEAR = 12;
	private static final int MAX_YEAR = 50;
	private static final int MAX_GRACE_YEAR = 10;
	private static final int MAX_PERIOD = MAX_YEAR * MONTHS_PER_YEAR;
	private static final int MAX_ENTRIES = 120;

	private final MortgageCalculator calculator = new MortgageCalculator();

	public MortgageApi() {
		super("/api/mortgage");
	}

	@Override
	protected Object respond(Params params) {
		BigDecimal loanAmount = params.decimal("loanAmount", "貸款金額", "0", Values.MAX_MONEY);
		BigDecimal rate = params.decimal("rate", "年利率", "0", "100");
		int years = params.integer("years", "貸款年限", 1, MAX_YEAR);
		int graceYears = params.integer("graceYears", "寬限期", 0, MAX_GRACE_YEAR);
		RepaymentMethod method = params.choice("method", "還款方式", RepaymentMethod.values());
		PrepaymentMode mode = params.choice("prepaymentMode", "提前還款後的方式", PrepaymentMode.values(), PrepaymentMode.SHORTEN_TERM);
		List<MortgageRateChange> rateChanges = entries(params, "ratePeriod", "rateValue", "分段利率", "起始期數", "年利率", "0", "100",
				(period, value) -> new MortgageRateChange(period, value.movePointLeft(2)));
		List<MortgagePrepayment> prepayments = entries(params, "prepayPeriod", "prepayAmount", "提前還款", "期數", "金額", "0", Values.MAX_MONEY,
				MortgagePrepayment::new);
		//年限輸入途中可能短暫小於寬限期 ex. 輸入 30 時會先經過 3，只提示不自動修改
		if (graceYears >= years) {
			throw new BadRequestException("graceYears", "寬限期必須短於貸款年限，請調整條件。");
		}

		BigDecimal annualRate = rate.movePointLeft(2);
		MortgageResult result = calculator.calculate(loanAmount, annualRate, years, graceYears, method, rateChanges, prepayments, mode);
		MortgageResult original = calculator.calculate(loanAmount, annualRate, years, graceYears, method, rateChanges, Collections.emptyList(), mode);

		List<Object[]> rows = new ArrayList<>(result.getPayments().size());
		BigDecimal prepaid = BigDecimal.ZERO;
		for (MortgagePayment payment : result.getPayments()) {
			prepaid = prepaid.add(payment.getPrepayment());
			rows.add(new Object[] { payment.getPeriod(), (payment.getPeriod() - 1) / MONTHS_PER_YEAR + 1, payment.getAnnualRate().movePointRight(2),
					payment.getPayment(), payment.getPrincipal(), payment.getInterest(), Values.blankIfZero(payment.getPrepayment()),
					payment.getBalance() });
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("gracePayment", result.getGracePayment());
		out.put("firstPayment", result.getFirstPayment());
		out.put("totalInterest", result.getTotalInterest());
		out.put("totalPayment", result.getTotalPayment());
		out.put("periods", result.getPayments().size());
		out.put("originalPeriods", original.getPayments().size());
		out.put("prepaid", prepaid);
		//與相同條件、沒有提前還款的試算比較，分段利率也相同
		out.put("savedInterest", original.getTotalInterest().subtract(result.getTotalInterest()));
		out.put("warnings", warnings(prepaid, prepayments, rateChanges, result.getPayments().size()));
		out.put("rows", rows);
		return out;
	}

	private static <T> List<T> entries(Params params, String periodName, String valueName, String title, String periodLabel, String valueLabel,
			String min, String max, BiFunction<Integer, BigDecimal, T> factory) {
		List<String> periods = params.list(periodName);
		List<String> values = params.list(valueName);
		if (periods.size() != values.size()) {
			throw new BadRequestException(null, title + "的期數與數值筆數不一致。");
		}
		if (periods.size() > MAX_ENTRIES) {
			throw new BadRequestException(null, title + "最多 " + MAX_ENTRIES + " 筆。");
		}
		List<T> entries = new ArrayList<>(periods.size());
		for (int i = 0; i < periods.size(); i++) {
			String prefix = title + "第 " + (i + 1) + " 筆的";
			int period = Params.toInteger(periods.get(i), periodName + ":" + i, prefix + periodLabel, 1, MAX_PERIOD);
			BigDecimal value = Params.toDecimal(values.get(i), valueName + ":" + i, prefix + valueLabel, new BigDecimal(min), new BigDecimal(max));
			entries.add(factory.apply(period, value));
		}
		return entries;
	}

	/** 有輸入但沒有計入試算的條件要明確告知，不能默默忽略 */
	private static List<String> warnings(BigDecimal prepaid, List<MortgagePrepayment> prepayments, List<MortgageRateChange> rateChanges, int periods) {
		List<String> warnings = new ArrayList<>();
		BigDecimal requested = BigDecimal.ZERO;
		for (MortgagePrepayment prepayment : prepayments) {
			requested = requested.add(prepayment.getAmount());
		}
		if (requested.compareTo(prepaid) > 0) {
			warnings.add("有 " + dollars(requested.subtract(prepaid)) + " 提前還款未計入（超過剩餘本金或已還清）。");
		}
		long unusedRates = rateChanges.stream().filter(change -> change.getStartPeriod() > periods).count();
		if (unusedRates > 0) {
			warnings.add("有 " + unusedRates + " 筆分段利率的起始期數已超過還款期間，未使用。");
		}
		return warnings;
	}

	private static String dollars(BigDecimal amount) {
		return "$" + Numbers.group(amount, 2);
	}
}
