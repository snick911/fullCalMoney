package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.fullmoney.bean.BondCondition;
import com.fullmoney.bean.BondPayment;
import com.fullmoney.bean.BondResult;
import com.fullmoney.bean.BondReturn;

/**
 * 債券持有到期試算，假設在配息日買進，每期配息相同
 * 每筆金額四捨五入到小數兩位，合計由四捨五入後的金額相加
 */
public class BondCalculator {

	private static final MathContext PRECISION = MathContext.DECIMAL128;
	private static final int MONEY_SCALE = 2;
	private static final int BISECTION_STEPS = 200;
	private static final double LOWEST_PERIOD_RATE = -0.99;
	private static final double HIGHEST_PERIOD_RATE = 1e6;

	public BondResult calculate(BondCondition condition) {
		int timesPerYear = condition.getFrequency().getTimesPerYear();
		int periods = condition.getYear() * timesPerYear;
		BigDecimal face = condition.getFaceValue();

		BigDecimal cost = toMoney(face.multiply(condition.getBuyPrice()).multiply(BigDecimal.ONE.add(condition.getFeeRate())));
		BigDecimal coupon = toMoney(face.multiply(condition.getCouponRate()).multiply(BigDecimal.ONE.subtract(condition.getTaxRate()))
				.divide(BigDecimal.valueOf(timesPerYear), PRECISION));
		BigDecimal redemption = toMoney(face.multiply(condition.getRedemptionPrice()));
		BigDecimal homeCost = toMoney(cost.multiply(condition.getBuyExchangeRate()));
		BigDecimal homeCoupon = toMoney(coupon.multiply(condition.getCouponExchangeRate()));
		BigDecimal homeRedemption = toMoney(redemption.multiply(condition.getRedemptionExchangeRate()));

		List<BondPayment> payments = new ArrayList<>(periods);
		BigDecimal homeReceived = BigDecimal.ZERO;
		for (int period = 1; period <= periods; period++) {
			boolean last = period == periods;
			homeReceived = homeReceived.add(homeCoupon);
			if (last) {
				homeReceived = homeReceived.add(homeRedemption);
			}
			payments.add(new BondPayment(period, coupon, last ? redemption : BigDecimal.ZERO, homeCoupon,
					last ? homeRedemption : BigDecimal.ZERO, homeReceived, ratio(homeReceived.subtract(homeCost), homeCost)));
		}

		BondReturn bondReturn = bondReturn(cost, coupon, redemption, periods, condition.getYear(), timesPerYear);
		BondReturn homeReturn = bondReturn(homeCost, homeCoupon, homeRedemption, periods, condition.getYear(), timesPerYear);
		BigDecimal yieldToMaturity = cost.signum() == 0 ? null
				: BigDecimal.valueOf(periodRate(cost, coupon, redemption, periods) * timesPerYear);
		BigDecimal exchangeGain = toMoney(homeReturn.getProfit().subtract(bondReturn.getProfit().multiply(condition.getBuyExchangeRate())));
		return new BondResult(payments, bondReturn, homeReturn, yieldToMaturity, exchangeGain);
	}

	private BondReturn bondReturn(BigDecimal cost, BigDecimal coupon, BigDecimal redemption, int periods, int year, int timesPerYear) {
		BigDecimal couponTotal = coupon.multiply(BigDecimal.valueOf(periods));
		BigDecimal profit = couponTotal.add(redemption).subtract(cost);
		BigDecimal couponReturn = ratio(couponTotal, cost);
		BigDecimal annualCouponReturn = couponReturn == null ? null : couponReturn.divide(BigDecimal.valueOf(year), PRECISION);
		BigDecimal annualReturn = cost.signum() == 0 ? null
				: BigDecimal.valueOf(Math.pow(1 + periodRate(cost, coupon, redemption, periods), timesPerYear) - 1);
		return new BondReturn(cost, couponTotal, redemption, profit, couponReturn, annualCouponReturn, ratio(profit, cost), annualReturn);
	}

	/**
	 * 買入後的現金流都不為負，淨現值隨利率遞減，答案唯一，用二分法求解
	 *
	 * @return 每期報酬率，完全沒有收回任何金額時為 -1
	 */
	private double periodRate(BigDecimal cost, BigDecimal coupon, BigDecimal redemption, int periods) {
		if (coupon.signum() == 0 && redemption.signum() == 0) {
			return -1;
		}
		double costValue = cost.doubleValue();
		double couponValue = coupon.doubleValue();
		double redemptionValue = redemption.doubleValue();
		double low = LOWEST_PERIOD_RATE;
		double high = 1;
		while (netPresentValue(high, costValue, couponValue, redemptionValue, periods) > 0 && high < HIGHEST_PERIOD_RATE) {
			high *= 2;
		}
		for (int step = 0; step < BISECTION_STEPS; step++) {
			double middle = (low + high) / 2;
			if (netPresentValue(middle, costValue, couponValue, redemptionValue, periods) > 0) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return (low + high) / 2;
	}

	private double netPresentValue(double rate, double cost, double coupon, double redemption, int periods) {
		double value = redemption > 0 ? redemption / Math.pow(1 + rate, periods) : 0;
		if (coupon > 0) {
			for (int period = 1; period <= periods; period++) {
				value += coupon / Math.pow(1 + rate, period);
			}
		}
		return value - cost;
	}

	private BigDecimal ratio(BigDecimal amount, BigDecimal base) {
		return base.signum() == 0 ? null : amount.divide(base, PRECISION);
	}

	private BigDecimal toMoney(BigDecimal amount) {
		return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
	}
}
