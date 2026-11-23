package com.fullmoney.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fullmoney.bean.AllocationProfile;
import com.fullmoney.bean.GlidePath;
import com.fullmoney.bean.InvestmentPlan;
import com.fullmoney.bean.InvestmentPlan.Fit;
import com.fullmoney.bean.InvestmentTier;
import com.fullmoney.bean.PlanSettings;
import com.fullmoney.bean.RebalanceMode;
import com.fullmoney.bean.ReturnDistribution;
import com.fullmoney.bean.StressScenario;
import com.fullmoney.bean.StressTiming;
import com.fullmoney.service.CashFlows;
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
 * years 投資年數、months 月數(0～11)、profit 期望獲利金額、round=1 金額四捨五入到整數；
 * 模擬設定（皆選填）：age 目前年齡、glide 配置調整方式（{@link GlidePath}）、rebalance 再平衡方式（{@link RebalanceMode}）、
 * distribution 報酬分布（{@link ReturnDistribution}）、stress 壓力測試情境（{@link StressScenario}）、stressTiming 情境發生時間（{@link StressTiming}），
 * 中途提領以 withdrawMonth（第幾個月底）與 withdrawAmount 成對重複送出</p>
 * <p>options 為可考慮的方案（由低風險到高風險），portfolios 為保守型、穩健型、積極型各自的最佳配置（每 5% 一級搜尋；年齡法則下沒有可用配置的類型不列出），
 * 每型的 weights 為定存／公債／公司債／股票比例、stockLevel 為股票等級、endStock 為最後一年的股票比例、stress 為壓力情境下的結果、
 * rows 為各資產的逐年金額與獲利；strategies 為同樣配置在 5 種調整方式下的結果（outcomes 與 portfolios 順序相同），
 * floating 為浮動投資取上限 high／下限 low 時的情況（沒有浮動區間時為 null），
 * rows 每列為 [第幾個月底, 累積投入, 金額, 累積獲利, 累積報酬率, 累積提領]</p>
 */
public final class PlanApi extends ApiEndpoint {

	private static final int MONTHS_PER_YEAR = 12;
	private static final int MAX_YEAR = 100;
	private static final String MAX_PROFIT = "100000000000";
	private static final int MIN_AGE = 1;
	private static final int MAX_AGE = 100;
	private static final int MAX_WITHDRAWALS = 20;

	/** 策略比較：達成機率相差不到 2 個百分點視為模擬誤差 */
	private static final double STRATEGY_NOISE = 0.02;

	/** 策略比較：悲觀金額高出 1% 以上才提出 */
	private static final BigDecimal SAFER = new BigDecimal("1.01");

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
		PlanSettings settings = settings(params, principal, monthlyFixed.add(floatMin), months);
		InvestmentPlan plan = planner.plan(principal, monthlyFixed, floatMin, floatMax, months, profit, settings);
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

		//獲利 = 金額 + 已提領 − 投入
		List<Object[]> rows = new ArrayList<>();
		for (InvestmentPlan.Row row : plan.getRows()) {
			BigDecimal gain = row.getBalance().add(row.getWithdrawn()).subtract(row.getInvested());
			rows.add(new Object[] { row.getMonths(), Values.money(row.getInvested(), whole), Values.money(row.getBalance(), whole),
					Values.money(gain, whole), Values.ratio(gain.divide(row.getInvested(), 10, RoundingMode.HALF_UP)),
					Values.money(row.getWithdrawn(), whole) });
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
		out.put("withdrawn", Values.money(plan.getWithdrawn(), whole));
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
		out.put("settings", settingsOut(plan));
		out.put("strategies", strategies(plan, whole));
		out.put("strategySummary", strategySummary(plan));
		out.put("stress", stress(plan));
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
			out.put("endStock", portfolio.getEndStock());
			out.put("stress", outcome(portfolio.getStressOutcome(), whole));
			out.put("rows", allocationRows(portfolio, whole));
			list.add(out);
		}
		return list;
	}

	/** 模擬結果摘要：達成機率與期滿金額的悲觀（第 10 百分位）、中位數、樂觀（第 90 百分位）；沒有結果時為 null */
	private static Map<String, Object> outcome(InvestmentPlan.Outcome outcome, boolean whole) {
		if (outcome == null) {
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("probability", Values.ratio(outcome.getProbability()));
		out.put("chance", chance(outcome.getProbability().doubleValue()));
		out.put("pessimistic", Values.money(outcome.getPessimistic(), whole));
		out.put("median", Values.money(outcome.getMedian(), whole));
		out.put("optimistic", Values.money(outcome.getOptimistic(), whole));
		return out;
	}

	/**
	 * 配置的逐年試算，每列為 [第幾個月底, 累積投入, 定存、公債、公司債、股票的金額（4 欄）, 四者的累積獲利（4 欄）, 總金額, 累積獲利, 累積提領]
	 */
	private static List<Object[]> allocationRows(InvestmentPlan.Portfolio portfolio, boolean whole) {
		List<Object[]> rows = new ArrayList<>();
		for (InvestmentPlan.AllocationRow row : portfolio.getRows()) {
			int assets = row.getValues().length;
			Object[] out = new Object[5 + assets * 2];
			BigDecimal total = BigDecimal.ZERO;
			BigDecimal gained = BigDecimal.ZERO;
			out[0] = row.getMonths();
			out[1] = Values.money(row.getInvested(), whole);
			for (int i = 0; i < assets; i++) {
				total = total.add(row.getValues()[i]);
				gained = gained.add(row.getGains()[i]);
				out[2 + i] = Values.money(row.getValues()[i], whole);
				out[2 + assets + i] = Values.money(row.getGains()[i], whole);
			}
			out[2 + assets * 2] = Values.money(total, whole);
			out[3 + assets * 2] = Values.money(gained, whole);
			out[4 + assets * 2] = Values.money(row.getWithdrawn(), whole);
			rows.add(out);
		}
		return rows;
	}

	/** 讀取模擬設定；提領不可超過當時已投入的金額（以每月浮動投資的最低值計算，三種情況都付得出來） */
	private static PlanSettings settings(Params params, BigDecimal principal, BigDecimal lowMonthly, int months) {
		PlanSettings settings = new PlanSettings();
		List<String> age = params.list("age");
		if (!age.isEmpty() && !age.get(0).isBlank()) {
			settings.setAge(Params.toInteger(age.get(0), "age", "目前年齡", MIN_AGE, MAX_AGE));
		}
		settings.setGlidePath(params.choice("glide", "配置調整方式", GlidePath.values(), GlidePath.FIXED));
		settings.setRebalance(params.choice("rebalance", "再平衡方式", RebalanceMode.values(), RebalanceMode.YEARLY));
		settings.setDistribution(params.choice("distribution", "報酬分布", ReturnDistribution.values(), ReturnDistribution.NORMAL));
		settings.setStress(params.choice("stress", "壓力測試情境", StressScenario.values(), StressScenario.NONE));
		settings.setStressTiming(params.choice("stressTiming", "情境發生時間", StressTiming.values(), StressTiming.END));

		List<String> monthsRaw = params.list("withdrawMonth");
		List<String> amountsRaw = params.list("withdrawAmount");
		if (monthsRaw.size() != amountsRaw.size()) {
			throw new BadRequestException(null, "中途提領的月份與金額筆數不一致。");
		}
		if (monthsRaw.size() > MAX_WITHDRAWALS) {
			throw new BadRequestException(null, "中途提領最多 " + MAX_WITHDRAWALS + " 筆。");
		}
		List<PlanSettings.Withdrawal> withdrawals = new ArrayList<>();
		int[] withdrawMonths = new int[monthsRaw.size()];
		double[] withdrawAmounts = new double[monthsRaw.size()];
		for (int i = 0; i < monthsRaw.size(); i++) {
			String prefix = "中途提領第 " + (i + 1) + " 筆的";
			int month = Params.toInteger(monthsRaw.get(i), "withdrawMonth:" + i, prefix + "月份", 1, months);
			BigDecimal amount = Params.toDecimal(amountsRaw.get(i), "withdrawAmount:" + i, prefix + "金額", BigDecimal.ONE, new BigDecimal(Values.MAX_MONEY));
			withdrawals.add(new PlanSettings.Withdrawal(month, amount));
			withdrawMonths[i] = month;
			withdrawAmounts[i] = amount.doubleValue();
		}
		int overdrawn = new CashFlows(principal.doubleValue(), lowMonthly.doubleValue(), months, withdrawMonths, withdrawAmounts).firstOverdrawnMonth();
		if (overdrawn > 0) {
			int index = 0;
			while (withdrawMonths[index] != overdrawn) {
				index++;
			}
			BigDecimal investedBy = principal.add(lowMonthly.multiply(BigDecimal.valueOf(overdrawn)));
			throw new BadRequestException("withdrawAmount:" + index, "到第 " + overdrawn + " 個月底累計提領超過已投入的 $" + Numbers.group(investedBy, 0)
					+ "（每月浮動投資以最低值計算），請減少金額或延後提領。");
		}
		settings.setWithdrawals(withdrawals);
		return settings;
	}

	/** 模擬設定與說明文字 */
	private static Map<String, Object> settingsOut(InvestmentPlan plan) {
		PlanSettings settings = plan.getSettings();
		int endAge = settings.getAge() + plan.getMonths() / MONTHS_PER_YEAR;
		GlidePath glide = settings.getGlidePath();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("age", settings.getAge());
		out.put("endAge", endAge);
		out.put("glide", glide.name());
		out.put("glideName", glide.getLabel());
		out.put("rebalance", settings.getRebalance().name());
		out.put("rebalanceName", rebalanceText(settings));
		out.put("distribution", settings.getDistribution().name());
		out.put("distributionName", settings.getDistribution().getLabel());
		List<Object[]> withdrawals = new ArrayList<>();
		for (PlanSettings.Withdrawal withdrawal : settings.getWithdrawals()) {
			withdrawals.add(new Object[] { withdrawal.getMonth(), withdrawal.getAmount() });
		}
		out.put("withdrawals", withdrawals);

		StringBuilder text = new StringBuilder("目前 ").append(settings.getAge()).append(" 歲、期滿時約 ").append(endAge).append(" 歲；")
				.append(glide.getLabel()).append("，").append(rebalanceText(settings)).append("，").append(settings.getDistribution().getLabel()).append("。");
		text.append("依年齡法則，股票比例宜在 ").append(Math.max(0, GlidePath.AGE_RULE_BASE - endAge)).append("%～")
				.append(Math.max(0, GlidePath.AGE_RULE_BASE - settings.getAge())).append("% 以下（期滿～現在）。");
		for (AllocationProfile profile : plan.getUnavailableProfiles()) {
			text.append("年齡法則下股票上限 ").append(settings.ageStockCap()).append("%，").append(profile.getName()).append("沒有可用的配置，不列出。");
		}
		if (plan.getWithdrawn().signum() > 0) {
			text.append("中途提領 ").append(settings.getWithdrawals().size()).append(" 筆共 $").append(Numbers.group(plan.getWithdrawn(), 0))
					.append("，目標金額已扣除提領，獲利以「期滿金額 + 提領 − 總投入」計算；提領時資產不足的走勢視為未達成。");
		}
		out.put("text", text.toString());
		return out;
	}

	/** 實際的再平衡方式：逐年調整比例時每年依新比例調整 */
	private static String rebalanceText(PlanSettings settings) {
		return settings.getGlidePath() == GlidePath.FIXED ? settings.getRebalance().getLabel() : "每年依新比例調整";
	}

	/** 策略比較：同樣的最佳配置以 5 種調整方式模擬 */
	private static List<Map<String, Object>> strategies(InvestmentPlan plan, boolean whole) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (InvestmentPlan.Strategy strategy : plan.getStrategies()) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("code", strategyCode(strategy));
			out.put("name", strategyName(strategy));
			out.put("selected", strategy.isSelected());
			List<Map<String, Object>> outcomes = new ArrayList<>();
			for (InvestmentPlan.Outcome outcome : strategy.getOutcomes()) {
				outcomes.add(outcome(outcome, whole));
			}
			out.put("outcomes", outcomes);
			list.add(out);
		}
		return list;
	}

	private static String strategyCode(InvestmentPlan.Strategy strategy) {
		return strategy.getGlidePath() == GlidePath.FIXED ? "FIXED_" + strategy.getRebalance().name() : strategy.getGlidePath().name();
	}

	private static String strategyName(InvestmentPlan.Strategy strategy) {
		return strategy.getGlidePath() == GlidePath.FIXED ? "固定比例（" + strategy.getRebalance().getLabel() + "）" : strategy.getGlidePath().getLabel();
	}

	/** 以建議（或機率最高）的配置比較 5 種調整方式，指出目前設定是否最好 */
	private static String strategySummary(InvestmentPlan plan) {
		List<InvestmentPlan.Portfolio> portfolios = plan.getPortfolios();
		int focus = -1;
		for (int i = 0; i < portfolios.size(); i++) {
			if (portfolios.get(i).getProfile() == plan.getRecommendedProfile()) {
				focus = i;
			}
		}
		if (focus < 0) {
			return "所有配置在 5 種調整方式下的達成機率都很低，調整方式無法彌補目標與期間的落差。";
		}
		InvestmentPlan.Strategy selected = null;
		InvestmentPlan.Strategy best = null;
		InvestmentPlan.Strategy safest = null;
		for (InvestmentPlan.Strategy strategy : plan.getStrategies()) {
			if (strategy.isSelected()) {
				selected = strategy;
			}
			if (best == null || probabilityOf(strategy, focus).compareTo(probabilityOf(best, focus)) > 0) {
				best = strategy;
			}
			if (safest == null || pessimisticOf(strategy, focus).compareTo(pessimisticOf(safest, focus)) > 0) {
				safest = strategy;
			}
		}
		String profile = portfolios.get(focus).getProfile().getName();
		InvestmentPlan.Outcome current = selected.getOutcomes().get(focus);
		StringBuilder text = new StringBuilder("以建議的").append(profile).append("配置比較 5 種調整方式：目前的設定「").append(strategyName(selected))
				.append("」達成機率約 ").append(wholePercent(current.getProbability()));
		BigDecimal gap = probabilityOf(best, focus).subtract(current.getProbability());
		if (gap.signum() <= 0) {
			text.append("，已是最高");
		} else if (gap.doubleValue() < STRATEGY_NOISE) {
			text.append("，與最高的「").append(strategyName(best)).append("」相差不到 2 個百分點，在模擬誤差範圍內");
		} else {
			InvestmentPlan.Outcome to = best.getOutcomes().get(focus);
			text.append("；改用「").append(strategyName(best)).append("」可提高到約 ").append(wholePercent(to.getProbability()))
					.append("，期滿金額中位數 $").append(Numbers.group(current.getMedian(), 0)).append(" → $").append(Numbers.group(to.getMedian(), 0));
		}
		//在意最差情況時：悲觀金額（最差 10%）明顯較高的方式
		BigDecimal floor = pessimisticOf(safest, focus);
		if (safest != selected && floor.compareTo(current.getPessimistic().multiply(SAFER)) > 0) {
			text.append("。若更在意最差情況，「").append(strategyName(safest)).append("」的悲觀金額最高，約 $").append(Numbers.group(floor, 0))
					.append("（目前 $").append(Numbers.group(current.getPessimistic(), 0)).append("）");
		}
		return text.append("。").toString();
	}

	private static BigDecimal pessimisticOf(InvestmentPlan.Strategy strategy, int portfolio) {
		return strategy.getOutcomes().get(portfolio).getPessimistic();
	}

	private static BigDecimal probabilityOf(InvestmentPlan.Strategy strategy, int portfolio) {
		return strategy.getOutcomes().get(portfolio).getProbability();
	}

	/** 壓力情境：名稱、說明、發生的年份與對建議配置的影響；沒有做壓力測試時為 null */
	private static Map<String, Object> stress(InvestmentPlan plan) {
		PlanSettings settings = plan.getSettings();
		StressScenario scenario = settings.getStress();
		if (scenario == StressScenario.NONE) {
			return null;
		}
		int from = plan.getStressFrom() + 1;
		int to = plan.getStressFrom() + plan.getStressYears();
		String period = from == to ? "第 " + from + " 年" : "第 " + from + "～" + to + " 年";
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("code", scenario.name());
		out.put("name", scenario.getLabel());
		out.put("description", scenario.getDescription());
		out.put("timing", settings.getStressTiming().name());
		out.put("timingName", settings.getStressTiming().getLabel());
		out.put("period", period);

		StringBuilder text = new StringBuilder("假設在").append(settings.getStressTiming().getLabel()).append("（").append(period).append("）發生")
				.append(scenario.getLabel()).append("：").append(scenario.getDescription()).append("。");
		for (InvestmentPlan.Portfolio portfolio : plan.getPortfolios()) {
			if (portfolio.getProfile() != plan.getRecommendedProfile()) {
				continue;
			}
			InvestmentPlan.Outcome stressed = portfolio.getStressOutcome();
			text.append("建議的").append(portfolio.getProfile().getName()).append("達成機率由 ").append(wholePercent(portfolio.getProbability()))
					.append(" 變為 ").append(wholePercent(stressed.getProbability())).append("，期滿金額中位數由 $")
					.append(Numbers.group(portfolio.getMedian(), 0)).append(" 變為 $").append(Numbers.group(stressed.getMedian(), 0)).append("。");
		}
		out.put("text", text.toString());
		return out;
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
		PlanSettings settings = plan.getSettings();
		StringBuilder text = new StringBuilder("以每 5% 一級試算 ").append(Numbers.group(BigDecimal.valueOf(total), 0))
				.append(" 種定存／公債／公司債／股票配置（股票再分 5 個等級），估計約 ").append(Numbers.group(BigDecimal.valueOf(reachable), 0))
				.append(" 種達成機率超過 50%；再把較好的 ").append(simulated).append(" 種各模擬 ").append(MarketSimulator.PATHS)
				.append(" 種市場走勢（四種資產分別模擬、彼此連動；").append(settings.getGlidePath().getLabel()).append("，").append(rebalanceText(settings))
				.append("，").append(settings.getDistribution().getLabel()).append("）。");
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
