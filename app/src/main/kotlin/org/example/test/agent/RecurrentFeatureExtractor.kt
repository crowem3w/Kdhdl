package org.example.test.agent

import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Turns a raw [MarketObservation] window into the fixed-shape, normalized
 * `[seqLen, FEATURE_DIM]` tensor that the offline-trained recurrent (GRU)
 * policy consumes on-device via [OnnxRecurrentPolicyRunner].
 *
 * **This layout must stay byte-for-byte in sync with whatever produced the
 * bundled ONNX model.** The (now offline-repo, not part of this codebase)
 * training pipeline must build its training tensors with the exact same
 * per-timestep feature order and normalization constants below. If one
 * side changes without the other, the on-device model will silently see
 * out-of-distribution inputs.
 *
 * Every feature is squashed into roughly `[-1, 1]` (mostly via [tanh]) -
 * no running mean/variance state to maintain on-device, which keeps the
 * offline-trained weights meaningful against on-device data captured under
 * different absolute price regimes than training time.
 */
object RecurrentFeatureExtractor {

    /** Depth levels per side packed into each timestep. */
    const val DEPTH_LEVELS = 10

    /** 4 candle/volume features + 4*[DEPTH_LEVELS] depth features + 1 imbalance + 5 position/funding features. */
    const val FEATURE_DIM = 4 + 4 * DEPTH_LEVELS + 1 + 5

    /** Expected window length; shorter windows are left-padded with zero rows. */
    const val SEQ_LEN = 32

    /**
     * @return a flat row-major `FloatArray` of length `SEQ_LEN * FEATURE_DIM`,
     *   oldest observation first, matching the ONNX model's `[1, SEQ_LEN, FEATURE_DIM]` input.
     */
    fun extract(window: List<MarketObservation>): FloatArray {
        val out = FloatArray(SEQ_LEN * FEATURE_DIM)
        val trimmed = window.takeLast(SEQ_LEN)
        val padCount = SEQ_LEN - trimmed.size
        for ((i, obs) in trimmed.withIndex()) {
            val prevClose = if (i == 0) obs.open else trimmed[i - 1].close
            val row = featureRow(obs, prevClose, trimmed)
            System.arraycopy(row, 0, out, (padCount + i) * FEATURE_DIM, FEATURE_DIM)
        }
        return out
    }

    private fun featureRow(obs: MarketObservation, prevClose: Double, window: List<MarketObservation>): FloatArray {
        val row = FloatArray(FEATURE_DIM)
        var k = 0

        // --- candle / volume (4) ---
        row[k++] = tanhScaled(safeRet(obs.close, prevClose), 20.0)
        row[k++] = tanhScaled(safeRet(obs.close, obs.open), 20.0)
        row[k++] = tanhScaled(if (obs.close != 0.0) (obs.high - obs.low) / obs.close else 0.0, 20.0)
        row[k++] = tanh(volumeZScore(obs, window)).toFloat()

        // --- depth: bidPrice, bidSize, askPrice, askSize per level (4 * DEPTH_LEVELS) ---
        val mark = if (obs.markPrice != 0.0) obs.markPrice else obs.close
        val totalBidSize = obs.bidSizes.sum().coerceAtLeast(1e-9)
        val totalAskSize = obs.askSizes.sum().coerceAtLeast(1e-9)
        for (level in 0 until DEPTH_LEVELS) {
            val bidPrice = obs.bidPrices.getOrElse(level) { mark }
            val bidSize = obs.bidSizes.getOrElse(level) { 0.0 }
            val askPrice = obs.askPrices.getOrElse(level) { mark }
            val askSize = obs.askSizes.getOrElse(level) { 0.0 }
            row[k++] = tanhScaled(safeRet(bidPrice, mark), 200.0)
            row[k++] = tanh(bidSize / totalBidSize * DEPTH_LEVELS).toFloat()
            row[k++] = tanhScaled(safeRet(askPrice, mark), 200.0)
            row[k++] = tanh(askSize / totalAskSize * DEPTH_LEVELS).toFloat()
        }

        // --- book imbalance (1) ---
        val bidVol = obs.bidSizes.take(DEPTH_LEVELS).sum()
        val askVol = obs.askSizes.take(DEPTH_LEVELS).sum()
        row[k++] = if (bidVol + askVol > 0.0) ((bidVol - askVol) / (bidVol + askVol)).toFloat() else 0f

        // --- position / funding state (5) ---
        row[k++] = obs.positionTarget.toFloat()
        row[k++] = tanh(obs.unrealizedPnlPercent / 20.0).toFloat()
        row[k++] = (obs.liquidationDistancePercent / 100.0).coerceIn(0.0, 1.0).toFloat()
        row[k++] = tanh(obs.fundingRate * 1000.0).toFloat()
        row[k++] = (obs.fundingSecondsRemaining.toDouble() / FUNDING_INTERVAL_SECONDS).coerceIn(0.0, 1.0).toFloat()

        check(k == FEATURE_DIM) { "feature row length mismatch: wrote $k, expected $FEATURE_DIM" }
        return row
    }

    private fun volumeZScore(obs: MarketObservation, window: List<MarketObservation>): Double {
        val volumes = window.map { it.volume }
        if (volumes.size < 2) return 0.0
        val mean = volumes.average()
        val std = sqrt(volumes.sumOf { (it - mean) * (it - mean) } / volumes.size)
        return if (std > 1e-9) (obs.volume - mean) / std else 0.0
    }

    private fun safeRet(current: Double, base: Double): Double = if (base != 0.0) (current - base) / base else 0.0

    private fun tanhScaled(value: Double, scale: Double): Float = tanh(value * scale).toFloat()

    private const val FUNDING_INTERVAL_SECONDS = 8L * 60 * 60
}
