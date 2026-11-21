package com.fullmoney.bean;

import java.math.BigDecimal;

/**
 * 房貸提前還款，在指定期數正常繳款後多還本金
 */
public class MortgagePrepayment {

	private final int period;

	private final BigDecimal amount;

	public MortgagePrepayment(int period, BigDecimal amount) {
		this.period = period;
		this.amount = amount;
	}

	public int getPeriod() {
		return period;
	}

	public BigDecimal getAmount() {
		return amount;
	}
}
