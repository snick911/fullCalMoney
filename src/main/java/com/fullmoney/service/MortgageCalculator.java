package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.MortgagePayment;
import com.fullmoney.bean.MortgagePrepayment;
import com.fullmoney.bean.MortgageRateChange;
import com.fullmoney.bean.MortgageResult;
import com.fullmoney.bean.PrepaymentMode;
import com.fullmoney.bean.RepaymentMethod;

/**
 * 房貸試算，按月計息，寬限期內只繳利息
 * 每期金額四捨五入到元，尾差由最後一期吸收，本金合計必等於貸款金額
 */
public class MortgageCalculator {

	private static final int MONTHS_PER_YEAR = 12;
	private static final MathContext PRECISION = MathContext.DECIMAL128;

	/**
	 * @param loanAmount 貸款金額
	 * @param annualRate 年利率 ex. 0.022
	 * @param totalYear 貸款年限(含寬限期)
	 * @param graceYear 寬限期年數
	 * @throws IllegalArgumentException 寬限期不短於貸款年限
	 */
	public MortgageResult calculate(BigDecimal loanAmount, BigDecimal annualRate, int totalYear, int graceYear, RepaymentMethod method) {
		return calculate(loanAmount, annualRate, totalYear, graceYear, method,
				Collections.emptyList(), Collections.emptyList(), PrepaymentMode.SHORTEN_TERM);
	}

	/**
	 * 利率變動時，以當時的剩餘本金與剩餘期數重算每期攤還金額
	 *
	 * @param rateChanges 分段利率，同一期有多筆時以後面的為準
	 * @param prepayments 提前還款，同一期有多筆時合計，超過剩餘本金的部分不計
	 * @param mode 提前還款後的攤還方式
	 * @throws IllegalArgumentException 寬限期不短於貸款年限
	 */
	public MortgageResult calculate(BigDecimal loanAmount, BigDecimal annualRate, int totalYear, int graceYear, RepaymentMethod method,
			List<MortgageRateChange> rateChanges, List<MortgagePrepayment> prepayments, PrepaymentMode mode) {
		if (graceYear >= totalYear) {
			throw new IllegalArgumentException("寬限期必須短於貸款年限");
		}
		Map<Integer, BigDecimal> ratesByPeriod = new HashMap<>();
		for (MortgageRateChange change : rateChanges) {
			ratesByPeriod.put(change.getStartPeriod(), change.getAnnualRate());
		}
		Map<Integer, BigDecimal> prepaymentsByPeriod = new HashMap<>();
		for (MortgagePrepayment prepayment : prepayments) {
			prepaymentsByPeriod.merge(prepayment.getPeriod(), prepayment.getAmount(), BigDecimal::add);
		}

		int gracePeriods = graceYear * MONTHS_PER_YEAR;
		int lastPeriod = totalYear * MONTHS_PER_YEAR;
		BigDecimal currentRate = annualRate;
		BigDecimal monthRate = monthRate(currentRate);
		BigDecimal installment = installment(method, loanAmount, monthRate, lastPeriod - gracePeriods);

		List<MortgagePayment> payments = new ArrayList<>(lastPeriod);
		BigDecimal balance = loanAmount;
		BigDecimal totalInterest = BigDecimal.ZERO;
		for (int period = 1; period <= lastPeriod && balance.signum() > 0; period++) {
			BigDecimal changedRate = ratesByPeriod.get(period);
			if (changedRate != null) {
				currentRate = changedRate;
				monthRate = monthRate(currentRate);
				installment = installment(method, balance, monthRate, lastPeriod - Math.max(period - 1, gracePeriods));
			}
			BigDecimal interest = toDollar(balance.multiply(monthRate));
			BigDecimal principal;
			if (period <= gracePeriods) {
				principal = BigDecimal.ZERO;
			} else if (period == lastPeriod) {
				principal = balance;
			} else {
				principal = scheduledPrincipal(method, installment, interest, balance);
			}
			balance = balance.subtract(principal);
			BigDecimal prepayment = prepaymentsByPeriod.getOrDefault(period, BigDecimal.ZERO).min(balance);
			balance = balance.subtract(prepayment);
			totalInterest = totalInterest.add(interest);
			payments.add(new MortgagePayment(period, currentRate, principal, interest, prepayment, balance));

			if (prepayment.signum() > 0 && balance.signum() > 0) {
				int settledPeriod = Math.max(period, gracePeriods);
				if (mode == PrepaymentMode.REDUCE_PAYMENT) {
					installment = installment(method, balance, monthRate, lastPeriod - settledPeriod);
				} else {
					lastPeriod = settledPeriod + periodsToClear(method, balance, monthRate, installment, lastPeriod - settledPeriod);
				}
			}
		}

		BigDecimal gracePayment = gracePeriods > 0 && !payments.isEmpty() ? payments.get(0).getPayment() : null;
		BigDecimal firstPayment = payments.size() > gracePeriods ? payments.get(gracePeriods).getPayment() : null;
		return new MortgageResult(payments, gracePayment, firstPayment, totalInterest, loanAmount.add(totalInterest));
	}

	/**
	 * @return 每期攤還金額：本息平均為月付金，本金平均為每期本金
	 */
	private BigDecimal installment(RepaymentMethod method, BigDecimal balance, BigDecimal monthRate, int periods) {
		if (method == RepaymentMethod.EQUAL_PAYMENT) {
			return equalPayment(balance, monthRate, periods);
		}
		return toDollar(balance.divide(BigDecimal.valueOf(periods), PRECISION));
	}

	private BigDecimal scheduledPrincipal(RepaymentMethod method, BigDecimal installment, BigDecimal interest, BigDecimal balance) {
		BigDecimal principal = method == RepaymentMethod.EQUAL_PAYMENT ? installment.subtract(interest) : installment;
		return principal.max(BigDecimal.ZERO).min(balance);
	}

	/**
	 * @return 維持每期攤還金額時，還清剩餘本金需要的期數，最多為 limit
	 */
	private int periodsToClear(RepaymentMethod method, BigDecimal balance, BigDecimal monthRate, BigDecimal installment, int limit) {
		BigDecimal remaining = balance;
		for (int periods = 1; periods < limit; periods++) {
			BigDecimal interest = toDollar(remaining.multiply(monthRate));
			remaining = remaining.subtract(scheduledPrincipal(method, installment, interest, remaining));
			if (remaining.signum() == 0) {
				return periods;
			}
		}
		return limit;
	}

	/**
	 * 本息平均攤還的月付金 = 本金 * 月利率 * (1+月利率)^期數 / ((1+月利率)^期數 - 1)
	 */
	private BigDecimal equalPayment(BigDecimal loanAmount, BigDecimal monthRate, int periods) {
		if (monthRate.signum() == 0) {
			return toDollar(loanAmount.divide(BigDecimal.valueOf(periods), PRECISION));
		}
		BigDecimal growth = BigDecimal.ONE.add(monthRate).pow(periods, PRECISION);
		return toDollar(loanAmount.multiply(monthRate).multiply(growth).divide(growth.subtract(BigDecimal.ONE), PRECISION));
	}

	private BigDecimal monthRate(BigDecimal annualRate) {
		return annualRate.divide(BigDecimal.valueOf(MONTHS_PER_YEAR), PRECISION);
	}

	private BigDecimal toDollar(BigDecimal amount) {
		return amount.setScale(0, RoundingMode.HALF_UP);
	}
}
