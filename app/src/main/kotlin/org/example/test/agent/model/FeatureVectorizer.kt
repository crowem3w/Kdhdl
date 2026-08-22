package org.example.test.agent.model

import org.example.test.agent.MarketFeatureFrame
import kotlin.math.ln
import kotlin.math.min

/**
 * Turns the human-readable [MarketFeatureFrame] (design doc §7.4's shared
 * feature-store record) into the fixed-width, normalized float tensor the
 * [CompactLatentStateEncoder] (design doc §3.1) actually consumes.
 *
 * Two design choices carry over from [MarketFeatureFrame]'s own doc comment:
 *
 * 1. **Value/mask pairs, not zero-fill.** Every nullable source field
 *    becomes *two* numbers: a value (0.0 when absent) and a mask (1.0 when
 *    present, 0.0 when absent). A silently-zeroed feature is indistinguishable
 *    from a real zero reading to a downstream matrix multiply; the mask bit
 *    is what lets the encoder eventually learn "missing OI" isn't "zero OI".
 * 2. **Non-stationary fields become returns/deltas, not levels.** Feeding a
 *    recurrent encoder a raw price level (e.g. "62,431.5") means its scale
 *    keeps drifting with BTC's price across months of training and live
 *    deployment - exactly the "fit to a static pattern" failure mode design
 *    doc §1 warns about. [lastPrice]/[markPrice] become a log-return against
 *    the previous frame; everything else in the frame is already a rate,
 *    ratio, or bps spread and is left as-is (after normalization).
 *
 * [Normalizer] holds the running per-feature mean/std used to standardize
 * every value before it reaches the GRU, fit during server pretraining
 * ([org.example.test.agent.pretrain.EncoderPretrainer]) and then frozen and
 * shipped alongside the encoder weights ([EncoderCheckpoint]) so live,
 * on-device inference sees features on the same scale the encoder was
 * trained on.
 */
class FeatureVectorizer(
    private val normalizer: Normalizer = Normalizer(FEATURE_NAMES.size),
) {
    /** Previous frame's [MarketFeatureFrame.lastPrice]/[MarketFeatureFrame.markPrice], for the return computation. Reset with [reset]. */
    private var prevLastPrice: Double? = null
    private var prevMarkPrice: Double? = null

    /** Width of the vector this class produces - the encoder's input dimension. */
    val inputDim: Int get() = FEATURE_NAMES.size

    fun reset() {
        prevLastPrice = null
        prevMarkPrice = null
    }

    /** Exposes the running normalizer stats for checkpointing ([EncoderCheckpoint]) - server pretraining fits these, live inference only ever reads them back via [importNormalizer]. */
    fun exportNormalizer(): Normalizer.Snapshot = normalizer.snapshot()

    fun importNormalizer(snapshot: Normalizer.Snapshot) = normalizer.loadFrom(snapshot)

    /**
     * Vectorizes one frame. Must be called in chronological order within an
     * episode/session (the log-return features depend on the previous call),
     * and [reset] between unrelated episodes so an episode boundary doesn't
     * leak a return computed across it.
     *
     * @param fit If true, folds this frame's raw values into [normalizer]'s
     *   running statistics before standardizing (server pretraining, first
     *   pass over the corpus). If false, standardizes using [normalizer]'s
     *   already-fit statistics without updating them (live/on-device
     *   inference, and validation/held-out passes during pretraining).
     */
    fun vectorize(frame: MarketFeatureFrame, fit: Boolean = false): DoubleArray {
        val raw = DoubleArray(FEATURE_NAMES.size)
        var i = 0

        fun putPair(value: Double?, transform: (Double) -> Double = { it }) {
            raw[i++] = if (value != null) transform(value) else 0.0
            raw[i++] = if (value != null) 1.0 else 0.0
        }

        val lastReturn = logReturn(prevLastPrice, frame.lastPrice)
        val markReturn = logReturn(prevMarkPrice, frame.markPrice)
        putPair(lastReturn)
        putPair(markReturn)
        putPair(frame.basisBps) { it / 100.0 }
        putPair(frame.spreadBps) { it / 100.0 }
        putPair(frame.orderBookImbalance)
        putPair(frame.openInterestChangePct15m) { it / 100.0 }
        putPair(frame.fundingRate) { it * 100.0 }
        putPair(timeToNextFundingNorm(frame.timestampMs, frame.nextFundingTimeMs))
        val flow = frame.tradeFlow
        putPair(flow?.imbalance)
        putPair(flow?.buyVolume?.let { log1p(it) })
        putPair(flow?.sellVolume?.let { log1p(it) })
        putPair(flow?.tradeCount?.toDouble()?.let { log1p(it) })
        putPair(vwapDeviationBps(flow?.vwap, frame.midPrice))
        putPair(frame.realizedVol5m)
        putPair(frame.realizedVol1h)

        // Always-present context (no mask needed).
        raw[i++] = min(1.0, frame.klineBarCount / KLINE_HISTORY_NORM)
        raw[i++] = if (frame.quality.klineStale) 1.0 else 0.0
        raw[i++] = if (frame.quality.depthStale) 1.0 else 0.0
        raw[i++] = if (frame.quality.tickerStale) 1.0 else 0.0
        raw[i++] = if (frame.quality.allFresh) 1.0 else 0.0
        check(i == FEATURE_NAMES.size) { "vectorizer wrote $i values, expected ${FEATURE_NAMES.size}" }

        prevLastPrice = frame.lastPrice ?: prevLastPrice
        prevMarkPrice = frame.markPrice ?: prevMarkPrice

        if (fit) normalizer.update(raw)
        return normalizer.standardize(raw)
    }

    private fun logReturn(prev: Double?, curr: Double?): Double? {
        if (prev == null || curr == null || prev <= 0.0 || curr <= 0.0) return null
        return ln(curr / prev)
    }

    private fun log1p(x: Double): Double = ln(1.0 + maxOf(0.0, x))

    private fun vwapDeviationBps(vwap: Double?, mid: Double?): Double? {
        if (vwap == null || mid == null || mid <= 0.0) return null
        return (vwap - mid) / mid * 10_000.0
    }

    private fun timeToNextFundingNorm(nowMs: Long, nextFundingTimeMs: Long?): Double? {
        if (nextFundingTimeMs == null) return null
        val remaining = (nextFundingTimeMs - nowMs).toDouble()
        return (remaining / FUNDING_CYCLE_MS).coerceIn(0.0, 1.0)
    }

    /**
     * Running per-feature mean/variance (Welford's online algorithm) used to
     * standardize raw feature values to roughly zero-mean/unit-variance
     * before they hit the GRU - unnormalized inputs of wildly different
     * scales (a [-1,1] imbalance next to a hundreds-of-bps funding rate)
     * make gradient descent ill-conditioned and slow to converge.
     */
    class Normalizer(private val dim: Int) {
        private var count: Long = 0
        val mean: DoubleArray = DoubleArray(dim)
        private val m2: DoubleArray = DoubleArray(dim)

        fun update(raw: DoubleArray) {
            count++
            for (j in 0 until dim) {
                val delta = raw[j] - mean[j]
                mean[j] += delta / count
                val delta2 = raw[j] - mean[j]
                m2[j] += delta * delta2
            }
        }

        fun std(j: Int): Double {
            if (count < 2) return 1.0
            val variance = m2[j] / (count - 1)
            return if (variance < MIN_VARIANCE) 1.0 else kotlin.math.sqrt(variance)
        }

        fun standardize(raw: DoubleArray): DoubleArray {
            val out = DoubleArray(dim)
            for (j in 0 until dim) {
                out[j] = if (count < 2) raw[j] else (raw[j] - mean[j]) / std(j)
            }
            return out
        }

        fun snapshot(): Snapshot = Snapshot(count, mean.copyOf(), DoubleArray(dim) { std(it) })

        fun loadFrom(snapshot: Snapshot) {
            count = snapshot.count
            snapshot.mean.copyInto(mean)
            for (j in 0 until dim) m2[j] = (snapshot.std[j] * snapshot.std[j]) * (snapshot.count - 1).coerceAtLeast(0)
        }

        data class Snapshot(val count: Long, val mean: DoubleArray, val std: DoubleArray)

        companion object {
            private const val MIN_VARIANCE = 1e-12
        }
    }

    companion object {
        private const val FUNDING_CYCLE_MS = 8L * 60L * 60L * 1000L
        private const val KLINE_HISTORY_NORM = 500.0

        /**
         * Names in emission order, value/mask pairs first (matching
         * [vectorize]'s [putPair] call order) followed by the unmasked
         * context fields. Kept as a companion list purely for
         * debuggability/logging - nothing here is looked up by name at
         * runtime.
         */
        val FEATURE_NAMES: List<String> = listOf(
            "lastPrice_logReturn", "lastPrice_mask",
            "markPrice_logReturn", "markPrice_mask",
            "basisBps", "basisBps_mask",
            "spreadBps", "spreadBps_mask",
            "orderBookImbalance", "orderBookImbalance_mask",
            "oiChangePct15m", "oiChangePct15m_mask",
            "fundingRate", "fundingRate_mask",
            "timeToNextFundingNorm", "timeToNextFundingNorm_mask",
            "tradeFlowImbalance", "tradeFlowImbalance_mask",
            "buyVolumeLog1p", "buyVolumeLog1p_mask",
            "sellVolumeLog1p", "sellVolumeLog1p_mask",
            "tradeCountLog1p", "tradeCountLog1p_mask",
            "vwapDeviationBps", "vwapDeviationBps_mask",
            "realizedVol5m", "realizedVol5m_mask",
            "realizedVol1h", "realizedVol1h_mask",
            "klineBarCountNorm",
            "klineStale",
            "depthStale",
            "tickerStale",
            "allFresh",
        )
    }
}
