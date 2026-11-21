package com.fullmoney.web.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.AppVersion;
import com.fullmoney.bean.CouponFrequency;
import com.fullmoney.bean.CurrencyType;
import com.fullmoney.bean.PrepaymentMode;
import com.fullmoney.bean.RepaymentMethod;
import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.Params;

/**
 * 網頁下拉選單的選項與版本號，選項只定義在 bean 的列舉，網頁不重複寫死
 */
public final class MetaApi extends ApiEndpoint {

	private final Map<String, Object> meta = buildMeta();

	public MetaApi() {
		super("/api/meta");
	}

	@Override
	protected Object respond(Params params) {
		return meta;
	}

	private static Map<String, Object> buildMeta() {
		List<Map<String, Object>> currencies = new ArrayList<>();
		for (CurrencyType currency : CurrencyType.values()) {
			currencies.add(option(currency.name(), currency.getLabel(), "sampleRate", currency.getSampleRate()));
		}
		List<Map<String, Object>> frequencies = new ArrayList<>();
		for (CouponFrequency frequency : CouponFrequency.values()) {
			frequencies.add(option(frequency.name(), frequency.getLabel(), "timesPerYear", frequency.getTimesPerYear()));
		}
		List<Map<String, Object>> methods = new ArrayList<>();
		for (RepaymentMethod method : RepaymentMethod.values()) {
			methods.add(option(method.name(), method.getLabel(), null, null));
		}
		List<Map<String, Object>> modes = new ArrayList<>();
		for (PrepaymentMode mode : PrepaymentMode.values()) {
			modes.add(option(mode.name(), mode.getLabel(), null, null));
		}

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("name", AppVersion.NAME);
		meta.put("version", AppVersion.VERSION);
		meta.put("currencies", currencies);
		meta.put("frequencies", frequencies);
		meta.put("repaymentMethods", methods);
		meta.put("prepaymentModes", modes);
		return meta;
	}

	private static Map<String, Object> option(String code, String label, String extraKey, Object extraValue) {
		Map<String, Object> option = new LinkedHashMap<>();
		option.put("code", code);
		option.put("label", label);
		if (extraKey != null) {
			option.put(extraKey, extraValue);
		}
		return option;
	}
}
