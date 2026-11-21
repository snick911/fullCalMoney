package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.fullmoney.bean.SaveMoney;
import com.fullmoney.bean.SaveMoneyResult;

/**
 * 複利試算：第 1 年年初投入原始本金，每年年初投入固定金額（每年再額外增加固定金額），年底依利率計息
 */
public class MoneyCalculator {

	/**
	 * @param initialAmount 原始本金，只在第 1 年年初投入一次
	 * @param baseAmount 每年固定投入金額
	 * @param rate 每年利率 ex. 0.04
	 * @param addAmount 每年額外增加投入，第 2 年起每年比前一年多投入此金額
	 * @param totalYear 總年數
	 */
	public SaveMoneyResult calculate(BigDecimal initialAmount, BigDecimal baseAmount, BigDecimal rate, BigDecimal addAmount, int totalYear) {
		List<BigDecimal> addAmounts=new ArrayList<>(totalYear);
		List<BigDecimal> rates=new ArrayList<>(totalYear);
		for(int i=0;i<totalYear;i++) {
			addAmounts.add(i==0 ? BigDecimal.ZERO : addAmount);
			rates.add(rate);
		}
		return calculate(initialAmount, baseAmount, addAmounts, rates);
	}

	/**
	 * 每年的利率與額外增加投入可以不同
	 *
	 * @param initialAmount 原始本金，只在第 1 年年初投入一次
	 * @param baseAmount 每年固定投入金額
	 * @param addAmounts 第 i+1 年比前一年多投入的金額，第 1 年應為 0
	 * @param rates 第 i+1 年的利率 ex. 0.04，筆數即總年數
	 */
	public SaveMoneyResult calculate(BigDecimal initialAmount, BigDecimal baseAmount, List<BigDecimal> addAmounts, List<BigDecimal> rates) {
		int totalYear=rates.size();
		List<SaveMoney> yearList=new ArrayList<>(totalYear);
		BigDecimal yearAmount=baseAmount;
		BigDecimal totalSumAmount=BigDecimal.ZERO;
		BigDecimal totalBaseAmount=BigDecimal.ZERO;

		for(int i=0;i<totalYear;i++) {
			BigDecimal addAmount=addAmounts.get(i);
			BigDecimal rate=rates.get(i);
			yearAmount=yearAmount.add(addAmount);
			BigDecimal investAmount=i==0 ? initialAmount.add(yearAmount) : yearAmount;
			//本年投入的利息 + 累積錢的利息
			BigDecimal interest=investAmount.add(totalSumAmount).multiply(rate);
			totalBaseAmount=totalBaseAmount.add(investAmount);
			totalSumAmount=totalSumAmount.add(investAmount).add(interest);

			SaveMoney saveMoney=new SaveMoney();
			saveMoney.setTheYear(i+1);
			saveMoney.setAmount(investAmount);
			saveMoney.setAddAmount(addAmount);
			saveMoney.setInterestRate(rate);
			saveMoney.setInterest(interest);
			saveMoney.setTotalBaseAmount(totalBaseAmount);
			saveMoney.setTotalSumAmount(totalSumAmount);
			yearList.add(saveMoney);
		}

		BigDecimal gains=totalSumAmount.subtract(totalBaseAmount);
		BigDecimal gainRate=BigDecimal.ZERO;
		if(totalBaseAmount.signum()!=0) {
			gainRate=gains.divide(totalBaseAmount, 6, RoundingMode.HALF_UP);
		}
		return new SaveMoneyResult(yearList, totalBaseAmount, totalSumAmount, gains, gainRate);
	}
}
