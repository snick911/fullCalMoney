package com.fullmoney.web;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析 application/x-www-form-urlencoded 參數，並依欄位範圍檢查
 *
 * <p>數字只接受一般寫法（可含千分位逗號與全形數字），不接受科學記號，
 * 避免 1e-999999999 這類輸入讓 BigDecimal 運算耗盡資源。</p>
 */
public final class Params {

	private static final int MAX_PAIRS = 2000;
	private static final int MAX_FRACTION_DIGITS_IN_MESSAGE = 4;
	private static final Pattern DECIMAL = Pattern.compile("-?\\d{1,13}(\\.\\d{1,8})?");
	private static final Pattern INTEGER = Pattern.compile("-?\\d{1,9}");

	private final Map<String, List<String>> values = new LinkedHashMap<>();

	private Params() {
	}

	public static Params parse(String raw) {
		Params params = new Params();
		if (raw == null || raw.isEmpty()) {
			return params;
		}
		String[] pairs = raw.split("&");
		if (pairs.length > MAX_PAIRS) {
			throw new BadRequestException(null, "參數數量過多。");
		}
		for (String pair : pairs) {
			if (pair.isEmpty()) {
				continue;
			}
			int equals = pair.indexOf('=');
			String key = decode(equals < 0 ? pair : pair.substring(0, equals));
			String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
			params.values.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
		}
		return params;
	}

	private static String decode(String text) {
		try {
			return URLDecoder.decode(text, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			throw new BadRequestException(null, "參數格式錯誤。");
		}
	}

	/** @return 同名參數的所有值，依送出順序 */
	public List<String> list(String name) {
		return Collections.unmodifiableList(values.getOrDefault(name, Collections.emptyList()));
	}

	public BigDecimal decimal(String name, String label, String min, String max) {
		return toDecimal(first(name), name, label, new BigDecimal(min), new BigDecimal(max));
	}

	public int integer(String name, String label, int min, int max) {
		return toInteger(first(name), name, label, min, max);
	}

	/**
	 * @param options 可選的值，傳入列舉的 values()。不用 Enum.valueOf，瀏覽器端引擎才不需要反射
	 */
	public <E extends Enum<E>> E choice(String name, String label, E[] options) {
		String value = first(name);
		if (value == null || value.isBlank()) {
			throw new BadRequestException(name, "請選擇" + label + "。");
		}
		String code = value.trim();
		for (E option : options) {
			if (option.name().equals(code)) {
				return option;
			}
		}
		throw new BadRequestException(name, label + "的選項不正確。");
	}

	/** 沒有送出此參數時用預設值，有送出但不正確時仍視為錯誤 */
	public <E extends Enum<E>> E choice(String name, String label, E[] options, E fallback) {
		String value = first(name);
		return value == null || value.isBlank() ? fallback : choice(name, label, options);
	}

	public static BigDecimal toDecimal(String raw, String field, String label, BigDecimal min, BigDecimal max) {
		String text = normalize(raw);
		if (text.isEmpty()) {
			throw new BadRequestException(field, "請輸入" + label + "。");
		}
		if (!DECIMAL.matcher(text).matches()) {
			throw new BadRequestException(field, label + "請輸入數字，例如 1,234.5。");
		}
		BigDecimal value = new BigDecimal(text);
		if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
			throw new BadRequestException(field, label + "必須介於 " + format(min) + " 到 " + format(max) + "。");
		}
		return value;
	}

	public static int toInteger(String raw, String field, String label, int min, int max) {
		String text = normalize(raw);
		if (text.isEmpty()) {
			throw new BadRequestException(field, "請輸入" + label + "。");
		}
		if (!INTEGER.matcher(text).matches()) {
			throw new BadRequestException(field, label + "請輸入整數。");
		}
		int value = Integer.parseInt(text);
		if (value < min || value > max) {
			throw new BadRequestException(field, label + "必須介於 " + format(BigDecimal.valueOf(min)) + " 到 " + format(BigDecimal.valueOf(max)) + "。");
		}
		return value;
	}

	private String first(String name) {
		List<String> list = values.get(name);
		return list == null || list.isEmpty() ? null : list.get(0);
	}

	/** 全形英數轉半形，去掉千分位逗號、底線與空白 */
	private static String normalize(String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c >= '！' && c <= '～') {
				c = (char) (c - 0xFEE0);
			}
			if (c == ',' || c == '_' || c == '　' || c == ' ' || Character.isWhitespace(c)) {
				continue;
			}
			out.append(c);
		}
		return out.toString();
	}

	private static String format(BigDecimal value) {
		return Numbers.group(value, MAX_FRACTION_DIGITS_IN_MESSAGE);
	}
}
