package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.InvestmentPlan;
import com.fullmoney.bean.InvestmentTier;
import com.fullmoney.service.InvestmentPlanner;
import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.BadRequestException;
import com.fullmoney.web.Numbers;
import com.fullmoney.web.Params;

/**
 * 投資計畫：算出需要的年化報酬率並依規則建議標的
 *
 * <p>參數：principal 投資本金、years 投資年數、months 月數(0～11)、profit 期望獲利金額、round=1 金額四捨五入到整數</p>
 * <p>scenarios 每列為 [類別代碼, 類別, 年報酬率上限, 期滿金額, 期滿獲利, 是否達標, 達標所需月數]；
 * rows 每列為 [第幾個月底, 金額, 累積獲利, 累積報酬率]。建議規則由 /api/meta 的 investmentTiers 提供</p>
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
		InvestmentTier tier = plan.getTier();

		List<Object[]> scenarios = new ArrayList<>();
		for (InvestmentPlan.Scenario scenario : plan.getScenarios()) {
			InvestmentTier each = scenario.getTier();
			scenarios.add(new Object[] { each.name(), each.getName(), Values.ratio(each.getMaxRate()), Values.money(scenario.getEndAmount(), whole),
					Values.money(scenario.getEndAmount().subtract(principal), whole), scenario.isReached(), scenario.getMonthsNeeded() });
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
		out.put("target", Values.money(plan.getTarget(), whole));
		out.put("totalReturn", Values.ratio(plan.getTotalReturn()));
		out.put("requiredRate", Values.ratio(plan.getRequiredRate()));
		out.put("tier", tier.name());
		out.put("tierName", tier.getName());
		out.put("risk", tier.getRisk());
		out.put("instruments", tier.getInstruments());
		out.put("horizon", tier.getHorizon());
		out.put("note", tier.getNote());
		out.put("feasible", plan.isFeasible());
		out.put("summary", summary(plan));
		out.put("warnings", warnings(plan));
		out.put("suggestions", suggestions(plan));
		out.put("scenarios", scenarios);
		out.put("rows", rows);
		return out;
	}

	private static String summary(InvestmentPlan plan) {
		InvestmentTier tier = plan.getTier();
		String rate = percent(plan.getRequiredRate());
		String period = term(plan.getMonths());
		if (plan.getProfit().signum() == 0) {
			return "期望獲利為 0，只要保本即可，建議存放在" + InvestmentTier.DEPOSIT.getInstruments() + "。";
		}
		switch (tier) {
		case LOTTERY:
			return "要在 " + period + " 內讓本金變成 " + Numbers.group(plan.getMultiple(), 2) + " 倍，需要的年化報酬率約 " + rate
					+ "，任何投資都幾乎不可能達成，計畫不可行。真的想試試，只能買" + tier.getInstruments() + "碰運氣了。";
		case SPECULATIVE:
			return "需要的年化報酬率約 " + rate + "，一般投資很難在 " + period + " 內達成，計畫可能不可行。若仍要嘗試，只能選擇"
					+ tier.getInstruments() + "等高風險標的，適合短線操作，高風險高報酬。";
		default:
			return "需要的年化報酬率約 " + rate + "，建議以「" + tier.getName() + "」為主：" + tier.getInstruments() + "。";
		}
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
		InvestmentPlan.Scenario benchmark = plan.getBenchmark();
		if (benchmark != null) {
			String basis = "以「" + benchmark.getTier().getName() + "」年報酬 " + percent(benchmark.getTier().getMaxRate()) + " 估算";
			suggestions.add("延長期間：" + basis + "，約需 " + term(benchmark.getMonthsNeeded()) + "。");
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
