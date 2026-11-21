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
 * <p>參數：baseAmount 第一年投入本金、rate 年利率(%)、addAmount 每年投入增加金額、years 總年數</p>
 * <p>rows 每列為 [年度, 本金加入, 本年利息, 純本金部位, 累積財富]</p>
 */
public final class CompoundApi extends ApiEndpoint {

	private final MoneyCalculator calculator = new MoneyCalculator();

	public CompoundApi() {
		super("/api/compound");
	}

	@Override
	protected Object respond(Params params) {
		BigDecimal baseAmount = params.decimal("baseAmount", "第一年投入本金", "0", Values.MAX_MONEY);
		BigDecimal rate = params.decimal("rate", "年利率", "0", "100");
		BigDecimal addAmount = params.decimal("addAmount", "每年投入增加金額", "0", Values.MAX_MONEY);
		int years = params.integer("years", "總年數", 1, 100);

		SaveMoneyResult result = calculator.calculate(baseAmount, rate.movePointLeft(2), addAmount, years);

		List<Object[]> rows = new ArrayList<>(years);
		for (SaveMoney year : result.getYearList()) {
			rows.add(new Object[] { year.getTheYear(), Values.money(year.getAmount()), Values.money(year.getInterest()),
					Values.money(year.getTotalBaseAmount()), Values.money(year.getTotalSumAmount()) });
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("totalBaseAmount", Values.money(result.getTotalBaseAmount()));
		out.put("gains", Values.money(result.getGains()));
		out.put("gainRate", Values.ratio(result.getGainRate()));
		out.put("totalSumAmount", Values.money(result.getTotalSumAmount()));
		out.put("rows", rows);
		return out;
	}
}
