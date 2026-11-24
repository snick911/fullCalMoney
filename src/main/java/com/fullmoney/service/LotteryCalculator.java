package com.fullmoney.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fullmoney.bean.LotteryGame;
import com.fullmoney.bean.LotteryPrize;
import com.fullmoney.bean.LotteryPrizeValue;

/**
 * 彩券的回報率：每注平均拿回多少（Σ 中獎機率 × 獎金），以及長期定期買入的模擬
 *
 * <p>獎金池的獎項以長期平均估算：每注貢獻給獎金池的金額 = 每注價格 × 總獎金支出率 − 每注平均的固定獎金，
 * 某獎項的平均獎金 = 每注貢獻 × 該獎分配比率 ÷ 中獎機率。頭獎無人中獎時累積到下期，長期來看每注貢獻的金額最後都會發出去，
 * 所以稅前回報率剛好等於總獎金支出率；使用者輸入頭獎金額時，頭獎改用該金額（未計入多人同時中獎平分）。</p>
 */
public final class LotteryCalculator {

	private static final int AMOUNT_SCALE = 2;
	private static final int RATIO_SCALE = 12;
	private static final BigDecimal KEEP_AFTER_TAX = BigDecimal.ONE.subtract(LotteryGame.TAX_RATE);

	/** 二項分布的平均次數低於此值時逐一累加機率（精確），否則以常態分布近似 */
	private static final double EXACT_BINOMIAL_MEAN = 30;
	private static final double SMALL_PROBABILITY = 1e-4;

	/**
	 * @return 頭獎的長期平均獎金；頭獎是固定獎金時為 null
	 */
	public BigDecimal averageJackpot(LotteryGame game) {
		if (!game.hasPool()) {
			return null;
		}
		return poolAverage(game, game.getPrizes().get(0));
	}

	/**
	 * @param jackpot 使用者輸入的頭獎金額，null 時以長期平均估算；頭獎是固定獎金時不使用
	 * @return 由頭獎到最小獎
	 */
	public List<LotteryPrizeValue> prizeValues(LotteryGame game, BigDecimal jackpot) {
		BigDecimal total = BigDecimal.valueOf(game.getTotalCombinations());
		List<LotteryPrizeValue> values = new ArrayList<>(game.getPrizes().size());
		for (int i = 0; i < game.getPrizes().size(); i++) {
			LotteryPrize prize = game.getPrizes().get(i);
			BigDecimal amount;
			boolean estimated = false;
			if (!prize.isPool()) {
				amount = prize.getFixedAmount();
			} else if (i == 0 && jackpot != null) {
				amount = jackpot;
			} else {
				amount = poolAverage(game, prize);
				estimated = true;
			}
			BigDecimal afterTax = afterTax(amount);
			BigDecimal combinations = BigDecimal.valueOf(prize.getCombinations());
			BigDecimal expected = afterTax.multiply(combinations).divide(total, RATIO_SCALE, RoundingMode.HALF_UP);
			double probability = (double) prize.getCombinations() / game.getTotalCombinations();
			values.add(new LotteryPrizeValue(prize, probability, amount, afterTax, estimated, expected));
		}
		return values;
	}

	/** @return 每注平均拿回的稅前金額 */
	public BigDecimal expectedPerBet(LotteryGame game, List<LotteryPrizeValue> values) {
		BigDecimal sum = BigDecimal.ZERO;
		for (LotteryPrizeValue value : values) {
			sum = sum.add(value.getAmount().multiply(BigDecimal.valueOf(value.getPrize().getCombinations())));
		}
		return sum.divide(BigDecimal.valueOf(game.getTotalCombinations()), RATIO_SCALE, RoundingMode.HALF_UP);
	}

	/** @return 每注平均拿回的稅後金額 */
	public BigDecimal expectedPerBetAfterTax(List<LotteryPrizeValue> values) {
		BigDecimal sum = BigDecimal.ZERO;
		for (LotteryPrizeValue value : values) {
			sum = sum.add(value.getExpectedValue());
		}
		return sum;
	}

	/** 單注超過 5,000 元扣 20% 所得稅與千分之四印花稅 */
	public static BigDecimal afterTax(BigDecimal amount) {
		if (amount.compareTo(LotteryGame.TAX_FREE_LIMIT) <= 0) {
			return amount;
		}
		return amount.multiply(KEEP_AFTER_TAX).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
	}

	/** 獎金池獎項的長期平均獎金 = （每注價格 × 支出率 × 總組合數 − 固定獎金總額）× 分配比率 ÷ 中獎組合數 */
	private static BigDecimal poolAverage(LotteryGame game, LotteryPrize prize) {
		BigDecimal pool = BigDecimal.valueOf(game.getPrice()).multiply(game.getPayoutRate()).multiply(BigDecimal.valueOf(game.getTotalCombinations()));
		for (LotteryPrize other : game.getPrizes()) {
			if (!other.isPool()) {
				pool = pool.subtract(other.getFixedAmount().multiply(BigDecimal.valueOf(other.getCombinations())));
			}
		}
		return pool.multiply(prize.getPoolShare()).divide(BigDecimal.valueOf(prize.getCombinations()), AMOUNT_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 模擬 paths 個人各買 bets 注的稅後中獎總額。每注只會中一個獎項，各獎項的中獎次數依序以條件二項分布抽出（多項分布）
	 *
	 * @return 由小到大排序
	 */
	public double[] simulate(List<LotteryPrizeValue> values, double bets, int paths, long seed) {
		RangeRandom random = new RangeRandom(seed);
		double[] winnings = new double[paths];
		for (int path = 0; path < paths; path++) {
			double remaining = bets;
			double remainingProbability = 1;
			double sum = 0;
			for (LotteryPrizeValue value : values) {
				double p = value.getProbability();
				if (remaining <= 0 || remainingProbability <= 0) {
					break;
				}
				double count = binomial(remaining, Math.min(1, p / remainingProbability), random);
				sum += count * value.getAfterTax().doubleValue();
				remaining -= count;
				remainingProbability -= p;
			}
			winnings[path] = sum;
		}
		Arrays.sort(winnings);
		return winnings;
	}

	/** @return 排序後陣列的第 q 分位數（0～1，取最接近的一筆） */
	public static double percentile(double[] sorted, double q) {
		int index = (int) Math.round(q * (sorted.length - 1));
		return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
	}

	/** @return 機率 p 的事件在 n 次中至少發生一次的機率 1 − (1 − p)^n */
	public static double atLeastOnce(double p, double n) {
		double x = n * logOneMinus(p);
		//x 很小時 exp(x) − 1 直接相減會失去精度，改用泰勒展開
		return Math.abs(x) < 1e-5 ? -(x + x * x / 2 + x * x * x / 6) : 1 - Math.exp(x);
	}

	/**
	 * 同樣的錢改成定期投資：每次買入時投入，年報酬率 rate 依買入次數換算成每期報酬複利
	 *
	 * @return 投入 count 次後的期末金額
	 */
	public static double investedValue(double each, int timesPerYear, double count, double rate) {
		if (rate == 0) {
			return each * count;
		}
		double growth = Math.pow(1 + rate, 1.0 / timesPerYear);
		return each * growth * (Math.pow(growth, count) - 1) / (growth - 1);
	}

	/**
	 * 二項分布 B(n, p) 的亂數。平均次數少時以逆轉換法精確抽樣，多時以常態分布近似（誤差遠小於模擬本身的誤差）
	 */
	static double binomial(double n, double p, RangeRandom random) {
		if (n <= 0 || p <= 0) {
			return 0;
		}
		if (p >= 1) {
			return n;
		}
		double mean = n * p;
		if (mean < EXACT_BINOMIAL_MEAN) {
			double s = p / (1 - p);
			double a = (n + 1) * s;
			double r = Math.exp(n * logOneMinus(p));
			double u = random.nextDouble();
			double x = 0;
			//浮點誤差讓機率總和略小於 1 時，避免一直累加下去
			double limit = Math.min(n, mean + 40 * Math.sqrt(mean) + 40);
			while (u > r && x < limit) {
				u -= r;
				x++;
				r *= a / x - s;
			}
			return x;
		}
		double x = Math.floor(mean + Math.sqrt(mean * (1 - p)) * random.nextGaussian() + 0.5);
		return Math.max(0, Math.min(n, x));
	}

	/** ln(1 − p)；p 很小時 1 − p 會失去精度，改用泰勒展開 */
	private static double logOneMinus(double p) {
		if (p < SMALL_PROBABILITY) {
			return -(p + p * p / 2 + p * p * p / 3);
		}
		return Math.log(1 - p);
	}
}
