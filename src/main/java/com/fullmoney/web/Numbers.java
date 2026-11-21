package com.fullmoney.web;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 千分位格式。不用 NumberFormat，瀏覽器端引擎（TeaVM）才能共用，結果也不受語系影響
 */
public final class Numbers {

	private Numbers() {
	}

	/**
	 * @return ex. 1234567.5 → 1,234,567.5；小數最多 maxFractionDigits 位，去掉結尾的 0
	 */
	public static String group(BigDecimal value, int maxFractionDigits) {
		String plain = value.setScale(maxFractionDigits, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
		boolean negative = plain.startsWith("-");
		if (negative) {
			plain = plain.substring(1);
		}
		int dot = plain.indexOf('.');
		String integer = dot < 0 ? plain : plain.substring(0, dot);
		StringBuilder out = new StringBuilder(plain.length() + integer.length() / 3 + 1);
		if (negative) {
			out.append('-');
		}
		for (int i = 0; i < integer.length(); i++) {
			if (i > 0 && (integer.length() - i) % 3 == 0) {
				out.append(',');
			}
			out.append(integer.charAt(i));
		}
		if (dot >= 0) {
			out.append(plain, dot, plain.length());
		}
		return out.toString();
	}
}
