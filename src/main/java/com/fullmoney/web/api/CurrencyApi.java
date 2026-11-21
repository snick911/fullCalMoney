package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fullmoney.bean.CurrencyType;
import com.fullmoney.service.CurrencyConverter;
import com.fullmoney.web.ApiHandler;
import com.fullmoney.web.Params;

/**
 * 幣別轉換，透過新台幣換算
 *
 * <p>參數：amount 金額、from / to 幣別、fromRate / toRate 1 單位該幣別可換多少新台幣（新台幣固定為 1，不必送出）</p>
 */
public final class CurrencyApi extends ApiHandler {

	private static final int CROSS_RATE_SCALE = 6;

	private final CurrencyConverter converter = new CurrencyConverter();

	public CurrencyApi() {
		super("/api/currency");
	}

	@Override
	protected Object respond(Params params) {
		BigDecimal amount = params.decimal("amount", "金額", "0", Values.MAX_MONEY);
		CurrencyType from = params.choice("from", "原幣別", CurrencyType.class);
		CurrencyType to = params.choice("to", "目標幣別", CurrencyType.class);
		BigDecimal fromRate = rate(params, from, "fromRate");
		BigDecimal toRate = rate(params, to, "toRate");

		BigDecimal crossRate = converter.crossRate(fromRate, toRate).setScale(CROSS_RATE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("from", from.name());
		out.put("to", to.name());
		out.put("result", converter.convert(amount, fromRate, toRate));
		out.put("crossRate", crossRate.toPlainString());
		return out;
	}

	private static BigDecimal rate(Params params, CurrencyType currency, String name) {
		if (currency == CurrencyType.TWD) {
			return BigDecimal.ONE;
		}
		return params.decimal(name, currency.getLabel() + "匯率", Values.MIN_EXCHANGE_RATE, Values.MAX_EXCHANGE_RATE);
	}
}
