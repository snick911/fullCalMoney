package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.AllocationProfile;
import com.fullmoney.bean.InvestmentPlan;
import com.fullmoney.bean.InvestmentPlan.Fit;
import com.fullmoney.bean.InvestmentTier;
import com.fullmoney.service.InvestmentPlanner;
import com.fullmoney.service.MarketSimulator;
import com.fullmoney.web.ApiEndpoint;
import com.fullmoney.web.BadRequestException;
import com.fullmoney.web.Numbers;
import com.fullmoney.web.Params;

/**
 * 投資計畫：算出需要的年化報酬率，列出可以達成的方案與配置建議
 *
 * <p>參數：principal 投資本金（可為 0）、monthlyFixed 每月固定投資金額、floatMin / floatMax 每月浮動投資金額的區間（三者選填，預設 0）、
 * years 投資年數、months 月數(0～11)、profit 期望獲利金額、round=1 金額四捨五入到整數</p>
 * <p>options 為可考慮的方案（由低風險到高風險），portfolios 為保守型、穩健型、積極型各自的最佳配置（每 5% 一級搜尋），
 * 每型的 weights 為定存／公債／公司債／股票比例、stockLevel 為股票等級、rows 為各資產的逐年金額與獲利，
 * floating 為浮動投資取上限 high／下限 low 時的情況（沒有浮動區間時為 null），
 * rows 每列為 [第幾個月底, 累積投入, 金額, 累積獲利, 累積報酬率]</p>
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
		BigDecimal principal = params.decimal("principal", "投資本金", "0", Values.MAX_MONEY);
		BigDecimal monthlyFixed = params.optionalDecimal("monthlyFixed", "每月固定投資金額", "0", Values.MAX_MONEY, BigDecimal.ZERO);
		BigDecimal floatMin = params.optionalDecimal("floatMin", "每月浮動投資金額的最低值", "0", Values.MAX_MONEY, BigDecimal.ZERO);
		BigDecimal floatMax = params.optionalDecimal("floatMax", "每月浮動投資金額的最高值", "0", Values.MAX_MONEY, BigDecimal.ZERO);
		int years = params.integer("years", "投資年數", 0, MAX_YEAR);
		int extraMonths = params.integer("months", "月數", 0, MONTHS_PER_YEAR - 1);
		BigDecimal profit = params.decimal("profit", "期望獲利金額", "0", MAX_PROFIT);
		boolean whole = Values.wholeMoney(params);
		int months = years * MONTHS_PER_YEAR + extraMonths;
		if (floatMax.compareTo(floatMin) < 0) {
			throw new BadRequestException("floatMax", "每月浮動投資金額的最高值不可小於最低值。");
		}
		if (months < 1) {
			throw new BadRequestException("years", "投資期間至少 1 個月。");
		}
		if (principal.signum() == 0 && monthlyFixed.signum() == 0 && floatMax.signum() == 0) {
			throw new BadRequestException("principal", "請輸入投資本金或每月投資金額。");
		}
		InvestmentPlan plan = planner.plan(principal, monthlyFixed, floatMin, floatMax, months, profit);
		BigDecimal target = plan.getTarget();

		List<Map<String, Object>> options = new ArrayList<>();
		for (InvestmentPlan.Option option : plan.getOptions()) {
			InvestmentTier tier = option.getTier();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("code", tier.name());
			out.put("name", tier.getName());
			out.put("fit", option.getFit().name());
			out.put("horizonOk", option.isHorizonOk());
			out.put("lowCaseOk", option.isLowCaseOk());
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
				out.put("probability", Values.ratio(option.getProbability()));
			}
			options.add(out);
		}

		List<Object[]> rows = new ArrayList<>();
		for (InvestmentPlan.Row row : plan.getRows()) {
			BigDecimal gain = row.getBalance().subtract(row.getInvested());
			rows.add(new Object[] { row.getMonths(), Values.money(row.getInvested(), whole), Values.money(row.getBalance(), whole),
					Values.money(gain, whole), Values.ratio(gain.divide(row.getInvested(), 10, RoundingMode.HALF_UP)) });
		}

		Map<String, Object> floating = null;
		if (plan.getLowCase() != null) {
			floating = new LinkedHashMap<>();
			floating.put("high", floatingCase(plan.getHighCase(), whole));
			floating.put("low", floatingCase(plan.getLowCase(), whole));
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("decimals", Values.moneyDecimals(whole));
		out.put("months", months);
		out.put("monthly", Values.money(plan.getMonthly(), whole));
		out.put("invested", Values.money(plan.getInvested(), whole));
		out.put("target", Values.money(target, whole));
		out.put("totalReturn", Values.ratio(plan.getTotalReturn()));
		out.put("requiredRate", Values.ratio(plan.getRequiredRate()));
		out.put("tier", plan.getTier().name());
		out.put("tierName", plan.getTier().getName());
		out.put("feasible", plan.isFeasible());
		out.put("summary", summary(plan));
		out.put("floating", floating);
		out.put("options", options);
		out.put("portfolioSummary", portfolioSummary(plan));
		out.put("portfolios", portfolios(plan, whole));
		out.put("warnings", warnings(plan));
		out.put("suggestions", suggestions(plan));
		out.put("rows", rows);
		return out;
	}

	/** 每月浮動投資取某一端時的總投入與需要的年化報酬率；沒有投入任何資金時 rate 為 null */
	private static Map<String, Object> floatingCase(InvestmentPlan.Case floatingCase, boolean whole) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("monthly", Values.money(floatingCase.getMonthly(), whole));
		out.put("invested", Values.money(floatingCase.getInvested(), whole));
		out.put("rate", floatingCase.getRate() == null ? null : Values.ratio(floatingCase.getRate()));
		out.put("tier", floatingCase.getTier() == null ? null : floatingCase.getTier().name());
		out.put("tierName", floatingCase.getTier() == null ? null : floatingCase.getTier().getName());
		return out;
	}

	private static List<Map<String, Object>> portfolios(InvestmentPlan plan, boolean whole) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (InvestmentPlan.Portfolio portfolio : plan.getPortfolios()) {
			AllocationProfile profile = portfolio.getProfile();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("code", profile.name());
			out.put("name", profile.getName());
			out.put("recommended", profile == plan.getRecommendedProfile());
			List<Integer> weights = new ArrayList<>();
			for (int weight : portfolio.getWeights()) {
				weights.add(weight);
			}
			out.put("weights", weights);
			InvestmentTier level = portfolio.getStockLevel();
			out.put("stockLevel", level == null ? null : level.name());
			out.put("stockLevelName", level == null ? null : level.getName());
			out.put("stockInstruments", level == null ? null : level.getInstruments());
			out.put("typicalRate", Values.ratio(portfolio.getTypicalRate()));
			out.put("volatility", Values.ratio(portfolio.getVolatility()));
			out.put("probability", Values.ratio(portfolio.getProbability()));
			out.put("chance", chance(portfolio.getProbability().doubleValue()));
			out.put("pessimistic", Values.money(portfolio.getPessimistic(), whole));
			out.put("median", Values.money(portfolio.getMedian(), whole));
			out.put("optimistic", Values.money(portfolio.getOptimistic(), whole));
			out.put("horizonOk", portfolio.isHorizonOk());
			out.put("horizon", profile.getHorizon());
			out.put("candidates", portfolio.getCandidates());
			out.put("reachable", portfolio.getReachable());
			out.put("simulated", portfolio.getSimulated());
			out.put("rows", allocationRows(portfolio, whole));
			list.add(out);
		}
		return list;
	}

	/**
	 * 配置的逐年試算，每列為 [第幾個月底, 累積投入, 定存、公債、公司債、股票的金額（4 欄）, 四者的獲利（4 欄）, 總金額, 累積獲利]
	 */
	private static List<Object[]> allocationRows(InvestmentPlan.Portfolio portfolio, boolean whole) {
		List<Object[]> rows = new ArrayList<>();
		for (InvestmentPlan.AllocationRow row : portfolio.getRows()) {
			int assets = row.getValues().length;
			Object[] out = new Object[4 + assets * 2];
			BigDecimal invested = BigDecimal.ZERO;
			BigDecimal total = BigDecimal.ZERO;
			out[0] = row.getMonths();
			for (int i = 0; i < assets; i++) {
				invested = invested.add(row.getInvested()[i]);
				total = total.add(row.getValues()[i]);
				out[2 + i] = Values.money(row.getValues()[i], whole);
				out[2 + assets + i] = Values.money(row.getValues()[i].subtract(row.getInvested()[i]), whole);
			}
			out[1] = Values.money(invested, whole);
			out[2 + assets * 2] = Values.money(total, whole);
			out[3 + assets * 2] = Values.money(total.subtract(invested), whole);
			rows.add(out);
		}
		return rows;
	}

	/** 達成機率的程度：HIGH 75% 以上、LIKELY 50% 以上、STRETCH 25% 以上、LOW 其他 */
	private static String chance(double probability) {
		if (probability >= 0.75) {
			return "HIGH";
		}
		if (probability >= 0.5) {
			return "LIKELY";
		}
		return probability >= 0.25 ? "STRETCH" : "LOW";
	}

	/** 搜尋範圍、模擬方式與建議的資產配置 */
	private static String portfolioSummary(InvestmentPlan plan) {
		int total = 0;
		int reachable = 0;
		int simulated = 0;
		InvestmentPlan.Portfolio recommended = null;
		for (InvestmentPlan.Portfolio portfolio : plan.getPortfolios()) {
			total += portfolio.getCandidates();
			reachable += portfolio.getReachable();
			simulated += portfolio.getSimulated();
			if (portfolio.getProfile() == plan.getRecommendedProfile()) {
				recommended = portfolio;
			}
		}
		StringBuilder text = new StringBuilder("以每 5% 一級試算 ").append(Numbers.group(BigDecimal.valueOf(total), 0))
				.append(" 種定存／公債／公司債／股票配置（股票再分 5 個等級），估計約 ").append(Numbers.group(BigDecimal.valueOf(reachable), 0))
				.append(" 種達成機率超過 50%；再把較好的 ").append(simulated).append(" 種各模擬 ").append(MarketSimulator.PATHS).append(" 種市場走勢。");
		if (recommended == null) {
			return text.append("所有配置的達成機率都是 0，需要延長期間、增加投入或調整目標。").toString();
		}
		text.append("建議「").append(recommended.getProfile().getName()).append("」：").append(mix(recommended)).append("，達成機率約 ")
				.append(wholePercent(recommended.getProbability())).append("，期滿金額中位數約 $").append(Numbers.group(recommended.getMedian(), 0))
				.append("（10% 的情況低於 $").append(Numbers.group(recommended.getPessimistic(), 0)).append("）");
		if (!recommended.isHorizonOk()) {
			text.append("，但投資期間短於建議的 ").append(recommended.getProfile().getHorizon());
		}
		return text.append("。").toString();
	}

	private static String mix(InvestmentPlan.Portfolio portfolio) {
		int[] weights = portfolio.getWeights();
		String stock = "股票 " + weights[3] + "%" + (portfolio.getStockLevel() == null ? "" : "（" + portfolio.getStockLevel().getName() + "）");
		return "定存 " + weights[0] + "%、公債 " + weights[1] + "%、公司債 " + weights[2] + "%、" + stock;
	}

	/** ex. 0.6234 → 62% */
	private static String wholePercent(BigDecimal ratio) {
		return ratio.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
	}

	private static String summary(InvestmentPlan plan) {
		InvestmentTier tier = plan.getTier();
		String rate = percent(plan.getRequiredRate());
		String period = term(plan.getMonths());
		if (plan.getProfit().signum() == 0) {
			return "期望獲利為 0，只要保本即可，建議存放在" + InvestmentTier.DEPOSIT.getInstruments() + "。";
		}
		if (tier == InvestmentTier.LOTTERY) {
			return "要在 " + period + " 內讓投入的資金變成 " + Numbers.group(plan.getMultiple(), 2) + " 倍，需要的年化報酬率約 " + rate
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
		//浮動投資只投入下限時風險升級或無法達成，要明確提醒
		InvestmentPlan.Case low = plan.getLowCase();
		if (low != null && plan.isFeasible()) {
			String monthly = "每月浮動投資只投入下限（每月合計 $" + Numbers.group(low.getMonthly(), 0) + "）時";
			if (low.getTier() == null) {
				warnings.add(monthly + "沒有投入任何資金，無法達成目標。");
			} else if (low.getTier().ordinal() > tier.ordinal()) {
				warnings.add(monthly + "，需要的年化報酬率升為約 " + percent(low.getRate()) + "（" + low.getTier().getName() + "），請預留緩衝。");
			}
		}
		return warnings;
	}

	private static List<String> suggestions(InvestmentPlan plan) {
		List<String> suggestions = new ArrayList<>();
		boolean hasMonthly = plan.getMonthly().signum() > 0;
		if (plan.getExtendedRate() != null) {
			suggestions.add("把期間延長到 " + plan.getTier().getMinYears() + " 年" + (hasMonthly ? "（每月照常投入）" : "") + "，需要的年化報酬率降為約 "
					+ percent(plan.getExtendedRate()) + "（" + plan.getExtendedTier().getName() + "）。");
		}
		InvestmentTier benchmark = plan.getBenchmark();
		if (benchmark != null) {
			String basis = "其他條件不變、以「" + benchmark.getName() + "」一般行情年報酬 " + percent(benchmark.getTypicalRate()) + " 估算";
			suggestions.add("延長期間：" + basis + "，"
					+ (plan.getBenchmarkMonths() < 0 ? "超過 1000 年也達不到。" : "約需 " + term(plan.getBenchmarkMonths()) + "。"));
			suggestions.add("增加本金：" + basis + "，本金需約 $" + Numbers.group(plan.getBenchmarkPrincipal(), 0) + "。");
			suggestions.add("增加每月投資：" + basis + "，每月需投入約 $" + Numbers.group(plan.getBenchmarkMonthly(), 0) + "。");
			suggestions.add("降低目標：" + basis + "，可期待獲利約 $" + Numbers.group(plan.getBenchmarkProfit(), 0) + "。");
		}
		return suggestions;
	}

	/** ex. 0.0456 → 4.56%、0.049 → 4.90%、0.05 → 5% */
	private static String percent(BigDecimal ratio) {
		String text = Numbers.group(ratio.movePointRight(2), 2);
		int dot = text.indexOf('.');
		return (dot >= 0 && text.length() - dot == 2 ? text + "0" : text) + "%";
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
