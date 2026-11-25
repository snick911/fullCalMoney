package com.fullmoney.web.api;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.AppVersion;
import com.fullmoney.bean.CouponFrequency;
import com.fullmoney.bean.CurrencyType;
import com.fullmoney.bean.DcaPreset;
import com.fullmoney.bean.DcaTarget;
import com.fullmoney.bean.DepositRollover;
import com.fullmoney.bean.GlidePath;
import com.fullmoney.bean.LotteryGame;
import com.fullmoney.bean.PlanPreset;
import com.fullmoney.bean.PrepaymentMode;
import com.fullmoney.bean.PurchaseFrequency;
import com.fullmoney.bean.RebalanceMode;
import com.fullmoney.bean.RepaymentMethod;
import com.fullmoney.bean.ReturnDistribution;
import com.fullmoney.bean.StressScenario;
import com.fullmoney.bean.StressTiming;
import com.fullmoney.service.LotteryCalculator;
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
		//投資計畫的模擬設定
		List<Map<String, Object>> glides = new ArrayList<>();
		for (GlidePath glide : GlidePath.values()) {
			glides.add(option(glide.name(), glide.getLabel(), null, null));
		}
		List<Map<String, Object>> rebalances = new ArrayList<>();
		for (RebalanceMode rebalance : RebalanceMode.values()) {
			rebalances.add(option(rebalance.name(), rebalance.getLabel(), null, null));
		}
		List<Map<String, Object>> distributions = new ArrayList<>();
		for (ReturnDistribution distribution : ReturnDistribution.values()) {
			distributions.add(option(distribution.name(), distribution.getLabel(), null, null));
		}
		List<Map<String, Object>> stresses = new ArrayList<>();
		for (StressScenario stress : StressScenario.values()) {
			stresses.add(option(stress.name(), stress.getLabel(), "description", stress.getDescription()));
		}
		List<Map<String, Object>> timings = new ArrayList<>();
		for (StressTiming timing : StressTiming.values()) {
			timings.add(option(timing.name(), timing.getLabel(), null, null));
		}

		//樂透投資：每注價格、開獎日與頭獎的保底與長期平均，網頁用來顯示說明與可買幾注
		LotteryCalculator lottery = new LotteryCalculator();
		List<Map<String, Object>> games = new ArrayList<>();
		for (LotteryGame game : LotteryGame.values()) {
			Map<String, Object> option = option(game.name(), game.getLabel(), "price", game.getPrice());
			option.put("drawsPerWeek", game.getDrawsPerWeek());
			option.put("schedule", game.getSchedule());
			option.put("howTo", game.getHowTo());
			option.put("jackpotMinimum", game.getJackpotMinimum());
			option.put("jackpotAverage", lottery.averageJackpot(game) == null ? null : lottery.averageJackpot(game).setScale(0, RoundingMode.HALF_UP));
			games.add(option);
		}
		List<Map<String, Object>> purchases = new ArrayList<>();
		for (PurchaseFrequency frequency : PurchaseFrequency.values()) {
			purchases.add(option(frequency.name(), frequency.getLabel(), null, null));
		}
		//貨幣投資：定存到期後的續存方式
		List<Map<String, Object>> rollovers = new ArrayList<>();
		for (DepositRollover rollover : DepositRollover.values()) {
			rollovers.add(option(rollover.name(), rollover.getLabel(), null, null));
		}
		//定期定額：標的類型與預設費用（百分比），換類型時網頁帶入
		List<Map<String, Object>> dcaTargets = new ArrayList<>();
		for (DcaTarget target : DcaTarget.values()) {
			Map<String, Object> option = option(target.name(), target.getLabel(), "description", target.getDescription());
			option.put("buyFee", target.getBuyFeeRate().movePointRight(2));
			option.put("minFee", target.getMinFee());
			option.put("sellFee", target.getSellFeeRate().movePointRight(2));
			dcaTargets.add(option);
		}
		//定期定額的推薦範例：按一下帶入所有條件，報酬率以 % 表示
		List<Map<String, Object>> dcaPresets = new ArrayList<>();
		for (DcaPreset preset : DcaPreset.values()) {
			Map<String, Object> option = option(preset.name(), preset.getLabel(), "description", preset.getDescription());
			option.put("target", preset.getTarget().name());
			option.put("targetName", preset.getTarget().getLabel());
			option.put("interval", preset.getInterval());
			option.put("years", preset.getYears());
			option.put("amountMin", preset.getAmountMin());
			option.put("amountMax", preset.getAmountMax());
			option.put("rateMin", preset.getRateMin());
			option.put("rateMax", preset.getRateMax());
			dcaPresets.add(option);
		}
		//投資計畫的推薦範例：欄位名稱與網頁表單相同，按一下帶入
		List<Map<String, Object>> planPresets = new ArrayList<>();
		for (PlanPreset preset : PlanPreset.values()) {
			Map<String, Object> option = option(preset.name(), preset.getLabel(), "description", preset.getDescription());
			option.put("target", preset.getTarget());
			option.put("principal", preset.getPrincipal());
			option.put("monthlyFixed", preset.getMonthlyFixed());
			option.put("floatMin", preset.getFloatMin());
			option.put("floatMax", preset.getFloatMax());
			option.put("years", preset.getYears());
			option.put("months", preset.getMonths());
			option.put("profit", preset.getProfit());
			option.put("age", preset.getAge());
			option.put("glide", preset.getGlide().name());
			option.put("rebalance", preset.getRebalance().name());
			option.put("distribution", preset.getDistribution().name());
			option.put("stress", preset.getStress().name());
			option.put("stressTiming", preset.getStressTiming().name());
			planPresets.add(option);
		}

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("name", AppVersion.NAME);
		meta.put("version", AppVersion.VERSION);
		meta.put("currencies", currencies);
		meta.put("frequencies", frequencies);
		meta.put("repaymentMethods", methods);
		meta.put("prepaymentModes", modes);
		meta.put("glidePaths", glides);
		meta.put("rebalanceModes", rebalances);
		meta.put("returnDistributions", distributions);
		meta.put("stressScenarios", stresses);
		meta.put("stressTimings", timings);
		meta.put("lotteryGames", games);
		meta.put("purchaseFrequencies", purchases);
		meta.put("depositRollovers", rollovers);
		meta.put("dcaTargets", dcaTargets);
		meta.put("dcaPresets", dcaPresets);
		meta.put("planPresets", planPresets);
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
