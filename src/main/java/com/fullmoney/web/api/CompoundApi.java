package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.SaveMoney;
import com.fullmoney.bean.SaveMoneyResult;
import com.fullmoney.service.MoneyCalculator;
import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.Params;

/**
 * 複利試算
 *
 * <p>參數：initialAmount 原始本金、baseAmount 每年固定投入金額、rate 年利率(%)、addAmount 每年額外增加投入、years 總年數、
 * round=1 金額四捨五入到整數（不送出時到小數兩位）</p>
 * <p>rows 每列為 [年度, 本金加入, 本年利息, 純本金部位, 累積財富]，decimals 為金額的小數位數</p>
 */
public final class CompoundApi extends ApiEndpoint {

	private final MoneyCalculator calculator = new MoneyCalculator();

	public CompoundApi() {
		super("/api/compound");
	}

	@Override
	protected Object respond(Params params) {
		BigDecimal initialAmount = params.decimal("initialAmount", "原始本金", "0", Values.MAX_MONEY);
		BigDecimal baseAmount = params.decimal("baseAmount", "每年固定投入金額", "0", Values.MAX_MONEY);
		BigDecimal rate = params.decimal("rate", "年利率", "0", "100");
		BigDecimal addAmount = params.decimal("addAmount", "每年額外增加投入", "0", Values.MAX_MONEY);
		int years = params.integer("years", "總年數", 1, 100);
		boolean whole = Values.wholeMoney(params);

		SaveMoneyResult result = calculator.calculate(initialAmount, baseAmount, rate.movePointLeft(2), addAmount, years);

		List<Object[]> rows = new ArrayList<>(years);
		for (SaveMoney year : result.getYearList()) {
			rows.add(new Object[] { year.getTheYear(), Values.money(year.getAmount(), whole), Values.money(year.getInterest(), whole),
					Values.money(year.getTotalBaseAmount(), whole), Values.money(year.getTotalSumAmount(), whole) });
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("decimals", Values.moneyDecimals(whole));
		out.put("totalBaseAmount", Values.money(result.getTotalBaseAmount(), whole));
		out.put("gains", Values.money(result.getGains(), whole));
		out.put("gainRate", Values.ratio(result.getGainRate()));
		out.put("totalSumAmount", Values.money(result.getTotalSumAmount(), whole));
		out.put("rows", rows);
		return out;
	}
}
