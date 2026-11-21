package com.fullmoney.web.api;

import java.util.Arrays;
import java.util.List;

import com.fullmoney.web.ApiEndpoint;

/**
 * 所有試算 API，網站伺服器與瀏覽器端引擎都由此取得，新增 API 只要加在這裡
 */
public final class ApiEndpoints {

	private ApiEndpoints() {
	}

	public static List<ApiEndpoint> all() {
		return Arrays.asList(new MetaApi(), new CompoundApi(), new FloatingCompoundApi(), new MortgageApi(), new BondApi(), new CurrencyApi());
	}
}
