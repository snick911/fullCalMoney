package com.fullmoney.web;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 最小的 JSON 輸出，只支援 API 用到的型別
 */
public final class Json {

	private Json() {
	}

	public static String write(Object value) {
		StringBuilder out = new StringBuilder(256);
		append(out, value);
		return out.toString();
	}

	private static void append(StringBuilder out, Object value) {
		if (value == null) {
			out.append("null");
		} else if (value instanceof String text) {
			appendString(out, text);
		} else if (value instanceof BigDecimal decimal) {
			out.append(decimal.toPlainString());
		} else if (value instanceof Double || value instanceof Float) {
			double number = ((Number) value).doubleValue();
			out.append(Double.isFinite(number) ? BigDecimal.valueOf(number).toPlainString() : "null");
		} else if (value instanceof Number || value instanceof Boolean) {
			out.append(value);
		} else if (value instanceof Enum<?> constant) {
			appendString(out, constant.name());
		} else if (value instanceof Map<?, ?> map) {
			appendMap(out, map);
		} else if (value instanceof Iterable<?> items) {
			out.append('[');
			boolean first = true;
			for (Object item : items) {
				if (!first) {
					out.append(',');
				}
				append(out, item);
				first = false;
			}
			out.append(']');
		} else if (value instanceof Object[] items) {
			out.append('[');
			for (int i = 0; i < items.length; i++) {
				if (i > 0) {
					out.append(',');
				}
				append(out, items[i]);
			}
			out.append(']');
		} else {
			throw new IllegalArgumentException("Unsupported JSON type: " + value.getClass().getName());
		}
	}

	private static void appendMap(StringBuilder out, Map<?, ?> map) {
		out.append('{');
		boolean first = true;
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (!first) {
				out.append(',');
			}
			appendString(out, String.valueOf(entry.getKey()));
			out.append(':');
			append(out, entry.getValue());
			first = false;
		}
		out.append('}');
	}

	private static void appendString(StringBuilder out, String text) {
		out.append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
			case '"' -> out.append("\\\"");
			case '\\' -> out.append("\\\\");
			case '\n' -> out.append("\\n");
			case '\r' -> out.append("\\r");
			case '\t' -> out.append("\\t");
			case '<' -> out.append("\\u003c");
			case '>' -> out.append("\\u003e");
			case '&' -> out.append("\\u0026");
			default -> {
				if (c < 0x20 || c == ' ' || c == ' ') {
					out.append(String.format("\\u%04x", (int) c));
				} else {
					out.append(c);
				}
			}
			}
		}
		out.append('"');
	}
}
