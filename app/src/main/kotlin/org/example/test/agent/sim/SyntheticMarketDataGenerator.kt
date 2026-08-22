package org.example.test.agent.sim

import org.example.test.bitget.BookSide
import org.example.test.bitget.DepthLevel
import org.example.test.bitget.DepthSnapshot
import org.example.test.bitget.Kline
import org.example.test.bitget.PublicTrade
import org.example.test.bitget.TickerSnapshot
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh
import kotlin.random.Random

/**
 * One synthetic bar in every shape the real ingestion pipeline consumes -
 * the same [Kline]/[PublicTrade]/[DepthSnapshot]/[TickerSnapshot] types
 * [org.example.test.agent.AgentFeatureStore] already knows how to turn
 * into a [org.example.test.agent.MarketFeatureFrame] - plus the ground-truth
 * [regime] label those live types have no room for. That label is the
 * entire point of Tier 1 (design doc §4.1): a real feature frame doesn't
 * come with an answer key, a synthetic one can.
 */
data class SyntheticBar(
    val regime: MarketRegime,
    val jumpFired: Boolean,
    val kline: Kline,
    val trades: List<PublicTrade>,
    val depth: DepthSnapshot,
    val ticker: TickerSnapshot,
)

/**
 * Converts the abstract [RegimeStep] stream from [RegimeSwitchingPriceProcess]
 * into concrete, internally-consistent market data: an OHLCV candle shaped
 * by a short intrabar random walk, a burst of [PublicTrade]s timed by
 * [HawkesTradeClock] and sized/sided so aggressor flow agrees with the
 * bar's realized direction, a synthetic order book whose spread/depth
 * track regime volatility, and a ticker carrying mark/index/funding/OI
 * dynamics (design doc §5.1, §4.5's "funding rate cycles (8h resets)").
 *
 * Stateful only in the sense that open interest and funding rate evolve
 * bar-over-bar (they're levels, not i.i.d. draws) - price itself is *not*
 * tracked here, it's read off each [RegimeStep] the caller already
 * generated, so this class never disagrees with the process driving it.
 */
class SyntheticMarketDataGenerator(
    private val symbol: String = "BTCUSDT",
    private val barDurationMs: Long = 60_000L,
    private val depthLevels: Int = 20,
    private val fundingIntervalMs: Long = 8L * 60 * 60_000,
    private val startOpenInterest: Double = 50_000.0,
    private val regimeParams: Map<MarketRegime, RegimeParams> = RegimeLibrary.DEFAULT,
    seed: Long = Random.nextLong(),
) {
    private val rng = Random(seed)

    private var openInterest: Double = startOpenInterest
    private var fundingRate: Double = 0.0001
    private var lastFundingResetMs: Long = 0L

    /**
     * Builds one [SyntheticBar]. [prevClose] is the previous bar's close
     * (or the series' start price for the first bar) - kept as an explicit
     * parameter rather than internal state so a caller can splice/replay
     * bars out of order (e.g. resuming a curriculum episode) without this
     * class silently disagreeing about where the candle should open.
     */
    fun barFrom(step: RegimeStep, prevClose: Double, barStartTimeMs: Long): SyntheticBar {
        val params = regimeParams.getValue(step.regime)
        val open = prevClose
        val close = step.price

        val (high, low) = intrabarRange(open, close, step.instantVolPerBar)

        val durationSeconds = barDurationMs / 1000.0
        val jumpKicks = if (step.jumpFired) {
            // A fired jump also announces itself as a burst of panicked/
            // euphoric prints roughly when it happens in the bar, not just
            // a bigger candle body - liquidation cascades are as much
            // about *how many* orders hit the tape as how far price moves.
            listOf(rng.nextDouble() * durationSeconds to params.hawkesBaselineIntensity * 4.0)
        } else {
            emptyList()
        }
        val arrivalOffsets = HawkesTradeClock.simulateArrivals(
            durationSeconds = durationSeconds,
            baselineIntensity = params.hawkesBaselineIntensity,
            excitation = params.hawkesExcitation,
            decayPerSec = params.hawkesDecayPerSec,
            rng = rng,
            extraKickAt = jumpKicks,
        )
        val trades = synthesizeTrades(arrivalOffsets, barStartTimeMs, open, high, low, close)

        val (baseVolume, quoteVolume) = trades.fold(0.0 to 0.0) { (baseAcc, quoteAcc), t ->
            (baseAcc + t.size) to (quoteAcc + t.size * t.price)
        }
        val kline = Kline(
            startTime = barStartTimeMs,
            open = open,
            high = high,
            low = low,
            close = close,
            baseVolume = baseVolume,
            quoteVolume = quoteVolume,
            usdtVolume = quoteVolume,
        )

        val barEndMs = barStartTimeMs + barDurationMs
        val depth = synthesizeDepth(close, step.instantVolPerBar, barEndMs)
        val ticker = synthesizeTicker(step, close, barEndMs)

        return SyntheticBar(
            regime = step.regime,
            jumpFired = step.jumpFired,
            kline = kline,
            trades = trades,
            depth = depth,
            ticker = ticker,
        )
    }

    /** Sequentially builds [steps].size bars, threading close-to-open continuity for the caller. */
    fun barsFrom(steps: List<RegimeStep>, startPrice: Double, startTimeMs: Long): List<SyntheticBar> {
        var prevClose = startPrice
        var t = startTimeMs
        return steps.map { step ->
            val bar = barFrom(step, prevClose, t)
            prevClose = step.price
            t += barDurationMs
            bar
        }
    }

    /** A short intrabar random walk so high/low aren't just max/min(open, close) - real candles wick beyond their body. */
    private fun intrabarRange(open: Double, close: Double, instantVolPerBar: Double): Pair<Double, Double> {
        val subSteps = 8
        val subVol = instantVolPerBar / kotlin.math.sqrt(subSteps.toDouble())
        var path = open
        var high = max(open, close)
        var low = min(open, close)
        repeat(subSteps) {
            path *= exp(subVol * rng.nextGaussian())
            high = max(high, path)
            low = min(low, path)
        }
        return max(high, close) to min(low, close)
    }

    /**
     * Sizes and sides each Hawkes arrival: side is biased toward the
     * bar's realized direction (informed flow tends to push price the way
     * it ends up going) with regime-scaled noise so it's not a tautology,
     * and price walks a short path from open to close across the arrivals
     * so trade prints - not just the candle - look internally consistent.
     */
    private fun synthesizeTrades(
        arrivalOffsets: List<Double>,
        barStartTimeMs: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
    ): List<PublicTrade> {
        if (arrivalOffsets.isEmpty()) return emptyList()
        val directionalBias = tanh((close - open) / open * 40.0) // in [-1, 1], saturates for big moves
        return arrivalOffsets.mapIndexed { i, offsetSec ->
            val progress = (i + 1).toDouble() / arrivalOffsets.size
            // Price glides open->close with bounded excursions into the bar's own high/low band.
            val glide = open + (close - open) * progress
            val band = (high - low).coerceAtLeast(open * 1e-6)
            val noisy = (glide + band * 0.15 * rng.nextGaussian()).coerceIn(low, high)

            val buyProb = (0.5 + 0.35 * directionalBias).coerceIn(0.05, 0.95)
            val side = if (rng.nextDouble() < buyProb) BookSide.BID else BookSide.ASK

            // Log-normal trade size: mostly small prints, occasional large ones - fat right tail like real tape.
            val size = exp(-1.5 + 0.9 * rng.nextGaussian()).coerceAtLeast(0.001)

            PublicTrade(
                price = noisy,
                size = size,
                side = side,
                timestampMs = barStartTimeMs + (offsetSec * 1000.0).toLong(),
            )
        }
    }

    /**
     * A plausible order book around [midPrice]: spread and level-to-level
     * size decay both widen/thin with [instantVolPerBar], so a crisis-regime
     * bar produces the thin, skittish book a real liquidation cascade
     * leaves behind rather than the same static depth in every regime.
     */
    private fun synthesizeDepth(midPrice: Double, instantVolPerBar: Double, nowMs: Long): DepthSnapshot {
        val volFloor = 0.0005
        val vol = max(instantVolPerBar, volFloor)
        val spreadBps = (0.5 + vol * 8_000.0).coerceIn(0.5, 250.0)
        val halfSpread = midPrice * spreadBps / 10_000.0 / 2.0
        val tickSize = max(midPrice * 0.00005, 0.01)

        // More vol -> less size resting per level (liquidity thins exactly when it's needed most).
        val baseLevelSize = (30.0 / (1.0 + vol * 400.0)).coerceAtLeast(0.05)
        val decay = 0.85

        val bids = ArrayList<DepthLevel>(depthLevels)
        val asks = ArrayList<DepthLevel>(depthLevels)
        var bidPrice = midPrice - halfSpread
        var askPrice = midPrice + halfSpread
        var levelSize = baseLevelSize
        repeat(depthLevels) {
            val bidJitter = 1.0 + 0.15 * rng.nextGaussian()
            val askJitter = 1.0 + 0.15 * rng.nextGaussian()
            bids.add(DepthLevel(price = bidPrice, size = max(levelSize * bidJitter, 0.001)))
            asks.add(DepthLevel(price = askPrice, size = max(levelSize * askJitter, 0.001)))
            bidPrice -= tickSize * (1 + rng.nextDouble())
            askPrice += tickSize * (1 + rng.nextDouble())
            levelSize *= decay
        }

        return DepthSnapshot(bids = bids, asks = asks, lastUpdateMs = nowMs, lastSeq = nowMs)
    }

    private fun synthesizeTicker(step: RegimeStep, lastPrice: Double, nowMs: Long): TickerSnapshot {
        // Open interest: drifts with the regime's directional conviction, but
        // a fired jump represents forced deleveraging - OI drops sharply
        // exactly on cascade bars, mirroring real liquidation behavior
        // (design doc §5.1's liquidation feed as a "leading indicator for
        // cascades" - here it's the trailing footprint of one).
        val regimeOiDrift = when (step.regime) {
            MarketRegime.BULL -> 0.0006
            MarketRegime.BEAR -> 0.0004
            MarketRegime.HIGH_VOL -> 0.0002
            MarketRegime.LOW_VOL -> 0.0001
            MarketRegime.CRISIS -> -0.0015
        }
        openInterest *= (1.0 + regimeOiDrift + 0.001 * rng.nextGaussian())
        if (step.jumpFired) {
            openInterest *= (1.0 - (0.03 + 0.05 * abs(step.jumpLogSize)).coerceAtMost(0.35))
        }
        openInterest = openInterest.coerceAtLeast(startOpenInterest * 0.05)

        // Funding: mean-reverting toward a regime-typical level, resetting
        // (§4.5's 8h cycle) on its own clock independent of bar duration.
        if (nowMs - lastFundingResetMs >= fundingIntervalMs) {
            lastFundingResetMs = nowMs - (nowMs % fundingIntervalMs)
        }
        val fundingTarget = when (step.regime) {
            MarketRegime.BULL -> 0.0004
            MarketRegime.BEAR -> -0.0003
            MarketRegime.HIGH_VOL -> 0.0
            MarketRegime.LOW_VOL -> 0.0001
            MarketRegime.CRISIS -> -0.0020
        }
        fundingRate += 0.05 * (fundingTarget - fundingRate) + 0.00005 * rng.nextGaussian()
        fundingRate = fundingRate.coerceIn(-0.0075, 0.0075) // Bitget-style funding rate cap

        val basisNoiseBps = (2.0 + step.instantVolPerBar * 3_000.0) * rng.nextGaussian()
        val markPrice = lastPrice * (1.0 + basisNoiseBps / 10_000.0)
        val indexPrice = lastPrice * (1.0 + (basisNoiseBps * 0.3) / 10_000.0)

        val spreadBps = (0.5 + step.instantVolPerBar * 8_000.0).coerceIn(0.5, 250.0)
        val halfSpread = lastPrice * spreadBps / 10_000.0 / 2.0

        return TickerSnapshot(
            symbol = symbol,
            lastPrice = lastPrice,
            markPrice = markPrice,
            indexPrice = indexPrice,
            fundingRate = fundingRate,
            nextFundingTimeMs = lastFundingResetMs + fundingIntervalMs,
            openInterest = openInterest,
            bestBid = lastPrice - halfSpread,
            bestAsk = lastPrice + halfSpread,
            baseVolume24h = null,
            quoteVolume24h = null,
            timestampMs = nowMs,
        )
    }
}
