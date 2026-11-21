package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.fullmoney.bean.SaveMoney;
import com.fullmoney.bean.SaveMoneyResult;

/**
 * 複利試算，每年年初投入本金，年底依利率計息
 */
public class MoneyCalculator {

	/**
	 * @param baseAmount 第一年投入本金
	 * @param rate 每年利率 ex. 0.04
	 * @param addAmount 每年投入本金額外增加金額
	 * @param totalYear 總年數
	 */
	public SaveMoneyResult calculate(BigDecimal baseAmount, BigDecimal rate, BigDecimal addAmount, int totalYear) {
		List<SaveMoney> yearList=new ArrayList<>();
		BigDecimal yearAmount=baseAmount;
		BigDecimal totalSumAmount=BigDecimal.ZERO;
		BigDecimal totalBaseAmount=BigDecimal.ZERO;

		for(int i=0;i<totalYear;i++) {
			//本年投入的利息 + 累積錢的利息
			BigDecimal interest=yearAmount.add(totalSumAmount).multiply(rate);
			totalBaseAmount=totalBaseAmount.add(yearAmount);
			totalSumAmount=totalSumAmount.add(yearAmount).add(interest);

			SaveMoney saveMoney=new SaveMoney();
			saveMoney.setTheYear(i+1);
			saveMoney.setAmount(yearAmount);
			saveMoney.setInterestRate(rate);
			saveMoney.setInterest(interest);
			saveMoney.setTotalBaseAmount(totalBaseAmount);
			saveMoney.setTotalSumAmount(totalSumAmount);
			yearList.add(saveMoney);

			yearAmount=yearAmount.add(addAmount);
		}

		BigDecimal gains=totalSumAmount.subtract(totalBaseAmount);
		BigDecimal gainRate=BigDecimal.ZERO;
		if(totalBaseAmount.signum()!=0) {
			gainRate=gains.divide(totalBaseAmount, 6, RoundingMode.HALF_UP);
		}
		return new SaveMoneyResult(yearList, totalBaseAmount, totalSumAmount, gains, gainRate);
	}
}
