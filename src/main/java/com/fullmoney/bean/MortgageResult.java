package com.fullmoney.bean;

import java.math.BigDecimal;
import java.util.List;

/**
 * 房貸試算結果
 */
public class MortgageResult {

	private final List<MortgagePayment> payments;

	/**
	 * 寬限期內的月付金，沒有寬限期時為 null
	 * */
	private final BigDecimal gracePayment;

	/**
	 * 開始攤還本金後第一期的月付金，在寬限期內就還清時為 null
	 * */
	private final BigDecimal firstPayment;

	private final BigDecimal totalInterest;

	private final BigDecimal totalPayment;

	public MortgageResult(List<MortgagePayment> payments, BigDecimal gracePayment, BigDecimal firstPayment,
			BigDecimal totalInterest, BigDecimal totalPayment) {
		this.payments = payments;
		this.gracePayment = gracePayment;
		this.firstPayment = firstPayment;
		this.totalInterest = totalInterest;
		this.totalPayment = totalPayment;
	}

	public List<MortgagePayment> getPayments() {
		return payments;
	}

	public BigDecimal getGracePayment() {
		return gracePayment;
	}

	public BigDecimal getFirstPayment() {
		return firstPayment;
	}

	public BigDecimal getTotalInterest() {
		return totalInterest;
	}

	public BigDecimal getTotalPayment() {
		return totalPayment;
	}
}
