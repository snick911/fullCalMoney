package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.BondCondition;
import com.fullmoney.bean.BondPayment;
import com.fullmoney.bean.BondResult;
import com.fullmoney.bean.BondReturn;
import com.fullmoney.bean.CouponFrequency;
import com.fullmoney.bean.CurrencyType;
import com.fullmoney.service.BondCalculator;
import com.fullmoney.web.ApiHandler;
import com.fullmoney.web.Params;

/**
 * 債券持有到期試算，外幣債券可換算回新台幣
 *
 * <p>參數：currency 幣別、faceValue 面額、buyPrice 買入價格(%)、redemptionPrice 到期價格(%)、couponRate 票面利率(%)、
 * frequency 配息頻率、years 年期、feeRate 手續費(%)、taxRate 配息稅率(%)、
 * buyExchangeRate / couponExchangeRate / redemptionExchangeRate 匯率（新台幣固定為 1，不必送出）</p>
 * <p>rows 每列為 [期數, 年度, 配息(原幣), 本金(原幣), 配息(台幣), 本金(台幣), 累計收回(台幣), 累計報酬率]</p>
 */
public final class BondApi extends ApiHandler {

	private static final int MAX_YEAR = 50;

	private final BondCalculator calculator = new BondCalculator();

	public BondApi() {
		super("/api/bond");
	}

	@Override
	protected Object respond(Params params) {
		CurrencyType currency = params.choice("currency", "幣別", CurrencyType.class);
		BigDecimal faceValue = params.decimal("faceValue", "面額", "0", Values.MAX_MONEY);
		BigDecimal buyPrice = params.decimal("buyPrice", "買入價格", "0.01", "1000");
		BigDecimal redemptionPrice = params.decimal("redemptionPrice", "到期價格", "0", "1000");
		BigDecimal couponRate = params.decimal("couponRate", "票面利率", "0", "100");
		CouponFrequency frequency = params.choice("frequency", "配息頻率", CouponFrequency.class);
		int years = params.integer("years", "年期", 1, MAX_YEAR);
		BigDecimal feeRate = params.decimal("feeRate", "手續費", "0", "100");
		BigDecimal taxRate = params.decimal("taxRate", "配息稅率", "0", "100");
		boolean foreign = currency != CurrencyType.TWD;
		BigDecimal buyExchangeRate = exchangeRate(params, foreign, "buyExchangeRate", "買入匯率");
		BigDecimal couponExchangeRate = exchangeRate(params, foreign, "couponExchangeRate", "配息匯率");
		BigDecimal redemptionExchangeRate = exchangeRate(params, foreign, "redemptionExchangeRate", "到期匯率");

		BondResult result = calculator.calculate(new BondCondition(faceValue, buyPrice.movePointLeft(2), redemptionPrice.movePointLeft(2),
				couponRate.movePointLeft(2), frequency, years, feeRate.movePointLeft(2), taxRate.movePointLeft(2),
				buyExchangeRate, couponExchangeRate, redemptionExchangeRate));

		int periodsPerYear = frequency.getTimesPerYear();
		List<Object[]> rows = new ArrayList<>(result.getPayments().size());
		for (BondPayment payment : result.getPayments()) {
			rows.add(new Object[] { payment.getPeriod(), (payment.getPeriod() - 1) / periodsPerYear + 1, payment.getCoupon(),
					Values.blankIfZero(payment.getRedemption()), payment.getHomeCoupon(), Values.blankIfZero(payment.getHomeRedemption()),
					payment.getHomeReceived(), Values.ratio(payment.getHomeReturn()) });
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("currency", currency.name());
		out.put("currencyLabel", currency.getLabel());
		out.put("foreign", foreign);
		out.put("bond", toMap(result.getBondReturn()));
		out.put("home", toMap(result.getHomeReturn()));
		out.put("yieldToMaturity", Values.ratio(result.getYieldToMaturity()));
		out.put("exchangeGain", result.getExchangeGain());
		out.put("rows", rows);
		return out;
	}

	/** 新台幣債券不需要匯率，固定為 1 */
	private static BigDecimal exchangeRate(Params params, boolean foreign, String name, String label) {
		return foreign ? params.decimal(name, label, Values.MIN_EXCHANGE_RATE, Values.MAX_EXCHANGE_RATE) : BigDecimal.ONE;
	}

	private static Map<String, Object> toMap(BondReturn bondReturn) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("cost", bondReturn.getCost());
		out.put("couponTotal", bondReturn.getCouponTotal());
		out.put("redemption", bondReturn.getRedemption());
		out.put("profit", bondReturn.getProfit());
		out.put("annualCouponReturn", Values.ratio(bondReturn.getAnnualCouponReturn()));
		out.put("couponReturn", Values.ratio(bondReturn.getCouponReturn()));
		out.put("totalReturn", Values.ratio(bondReturn.getTotalReturn()));
		out.put("annualReturn", Values.ratio(bondReturn.getAnnualReturn()));
		return out;
	}
}
