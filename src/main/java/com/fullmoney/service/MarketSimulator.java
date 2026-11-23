package com.fullmoney.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fullmoney.bean.InvestmentTier;
import com.fullmoney.bean.RebalanceMode;
import com.fullmoney.bean.ReturnDistribution;
import com.fullmoney.bean.StressScenario;
import com.fullmoney.bean.StressTiming;

/**
 * 蒙地卡羅模擬：產生一組共用的市場走勢，四種資產分別模擬、彼此連動
 *
 * <p>模型：</p>
 * <ul>
 * <li>每類標的每年的報酬為對數常態分布，ln(1 + 年報酬) ~ N(ln(1 + 一般行情), 波動²)，「一般行情」就是中位數；
 * 不足一年的最後一段依比例縮放。每年內的成長平均分攤到每個月，與 {@link InvestmentPlanner#futureValue} 的逐月複利一致。</li>
 * <li>三個市場因子（公債、公司債、股票）以 Cholesky 分解產生相關：公債與公司債 0.5、公債與股票 0.1、公司債與股票 0.5；
 * 5 個股票等級共用股票因子（只差波動大小），定存沒有波動。</li>
 * <li>厚尾分布：三個因子同除以一個卡方亂數，成為自由度 5 的多變量 t 分布（標準差仍為 1），大跌時各資產容易一起跌。
 * 常態與厚尾都取同一組亂數，比較才公平；單年衝擊限制在 ±6 個標準差內，避免極端值失真。</li>
 * <li>現金流：本金第 1 個月初、每月月初依當年目標比例投入；提領在該月底依各部位比例賣出，付不出來時這條走勢視為失敗。</li>
 * <li>再平衡在每年初（第 13、25… 個月初）依 {@link Allocation} 的設定進行。</li>
 * <li>壓力測試：指定的年份改用情境報酬（見 {@link StressScenario}），其他年份與原本的走勢相同。</li>
 * </ul>
 *
 * <p>亂數種子固定，相同條件每次結果相同，伺服器與瀏覽器端引擎也相同。所有配置使用同一組市場走勢。</p>
 */
public final class MarketSimulator {

	/** 模擬的走勢數量 */
	public static final int PATHS = 500;

	/** 可模擬的標的，順序與 {@link InvestmentTier} 前 8 類相同 */
	public static final InvestmentTier[] INSTRUMENTS = { InvestmentTier.DEPOSIT, InvestmentTier.GOV_BOND, InvestmentTier.CORPORATE_BOND,
			InvestmentTier.STOCK_INCOME, InvestmentTier.STOCK_CORE, InvestmentTier.STOCK_GROWTH, InvestmentTier.STOCK_AGGRESSIVE,
			InvestmentTier.STOCK_VOLATILE };

	/** 相關係數 */
	public static final double GOV_CORPORATE_CORRELATION = 0.5;
	public static final double GOV_STOCK_CORRELATION = 0.1;
	public static final double CORPORATE_STOCK_CORRELATION = 0.5;

	private static final long SEED = 20_261_122L;
	private static final int MONTHS_PER_YEAR = 12;
	private static final double EPSILON = 1e-12;

	/** 市場因子：公債、公司債、股票 */
	private static final int GOV_FACTOR = 0;
	private static final int CORPORATE_FACTOR = 1;
	private static final int STOCK_FACTOR = 2;
	private static final int FACTORS = 3;

	/** 單年衝擊的上下限（標準差） */
	private static final double MAX_SHOCK = 6;

	private final CashFlows flows;
	private final int paths;
	private final int steps;
	private final int[] stepMonths;
	private final double[] stepYears;
	/** [因子][走勢 × 年數 + 年]：標準化的年衝擊 */
	private final double[][] factors;

	private final int segmentCount;
	private final int[] segmentStep;
	private final int[] segmentStart;
	private final int[] segmentMonths;
	private final double[] segmentWithdrawal;

	/** [標的][走勢 × 段數 + 段]：該段的總成長倍數，與每月 1 元月初投入到段末的金額 */
	private final double[][] growth;
	private final double[][] annuity;

	private final int stressFrom;
	private final int stressTo;

	/**
	 * @param flows 本金、每月投資、期間與提領
	 */
	public MarketSimulator(CashFlows flows, ReturnDistribution distribution) {
		this(flows, PATHS, randomFactors(PATHS, steps(flows.getMonths()), distribution), StressScenario.NONE, 0, 0);
	}

	/** 只有一條走勢、每年都是一般行情（沒有波動），用於逐年試算 */
	public static MarketSimulator typical(CashFlows flows) {
		return new MarketSimulator(flows, 1, new double[FACTORS][steps(flows.getMonths())], StressScenario.NONE, 0, 0);
	}

	private MarketSimulator(CashFlows flows, int paths, double[][] factors, StressScenario scenario, int stressFrom, int stressTo) {
		this.flows = flows;
		this.paths = paths;
		this.factors = factors;
		this.stressFrom = stressFrom;
		this.stressTo = stressTo;
		int months = flows.getMonths();
		steps = steps(months);
		stepMonths = new int[steps];
		stepYears = new double[steps];
		for (int k = 0; k < steps; k++) {
			stepMonths[k] = Math.min(MONTHS_PER_YEAR, months - k * MONTHS_PER_YEAR);
			stepYears[k] = stepMonths[k] / (double) MONTHS_PER_YEAR;
		}

		//每段在年底、提領月底或期滿結束；一段不會跨年
		boolean[] boundary = new boolean[months + 1];
		for (int m = MONTHS_PER_YEAR; m < months; m += MONTHS_PER_YEAR) {
			boundary[m] = true;
		}
		for (int month : flows.getWithdrawMonths()) {
			if (month >= 1 && month <= months) {
				boundary[month] = true;
			}
		}
		boundary[months] = true;
		int count = 0;
		for (int m = 1; m <= months; m++) {
			if (boundary[m]) {
				count++;
			}
		}
		segmentCount = count;
		segmentStep = new int[count];
		segmentStart = new int[count];
		segmentMonths = new int[count];
		segmentWithdrawal = new double[count];
		int start = 0;
		int j = 0;
		for (int m = 1; m <= months; m++) {
			if (boundary[m]) {
				segmentStep[j] = start / MONTHS_PER_YEAR;
				segmentStart[j] = start;
				segmentMonths[j] = m - start;
				segmentWithdrawal[j] = flows.withdrawalAt(m);
				start = m;
				j++;
			}
		}

		growth = new double[INSTRUMENTS.length][paths * segmentCount];
		annuity = new double[INSTRUMENTS.length][paths * segmentCount];
		for (int i = 0; i < INSTRUMENTS.length; i++) {
			fillTables(i, scenario);
		}
	}

	/** 同一組走勢，指定年份換成壓力情境 */
	public MarketSimulator stressed(StressScenario scenario, StressTiming timing) {
		int span = Math.min(scenario.getYears(), steps);
		int from = timing == StressTiming.START ? 0 : timing == StressTiming.END ? steps - span : (steps - span) / 2;
		return new MarketSimulator(flows, paths, factors, scenario, from, from + span);
	}

	/** 模擬的年數（最後不足一年也算一年） */
	public static int steps(int months) {
		return (months + MONTHS_PER_YEAR - 1) / MONTHS_PER_YEAR;
	}

	/** @return 標的在 {@link #INSTRUMENTS} 的序號，不可模擬時為 -1 */
	public static int instrumentOf(InvestmentTier tier) {
		for (int i = 0; i < INSTRUMENTS.length; i++) {
			if (INSTRUMENTS[i] == tier) {
				return i;
			}
		}
		return -1;
	}

	/** 壓力情境從第幾年開始（0 起算） */
	public int getStressFrom() {
		return stressFrom;
	}

	/** 壓力情境持續幾年 */
	public int getStressYears() {
		return stressTo - stressFrom;
	}

	public int getSteps() {
		return steps;
	}

	public CashFlows getFlows() {
		return flows;
	}

	/** 三個相關因子的標準化年衝擊；不論分布都抽同樣多的亂數，常態與厚尾的走勢才一一對應 */
	private static double[][] randomFactors(int paths, int steps, ReturnDistribution distribution) {
		double l21 = GOV_CORPORATE_CORRELATION;
		double l22 = Math.sqrt(1 - l21 * l21);
		double l31 = GOV_STOCK_CORRELATION;
		double l32 = (CORPORATE_STOCK_CORRELATION - l31 * l21) / l22;
		double l33 = Math.sqrt(1 - l31 * l31 - l32 * l32);
		int freedom = ReturnDistribution.DEGREES_OF_FREEDOM;
		boolean fat = distribution == ReturnDistribution.FAT_TAIL;

		RangeRandom random = new RangeRandom(SEED);
		double[][] factors = new double[FACTORS][paths * steps];
		for (int path = 0; path < paths; path++) {
			for (int k = 0; k < steps; k++) {
				double e1 = random.nextGaussian();
				double e2 = random.nextGaussian();
				double e3 = random.nextGaussian();
				double chiSquare = 0;
				for (int d = 0; d < freedom; d++) {
					double n = random.nextGaussian();
					chiSquare += n * n;
				}
				//t 分布的變異數為 ν / (ν - 2)，乘上 √((ν - 2) / χ²) 後標準差為 1
				double scale = fat ? Math.sqrt((freedom - 2) / Math.max(chiSquare, EPSILON)) : 1;
				int index = path * steps + k;
				factors[GOV_FACTOR][index] = clamp(e1 * scale);
				factors[CORPORATE_FACTOR][index] = clamp((l21 * e1 + l22 * e2) * scale);
				factors[STOCK_FACTOR][index] = clamp((l31 * e1 + l32 * e2 + l33 * e3) * scale);
			}
		}
		return factors;
	}

	private static double clamp(double shock) {
		return Math.max(-MAX_SHOCK, Math.min(MAX_SHOCK, shock));
	}

	/** 標的對應的市場因子，定存沒有波動時為 -1 */
	private static int factorOf(int instrument) {
		InvestmentTier tier = INSTRUMENTS[instrument];
		if (tier == InvestmentTier.DEPOSIT) {
			return -1;
		}
		if (tier == InvestmentTier.GOV_BOND) {
			return GOV_FACTOR;
		}
		return tier == InvestmentTier.CORPORATE_BOND ? CORPORATE_FACTOR : STOCK_FACTOR;
	}

	/** 先算每年的對數報酬，再換算成每段的成長倍數與月初投入的累積倍數（每月一次 exp，配置搜尋時只需乘加） */
	private void fillTables(int instrument, StressScenario scenario) {
		InvestmentTier tier = INSTRUMENTS[instrument];
		double drift = Math.log(1 + tier.getTypicalRate().doubleValue());
		double volatility = tier.getVolatility().doubleValue();
		int factor = factorOf(instrument);
		double[] growthTable = growth[instrument];
		double[] annuityTable = annuity[instrument];
		for (int path = 0; path < paths; path++) {
			int j = 0;
			for (int k = 0; k < steps; k++) {
				double shock = factor < 0 ? 0 : factors[factor][path * steps + k] * volatility * Math.sqrt(stepYears[k]);
				double logReturn;
				if (k >= stressFrom && k < stressTo) {
					double stressed = Math.log(1 + scenario.getReturn(instrument));
					logReturn = scenario.isRandom() ? stressed * stepYears[k] + shock : stressed;
				} else {
					logReturn = drift * stepYears[k] + shock;
				}
				double monthLog = logReturn / stepMonths[k];
				double monthGrowth = Math.exp(monthLog);
				for (; j < segmentCount && segmentStep[j] == k; j++) {
					int n = segmentMonths[j];
					double total = Math.exp(monthLog * n);
					int index = path * segmentCount + j;
					growthTable[index] = total;
					annuityTable[index] = Math.abs(monthGrowth - 1) < EPSILON ? n : monthGrowth * (total - 1) / (monthGrowth - 1);
				}
			}
		}
	}

	/**
	 * 模擬一種配置的所有走勢
	 *
	 * @param target 期滿要達到的金額；有提領付不出來的走勢不算達成
	 */
	public Outcome simulate(Allocation allocation, double target) {
		int[] instruments = allocation.instruments();
		double[][] weights = allocation.weights();
		RebalanceMode mode = allocation.rebalance();
		boolean scheduled = allocation.isScheduled();
		double principal = flows.getPrincipal();
		double monthly = flows.getMonthly();
		double tolerance = EPSILON * Math.max(1, Math.abs(target));
		double[] g0 = growth[instruments[0]];
		double[] g1 = growth[instruments[1]];
		double[] g2 = growth[instruments[2]];
		double[] g3 = growth[instruments[3]];
		double[] a0 = annuity[instruments[0]];
		double[] a1 = annuity[instruments[1]];
		double[] a2 = annuity[instruments[2]];
		double[] a3 = annuity[instruments[3]];

		double[] finals = new double[paths];
		int successes = 0;
		for (int path = 0; path < paths; path++) {
			double[] w = weights[0];
			double v0 = principal * w[0];
			double v1 = principal * w[1];
			double v2 = principal * w[2];
			double v3 = principal * w[3];
			boolean failed = false;
			int base = path * segmentCount;
			for (int j = 0; j < segmentCount; j++) {
				int start = segmentStart[j];
				if (start > 0 && start % MONTHS_PER_YEAR == 0) {
					w = weights[segmentStep[j]];
					double total = v0 + v1 + v2 + v3;
					boolean rebalance = scheduled || mode == RebalanceMode.YEARLY;
					if (!rebalance && mode == RebalanceMode.BAND && total > 0) {
						rebalance = Math.abs(v0 / total - w[0]) > RebalanceMode.BAND_WIDTH || Math.abs(v1 / total - w[1]) > RebalanceMode.BAND_WIDTH
								|| Math.abs(v2 / total - w[2]) > RebalanceMode.BAND_WIDTH || Math.abs(v3 / total - w[3]) > RebalanceMode.BAND_WIDTH;
					}
					if (rebalance) {
						v0 = total * w[0];
						v1 = total * w[1];
						v2 = total * w[2];
						v3 = total * w[3];
					}
				}
				int index = base + j;
				v0 = v0 * g0[index] + monthly * w[0] * a0[index];
				v1 = v1 * g1[index] + monthly * w[1] * a1[index];
				v2 = v2 * g2[index] + monthly * w[2] * a2[index];
				v3 = v3 * g3[index] + monthly * w[3] * a3[index];
				double out = segmentWithdrawal[j];
				if (out > 0) {
					double total = v0 + v1 + v2 + v3;
					if (total < out - tolerance) {
						failed = true;
						v0 = 0;
						v1 = 0;
						v2 = 0;
						v3 = 0;
					} else {
						double keep = total <= 0 ? 0 : (total - out) / total;
						v0 *= keep;
						v1 *= keep;
						v2 *= keep;
						v3 *= keep;
					}
				}
			}
			double end = v0 + v1 + v2 + v3;
			finals[path] = end;
			if (!failed && end >= target - tolerance) {
				successes++;
			}
		}
		Arrays.sort(finals);
		return new Outcome(finals, successes / (double) paths);
	}

	/**
	 * 第一條走勢（{@link #typical} 只有一條，為一般行情）在每年底與期滿時，各部位的金額與累積獲利
	 */
	public List<Snapshot> trace(Allocation allocation) {
		int[] instruments = allocation.instruments();
		double[][] weights = allocation.weights();
		RebalanceMode mode = allocation.rebalance();
		boolean scheduled = allocation.isScheduled();
		double monthly = flows.getMonthly();
		double[] w = weights[0];
		double[] values = new double[Allocation.SLOTS];
		double[] gains = new double[Allocation.SLOTS];
		for (int slot = 0; slot < Allocation.SLOTS; slot++) {
			values[slot] = flows.getPrincipal() * w[slot];
		}
		double withdrawn = 0;
		List<Snapshot> snapshots = new ArrayList<>();
		for (int j = 0; j < segmentCount; j++) {
			int start = segmentStart[j];
			if (start > 0 && start % MONTHS_PER_YEAR == 0) {
				w = weights[segmentStep[j]];
				double total = sum(values);
				boolean rebalance = scheduled || mode == RebalanceMode.YEARLY;
				for (int slot = 0; !rebalance && mode == RebalanceMode.BAND && total > 0 && slot < Allocation.SLOTS; slot++) {
					rebalance = Math.abs(values[slot] / total - w[slot]) > RebalanceMode.BAND_WIDTH;
				}
				for (int slot = 0; rebalance && slot < Allocation.SLOTS; slot++) {
					values[slot] = total * w[slot];
				}
			}
			int n = segmentMonths[j];
			for (int slot = 0; slot < Allocation.SLOTS; slot++) {
				double before = values[slot];
				double contributed = monthly * w[slot] * n;
				values[slot] = before * growth[instruments[slot]][j] + monthly * w[slot] * annuity[instruments[slot]][j];
				gains[slot] += values[slot] - before - contributed;
			}
			double out = segmentWithdrawal[j];
			if (out > 0) {
				double total = sum(values);
				double keep = total <= out ? 0 : (total - out) / total;
				withdrawn += Math.min(out, total);
				for (int slot = 0; slot < Allocation.SLOTS; slot++) {
					values[slot] *= keep;
				}
			}
			int end = start + n;
			if (end % MONTHS_PER_YEAR == 0 || end == flows.getMonths()) {
				snapshots.add(new Snapshot(end, flows.investedBy(end), withdrawn, values.clone(), gains.clone()));
			}
		}
		return snapshots;
	}

	private static double sum(double[] values) {
		double total = 0;
		for (double value : values) {
			total += value;
		}
		return total;
	}

	/** @return 期滿金額達到 target 的走勢比例 */
	public static double probability(double[] sortedFinals, double target) {
		int low = 0;
		int high = sortedFinals.length;
		//第一個不小於 target 的位置
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (sortedFinals[middle] < target - EPSILON * Math.max(1, Math.abs(target))) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		return (sortedFinals.length - low) / (double) sortedFinals.length;
	}

	/**
	 * @param fraction ex. 0.1 為第 10 百分位（只有 10% 的走勢更差）
	 */
	public static double percentile(double[] sortedFinals, double fraction) {
		double position = fraction * (sortedFinals.length - 1);
		int index = (int) Math.floor(position);
		if (index >= sortedFinals.length - 1) {
			return sortedFinals[sortedFinals.length - 1];
		}
		double weight = position - index;
		return sortedFinals[index] * (1 - weight) + sortedFinals[index + 1] * weight;
	}

	/** 一種配置的模擬結果 */
	public static final class Outcome {

		private final double[] finals;
		private final double probability;

		Outcome(double[] finals, double probability) {
			this.finals = finals;
			this.probability = probability;
		}

		/** 每條走勢的期滿金額，由小到大 */
		public double[] getFinals() {
			return finals;
		}

		/** 達成目標（且每筆提領都付得出來）的走勢比例 */
		public double getProbability() {
			return probability;
		}

		public double percentile(double fraction) {
			return MarketSimulator.percentile(finals, fraction);
		}
	}

	/** 逐年試算的一列：第 months 個月底，各部位（定存、公債、公司債、股票）的金額與累積獲利 */
	public static final class Snapshot {

		private final int months;
		private final double invested;
		private final double withdrawn;
		private final double[] values;
		private final double[] gains;

		Snapshot(int months, double invested, double withdrawn, double[] values, double[] gains) {
			this.months = months;
			this.invested = invested;
			this.withdrawn = withdrawn;
			this.values = values;
			this.gains = gains;
		}

		public int getMonths() {
			return months;
		}

		/** 累積投入 */
		public double getInvested() {
			return invested;
		}

		/** 累積提領 */
		public double getWithdrawn() {
			return withdrawn;
		}

		public double[] getValues() {
			return values;
		}

		/** 各部位累積賺到的獲利（再平衡只搬動金額，不改變獲利） */
		public double[] getGains() {
			return gains;
		}
	}
}
