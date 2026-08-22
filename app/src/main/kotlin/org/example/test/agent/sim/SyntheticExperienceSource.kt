package org.example.test.agent.sim

import org.example.test.agent.AgentFeatureStore
import org.example.test.agent.MarketFeatureFrame
import org.example.test.bitget.Kline
import kotlin.random.Random

/**
 * A [MarketFeatureFrame] paired with the ground truth its live counterpart
 * never has: which [MarketRegime] actually produced it, and whether the
 * bar behind it was a forced jump/extreme event. This is the labeled unit
 * design doc §4.2 stratifies the replay buffer by ("Stratify the replay
 * buffer by detected regime... Oversample rare-but-critical regimes").
 */
data class LabeledFrame(
    val frame: MarketFeatureFrame,
    val regime: MarketRegime,
    val jumpFired: Boolean,
)

/** One synthetic training episode: the labeled feature-frame stream plus the raw bars that produced it, for anything that wants OHLCV/trades/depth directly instead of the assembled frame. */
data class SyntheticEpisode(
    val frames: List<LabeledFrame>,
    val bars: List<SyntheticBar>,
) {
    /** How many bars of this episode landed in each regime - the input a curriculum sampler needs to check its oversampling is actually working. */
    val regimeBarCounts: Map<MarketRegime, Int> get() = frames.groupingBy { it.regime }.eachCount()
}

/**
 * Design doc §4.1 Tier 1, end to end: "Synthetic regime generator
 * (jump-diffusion, regime-switching GARCH, Hawkes processes) — Cheap,
 * infinite data; force rare/extreme events." Where [RegimeSwitchingPriceProcess],
 * [HawkesTradeClock], and [SyntheticMarketDataGenerator] each model one
 * piece of that, this class is the piece that turns "we can generate
 * plausible bars" into "we can generate labeled experience the rest of
 * the system already knows how to consume" - it drives a fresh
 * [AgentFeatureStore] (the exact same aggregation logic the *live*
 * [org.example.test.agent.AgentDataIngestionService] uses) bar by bar, so
 * a [SyntheticEpisode]'s frames are indistinguishable in shape from what a
 * regime detector or replay buffer would receive from the real pipeline -
 * they just come with an answer key attached.
 *
 * Every timestamp fed into the feature store is the synthetic bar clock,
 * not wall-clock time, so staleness/quality and OI-change-window
 * computations behave correctly for a backtest-speed episode instead of
 * reporting everything as instantly stale (or instantly "fresh" no matter
 * how far apart the bars really are).
 */
class SyntheticExperienceSource(
    private val symbol: String = "BTCUSDT",
    private val barDurationMs: Long = 60_000L,
    private val klineHistoryCapacity: Int = 500,
    private val staleThresholdMs: Long = barDurationMs * 3,
) {
    /**
     * Generates [barCount] consecutive bars starting in [startRegime],
     * free-running through [RegimeTransitionMatrix] from there - i.e. an
     * "ordinary" episode, not one biased toward any particular regime.
     */
    fun generateEpisode(
        barCount: Int,
        startPrice: Double = 60_000.0,
        startTimeMs: Long = System.currentTimeMillis(),
        startRegime: MarketRegime = MarketRegime.LOW_VOL,
        regimeParams: Map<MarketRegime, RegimeParams> = RegimeLibrary.DEFAULT,
        seed: Long = Random.nextLong(),
    ): SyntheticEpisode {
        val priceProcess = RegimeSwitchingPriceProcess(
            startPrice = startPrice,
            regimeParams = regimeParams,
            startRegime = startRegime,
            seed = seed,
        )
        // Distinct-but-reproducible seed for the data generator, so trade/
        // depth noise doesn't share (and silently correlate with) the exact
        // Gaussian stream driving price itself.
        val dataGenerator = SyntheticMarketDataGenerator(
            symbol = symbol,
            barDurationMs = barDurationMs,
            regimeParams = regimeParams,
            seed = seed xor 0x9E3779B97F4A7C15L,
        )
        val featureStore = AgentFeatureStore()
        val klineHistory = ArrayDeque<Kline>(klineHistoryCapacity)

        val frames = ArrayList<LabeledFrame>(barCount)
        val bars = ArrayList<SyntheticBar>(barCount)

        var prevClose = startPrice
        var barStartMs = startTimeMs
        repeat(barCount) {
            val step = priceProcess.nextStep()
            val bar = dataGenerator.barFrom(step, prevClose, barStartMs)
            prevClose = step.price

            val barEndMs = barStartMs + barDurationMs
            klineHistory.addLast(bar.kline)
            if (klineHistory.size > klineHistoryCapacity) klineHistory.removeFirst()

            featureStore.onKlines(klineHistory.toList(), nowMs = barEndMs)
            for (trade in bar.trades) featureStore.onTrade(trade)
            featureStore.onDepth(bar.depth, nowMs = barEndMs)
            featureStore.onTicker(bar.ticker, nowMs = barEndMs)

            val frame = featureStore.snapshot(nowMs = barEndMs, staleThresholdMs = staleThresholdMs)
            frames.add(LabeledFrame(frame = frame, regime = step.regime, jumpFired = step.jumpFired))
            bars.add(bar)

            barStartMs = barEndMs
        }
        return SyntheticEpisode(frames = frames, bars = bars)
    }

    /**
     * Design doc §4.2's "oversample rare-but-critical regimes" for a single
     * episode: starts in [regime] and stretches its sojourn so the episode
     * spends (almost) the whole run there instead of switching away after
     * a realistic-length dwell - useful for forcing a full episode of
     * [MarketRegime.CRISIS] (a liquidation cascade) rather than waiting for
     * one to show up naturally in a free-running [generateEpisode] call.
     */
    fun generateForcedRegimeEpisode(
        regime: MarketRegime,
        barCount: Int,
        startPrice: Double = 60_000.0,
        startTimeMs: Long = System.currentTimeMillis(),
        seed: Long = Random.nextLong(),
    ): SyntheticEpisode {
        val stretched = RegimeLibrary.DEFAULT.toMutableMap()
        val base = stretched.getValue(regime)
        stretched[regime] = base.copy(meanSojournBars = barCount.toDouble() * 3.0)
        return generateEpisode(
            barCount = barCount,
            startPrice = startPrice,
            startTimeMs = startTimeMs,
            startRegime = regime,
            regimeParams = stretched,
            seed = seed,
        )
    }

    /**
     * Builds a batch of episodes drawn from [regimeWeights] rather than
     * each regime's natural (Markov-stationary) share - the mechanism
     * behind design doc §4.2's curriculum stratification. Defaults to
     * [DEFAULT_CURRICULUM_WEIGHTS], which deliberately over-represents
     * [MarketRegime.CRISIS] relative to how rarely it would occur
     * free-running, matching the doc's explicit call-out of liquidation
     * cascades as a regime worth forcing rather than waiting for.
     */
    fun generateCurriculumBatch(
        episodeCount: Int,
        barsPerEpisode: Int,
        regimeWeights: Map<MarketRegime, Double> = DEFAULT_CURRICULUM_WEIGHTS,
        startPrice: Double = 60_000.0,
        seed: Long = Random.nextLong(),
    ): List<SyntheticEpisode> {
        val samplingRng = Random(seed)
        val totalWeight = regimeWeights.values.sum()
        return List(episodeCount) {
            var draw = samplingRng.nextDouble() * totalWeight
            var chosenRegime = regimeWeights.keys.first()
            for ((regime, weight) in regimeWeights) {
                draw -= weight
                if (draw <= 0.0) {
                    chosenRegime = regime
                    break
                }
            }
            generateForcedRegimeEpisode(
                regime = chosenRegime,
                barCount = barsPerEpisode,
                startPrice = startPrice,
                seed = samplingRng.nextLong(),
            )
        }
    }

    companion object {
        val DEFAULT_CURRICULUM_WEIGHTS: Map<MarketRegime, Double> = mapOf(
            MarketRegime.BULL to 0.20,
            MarketRegime.BEAR to 0.20,
            MarketRegime.HIGH_VOL to 0.20,
            MarketRegime.LOW_VOL to 0.15,
            MarketRegime.CRISIS to 0.25,
        )
    }
}
