package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.fullmoney.bean.DepositReturn;
import com.fullmoney.bean.DepositRollover;
import com.fullmoney.bean.TimeDeposit;
import com.fullmoney.bean.TimeDepositPeriod;
import com.fullmoney.bean.TimeDepositResult;

/**
 * 依序定存：第 1 筆到期後接著存第 2 筆，以此類推；最後把本息以預估匯率換回指定貨幣計算總報酬
 *
 * <p>利息以月計算：本金 × 年利率 × 月數 ÷ 12，每筆四捨五入到小數兩位。</p>
 */
public class TimeDepositCalculator {

	private static final MathContext PRECISION = MathContext.DECIMAL128;
	private static final int MONEY_SCALE = 2;
	private static final int MONTHS_PER_YEAR = 12;

	public TimeDepositResult calculate(BigDecimal principal, List<TimeDeposit> deposits, DepositRollover rollover) {
		List<TimeDepositPeriod> periods = new ArrayList<>(deposits.size());
		BigDecimal balance = principal;
		BigDecimal totalInterest = BigDecimal.ZERO;
		int month = 0;
		for (int i = 0; i < deposits.size(); i++) {
			TimeDeposit deposit = deposits.get(i);
			BigDecimal base = rollover == DepositRollover.COMPOUND ? balance : principal;
			BigDecimal interest = interest(base, deposit.getAnnualRate(), deposit.getMonths());
			totalInterest = totalInterest.add(interest);
			periods.add(new TimeDepositPeriod(i + 1, month + 1, month + deposit.getMonths(), deposit.getMonths(), deposit.getAnnualRate(), base, interest,
					totalInterest));
			balance = base.add(interest);
			month += deposit.getMonths();
		}
		return new TimeDepositResult(periods, principal, totalInterest, month);
	}

	/**
	 * @param annualRate 年利率，比例 ex. 0.035
	 */
	public BigDecimal interest(BigDecimal principal, BigDecimal annualRate, int months) {
		return principal.multiply(annualRate).multiply(BigDecimal.valueOf(months)).divide(BigDecimal.valueOf(MONTHS_PER_YEAR), PRECISION)
				.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 本息換回指定貨幣
	 *
	 * @param cost 投入成本（指定貨幣）
	 * @param rate 到期時 1 單位定存幣別可換多少指定貨幣
	 */
	public DepositReturn settle(BigDecimal cost, TimeDepositResult result, BigDecimal rate) {
		BigDecimal principalValue = money(result.getPrincipal().multiply(rate));
		BigDecimal interestValue = money(result.getTotalInterest().multiply(rate));
		BigDecimal value = principalValue.add(interestValue);
		BigDecimal profit = value.subtract(cost);
		BigDecimal totalReturn = cost.signum() == 0 ? null : profit.divide(cost, PRECISION);
		return new DepositReturn(rate, value, principalValue, interestValue, principalValue.subtract(cost), profit, totalReturn,
				annualize(value, cost, result.getMonths()));
	}

	/**
	 * @return 換回後剛好不賺不賠的到期匯率（1 單位定存幣別可換多少指定貨幣），到期本息為 0 時為 null
	 */
	public BigDecimal breakEvenRate(BigDecimal cost, TimeDepositResult result) {
		BigDecimal amount = result.getFinalAmount();
		return amount.signum() == 0 ? null : cost.divide(amount, PRECISION);
	}

	/**
	 * @return 年化報酬率 = (換回金額 ÷ 投入成本)^(12 ÷ 月數) − 1，無法計算時為 null
	 */
	static BigDecimal annualize(BigDecimal value, BigDecimal cost, int months) {
		if (cost.signum() <= 0 || months <= 0 || value.signum() < 0) {
			return null;
		}
		double annual = Math.pow(value.divide(cost, PRECISION).doubleValue(), (double) MONTHS_PER_YEAR / months) - 1;
		return Double.isFinite(annual) ? BigDecimal.valueOf(annual) : null;
	}

	private static BigDecimal money(BigDecimal amount) {
		return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
	}
}
