package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.InvestmentPlan;
import com.fullmoney.bean.InvestmentPlan.Fit;
import com.fullmoney.bean.InvestmentTier;
import com.fullmoney.service.InvestmentPlanner;
import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.BadRequestException;
import com.fullmoney.web.Numbers;
import com.fullmoney.web.Params;

/**
 * 投資計畫：算出需要的年化報酬率，列出可以達成的方案與配置建議
 *
 * <p>參數：principal 投資本金、years 投資年數、months 月數(0～11)、profit 期望獲利金額、round=1 金額四捨五入到整數</p>
 * <p>options 為可考慮的方案（由低風險到高風險），allocation 為兩類標的的配置建議（不需要時為 null），
 * rows 每列為 [第幾個月底, 金額, 累積獲利, 累積報酬率]</p>
 */
public final class PlanApi extends ApiEndpoint {

	private static final int MONTHS_PER_YEAR = 12;
	private static final int MAX_YEAR = 100;
	private static final String MAX_PROFIT = "100000000000";

	private final InvestmentPlanner planner = new InvestmentPlanner();

	public PlanApi() {
		super("/api/plan");
	}

	@Override
	protected Object respond(Params params) {
		BigDecimal principal = params.decimal("principal", "投資本金", "1", Values.MAX_MONEY);
		int years = params.integer("years", "投資年數", 0, MAX_YEAR);
		int extraMonths = params.integer("months", "月數", 0, MONTHS_PER_YEAR - 1);
		BigDecimal profit = params.decimal("profit", "期望獲利金額", "0", MAX_PROFIT);
		boolean whole = Values.wholeMoney(params);
		int months = years * MONTHS_PER_YEAR + extraMonths;
		if (months < 1) {
			throw new BadRequestException("years", "投資期間至少 1 個月。");
		}

		InvestmentPlan plan = planner.plan(principal, months, profit);
		BigDecimal target = plan.getTarget();

		List<Map<String, Object>> options = new ArrayList<>();
		for (InvestmentPlan.Option option : plan.getOptions()) {
			InvestmentTier tier = option.getTier();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("code", tier.name());
			out.put("name", tier.getName());
			out.put("fit", option.getFit().name());
			out.put("horizonOk", option.isHorizonOk());
			out.put("instruments", tier.getInstruments());
			out.put("risk", tier.getRisk());
			out.put("horizon", tier.getHorizon());
			out.put("note", tier.getNote());
			if (option.getTypicalEnd() != null) {
				out.put("typicalRate", Values.ratio(tier.getTypicalRate()));
				out.put("typicalEnd", Values.money(option.getTypicalEnd(), whole));
				out.put("typicalReached", option.getTypicalEnd().compareTo(target) >= 0 || option.getFit() == Fit.COMFORTABLE);
				out.put("maxRate", Values.ratio(tier.getMaxRate()));
				out.put("maxEnd", Values.money(option.getMaxEnd(), whole));
			}
			options.add(out);
		}

		List<Object[]> rows = new ArrayList<>();
		for (InvestmentPlan.Row row : plan.getRows()) {
			BigDecimal gain = row.getBalance().subtract(principal);
			rows.add(new Object[] { row.getMonths(), Values.money(row.getBalance(), whole), Values.money(gain, whole),
					Values.ratio(gain.divide(principal, 10, RoundingMode.HALF_UP)) });
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("decimals", Values.moneyDecimals(whole));
		out.put("months", months);
		out.put("target", Values.money(target, whole));
		out.put("totalReturn", Values.ratio(plan.getTotalReturn()));
		out.put("requiredRate", Values.ratio(plan.getRequiredRate()));
		out.put("tier", plan.getTier().name());
		out.put("tierName", plan.getTier().getName());
		out.put("feasible", plan.isFeasible());
		out.put("summary", summary(plan));
		out.put("options", options);
		out.put("allocation", allocation(plan.getAllocation(), whole));
		out.put("warnings", warnings(plan));
		out.put("suggestions", suggestions(plan));
		out.put("rows", rows);
		return out;
	}

	private static Map<String, Object> allocation(InvestmentPlan.Allocation allocation, boolean whole) {
		if (allocation == null) {
			return null;
		}
		List<Map<String, Object>> parts = new ArrayList<>();
		parts.add(part(allocation.getLow(), 100 - allocation.getHighPercent()));
		parts.add(part(allocation.getHigh(), allocation.getHighPercent()));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("parts", parts);
		out.put("rate", Values.ratio(allocation.getRate()));
		out.put("endAmount", Values.money(allocation.getEndAmount(), whole));
		out.put("horizonOk", allocation.isHorizonOk());
		out.put("text", "以一般行情估算，" + allocation.getLow().getName() + " " + (100 - allocation.getHighPercent()) + "%、"
				+ allocation.getHigh().getName() + " " + allocation.getHighPercent() + "% 的年化報酬率約 " + percent(allocation.getRate())
				+ "，剛好超過需要的報酬率，比全部投入高風險的一類更穩健。"
				+ (allocation.isHorizonOk() ? "" : allocation.getHigh().getName() + "部位建議持有 " + allocation.getHigh().getHorizon() + "。"));
		return out;
	}

	private static Map<String, Object> part(InvestmentTier tier, int percent) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("code", tier.name());
		out.put("name", tier.getName());
		out.put("percent", percent);
		return out;
	}

	private static String summary(InvestmentPlan plan) {
		InvestmentTier tier = plan.getTier();
		String rate = percent(plan.getRequiredRate());
		String period = term(plan.getMonths());
		if (plan.getProfit().signum() == 0) {
			return "期望獲利為 0，只要保本即可，建議存放在" + InvestmentTier.DEPOSIT.getInstruments() + "。";
		}
		if (tier == InvestmentTier.LOTTERY) {
			return "要在 " + period + " 內讓本金變成 " + Numbers.group(plan.getMultiple(), 2) + " 倍，需要的年化報酬率約 " + rate
					+ "，任何投資都幾乎不可能達成，計畫不可行。真的想試試，只能買" + tier.getInstruments() + "碰運氣了。";
		}
		if (tier == InvestmentTier.SPECULATIVE) {
			return "需要的年化報酬率約 " + rate + "，一般投資很難在 " + period + " 內達成，計畫可能不可行。若仍要嘗試，只能選擇"
					+ tier.getInstruments() + "等高風險標的，適合短線操作，高風險高報酬。";
		}

		List<InvestmentPlan.Option> options = plan.getOptions();
		InvestmentPlan.Option lowest = options.get(0);
		String lowestFit = lowest.getFit() == Fit.COMFORTABLE ? "，一般行情就能達成" : "，但需要行情較好";
		StringBuilder text = new StringBuilder("需要的年化報酬率約 ").append(rate).append("，");
		if (options.size() == 1) {
			return text.append("可以考慮「").append(tier.getName()).append("」").append(lowestFit).append("。").toString();
		}
		for (InvestmentPlan.Option option : options) {
			text.append("「").append(option.getTier().getName()).append("」");
		}
		text.append("都有機會達成。風險最低的是「").append(tier.getName()).append("」").append(lowestFit);
		for (InvestmentPlan.Option option : options) {
			if (option != lowest && option.getFit() == Fit.COMFORTABLE) {
				text.append("；「").append(option.getTier().getName()).append("」在一般行情下就能達成，但風險較高");
				break;
			}
		}
		return text.append("。").toString();
	}

	private static List<String> warnings(InvestmentPlan plan) {
		InvestmentTier tier = plan.getTier();
		List<String> warnings = new ArrayList<>();
		if (plan.getExtendedRate() != null) {
			warnings.add("投資期間 " + term(plan.getMonths()) + " 短於「" + tier.getName() + "」建議的 " + tier.getHorizon()
					+ "，短期內價格可能下跌，不一定能如期達成。");
		}
		if (tier == InvestmentTier.SPECULATIVE) {
			warnings.add("期貨、選擇權、權證具槓桿，可能在短時間內損失全部本金，只適合拿可以完全承受虧損的小部分資金操作。");
		}
		if (tier == InvestmentTier.LOTTERY) {
			warnings.add("彩券的期望報酬為負，請不要投入本金或借錢購買。");
		}
		return warnings;
	}

	private static List<String> suggestions(InvestmentPlan plan) {
		List<String> suggestions = new ArrayList<>();
		if (plan.getExtendedRate() != null) {
			suggestions.add("把期間延長到 " + plan.getTier().getMinYears() + " 年，需要的年化報酬率降為約 " + percent(plan.getExtendedRate())
					+ "（" + plan.getExtendedTier().getName() + "）。");
		}
		InvestmentTier benchmark = plan.getBenchmark();
		if (benchmark != null) {
			String basis = "以「" + benchmark.getName() + "」一般行情年報酬 " + percent(benchmark.getTypicalRate()) + " 估算";
			suggestions.add("延長期間：" + basis + "，約需 " + term(plan.getBenchmarkMonths()) + "。");
			suggestions.add("增加本金：維持 " + term(plan.getMonths()) + "、" + basis + "，本金需約 $" + Numbers.group(plan.getBenchmarkPrincipal(), 0) + "。");
			suggestions.add("降低目標：維持本金與期間、" + basis + "，可期待獲利約 $" + Numbers.group(plan.getBenchmarkProfit(), 0) + "。");
		}
		return suggestions;
	}

	/** ex. 0.0456 → 4.56% */
	private static String percent(BigDecimal ratio) {
		return Numbers.group(ratio.movePointRight(2), 2) + "%";
	}

	/** ex. 30 → 2 年 6 個月 */
	private static String term(int months) {
		int years = months / MONTHS_PER_YEAR;
		int rest = months % MONTHS_PER_YEAR;
		if (rest == 0) {
			return years + " 年";
		}
		return years == 0 ? rest + " 個月" : years + " 年 " + rest + " 個月";
	}
}
