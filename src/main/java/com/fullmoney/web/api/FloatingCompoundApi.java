package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.SaveMoney;
import com.fullmoney.bean.SaveMoneyResult;
import com.fullmoney.service.FloatingMoneyCalculator;
import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.BadRequestException;
import com.fullmoney.web.Params;

/**
 * 複利試算（浮動）：每年的年利率與額外增加投入在區間內隨機取值
 *
 * <p>參數：initialAmount 原始本金、baseAmount 每年固定投入金額、addMin / addMax 每年額外增加投入的區間、
 * rateMin / rateMax 年利率區間(%)，可為負數、years 總年數、seed 亂數種子（相同種子結果相同，不送出時隨機產生）、
 * round=1 金額四捨五入到整數</p>
 * <p>rows 每列為 [年度, 年利率(%), 額外增加, 本金加入, 本年利息, 純本金部位, 累積財富]，seed 為實際使用的種子</p>
 */
public final class FloatingCompoundApi extends ApiEndpoint {

	/** 網頁產生 0 到此值之間的種子，與 Params.integer 的 9 位數上限相同 */
	private static final int MAX_SEED = 999_999_999;

	private static final int MAX_YEAR = 100;

	private final FloatingMoneyCalculator calculator = new FloatingMoneyCalculator();

	public FloatingCompoundApi() {
		super("/api/floating");
	}

	@Override
	protected Object respond(Params params) {
		BigDecimal initialAmount = params.decimal("initialAmount", "原始本金", "0", Values.MAX_MONEY);
		BigDecimal baseAmount = params.decimal("baseAmount", "每年固定投入金額", "0", Values.MAX_MONEY);
		BigDecimal addMin = params.decimal("addMin", "每年額外增加投入的最低值", "0", Values.MAX_MONEY);
		BigDecimal addMax = params.decimal("addMax", "每年額外增加投入的最高值", "0", Values.MAX_MONEY);
		BigDecimal rateMin = params.decimal("rateMin", "期望年利率的最低值", "-100", "100");
		BigDecimal rateMax = params.decimal("rateMax", "期望年利率的最高值", "-100", "100");
		int years = params.integer("years", "總年數", 1, MAX_YEAR);
		//送出種子才能重現同一組結果；沒送出時（直接呼叫 API）隨機產生
		int seed = params.list("seed").isEmpty() ? (int) (Math.random() * MAX_SEED) : params.integer("seed", "亂數種子", 0, MAX_SEED);
		boolean whole = Values.wholeMoney(params);
		if (addMax.compareTo(addMin) < 0) {
			throw new BadRequestException("addMax", "每年額外增加投入的最高值不可小於最低值。");
		}
		if (rateMax.compareTo(rateMin) < 0) {
			throw new BadRequestException("rateMax", "期望年利率的最高值不可小於最低值。");
		}

		SaveMoneyResult result = calculator.calculate(initialAmount, baseAmount, addMin, addMax, rateMin.movePointLeft(2), rateMax.movePointLeft(2),
				years, seed);

		List<Object[]> rows = new ArrayList<>(years);
		BigDecimal rateSum = BigDecimal.ZERO;
		for (SaveMoney year : result.getYearList()) {
			rateSum = rateSum.add(year.getInterestRate());
			rows.add(new Object[] { year.getTheYear(), year.getInterestRate().movePointRight(2), Values.money(year.getAddAmount(), whole),
					Values.money(year.getAmount(), whole), Values.money(year.getInterest(), whole), Values.money(year.getTotalBaseAmount(), whole),
					Values.money(year.getTotalSumAmount(), whole) });
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("decimals", Values.moneyDecimals(whole));
		out.put("seed", seed);
		out.put("totalBaseAmount", Values.money(result.getTotalBaseAmount(), whole));
		out.put("gains", Values.money(result.getGains(), whole));
		out.put("gainRate", Values.ratio(result.getGainRate()));
		out.put("averageRate", Values.ratio(rateSum.divide(BigDecimal.valueOf(years), 10, RoundingMode.HALF_UP)));
		out.put("totalSumAmount", Values.money(result.getTotalSumAmount(), whole));
		out.put("rows", rows);
		return out;
	}
}
