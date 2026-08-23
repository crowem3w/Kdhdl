package org.example.syncora.bitget

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * The six-indicator feature block that makes up `f_t` in the state-vector
 * design doc (§3.1): RSI, DX, Ultimate Oscillator, OBV, Hilbert Transform
 * Dominant Cycle, and log volume, computed from kline history.
 *
 * [obv] is z-scored against its own recent history and squashed through
 * `tanh` rather than returned as a raw cumulative sum - raw OBV is an
 * unbounded running total (its scale depends entirely on how far back the
 * kline buffer goes), which is a bad fit for a policy-network input that's
 * sitting next to bounded features like RSI. [obvRaw] carries the
 * unnormalized value for callers that want it (logging, debugging).
 */
data class TechnicalIndicatorSnapshot(
    val rsi: Double,
    val dx: Double,
    val ultimateOscillator: Double,
    val obv: Double,
    val htDominantCycle: Double,
    val logVolume: Double,
    val obvRaw: Double,
) {
    /** In the fixed order the state vector uses for `f_t`. */
    fun toDoubleArray(): DoubleArray = doubleArrayOf(rsi, dx, ultimateOscillator, obv, htDominantCycle, logVolume)

    companion object {
        const val DIMENSION = 6
    }
}

/**
 * Pure, stateless computation of the `f_t` indicator block from kline
 * history. Every function here expects [klines] in ascending time order
 * (oldest first, newest last) - the order [KlineBuffer.snapshot] and
 * [TradingChartPipeline.klines] already use - and returns `null` when there
 * isn't enough history yet to produce a stable reading, so callers (see
 * [StateVectorBuilder]) can treat "not enough warm-up data" as a distinct
 * case from "a real zero reading."
 */
object TechnicalIndicators {

    private const val RSI_PERIOD = 14
    private const val DX_PERIOD = 14
    private const val UO_SHORT = 7
    private const val UO_MID = 14
    private const val UO_LONG = 28
    private const val OBV_LOOKBACK = 50

    // TA-Lib's HT_DCPERIOD documents a lookback of 32 bars of unstable-period
    // warm-up on top of its own internal smoothing; 50 is a practical floor
    // that keeps the recursive filters below from producing garbage on the
    // first few bars while staying inside typical kline-buffer sizes.
    private const val HT_MIN_BARS = 50

    /**
     * Computes all six indicators from [klines] in one pass. Returns `null`
     * if [klines] doesn't yet have enough bars to satisfy the slowest
     * indicator's warm-up (Ultimate Oscillator's 28-period window and the
     * Hilbert Transform's ~50-bar settle time dominate). Callers at a
     * decision boundary should treat `null` as "not ready to act yet"
     * rather than substituting zeros, since a zeroed indicator vector looks
     * like real (and misleading) neutral market data to a policy network.
     */
    fun compute(klines: List<Kline>): TechnicalIndicatorSnapshot? {
        val minBars = max(HT_MIN_BARS, max(UO_LONG, DX_PERIOD) + 1)
        if (klines.size < minBars) return null

        val closes = klines.map { it.close }
        val rsiValue = rsi(closes, RSI_PERIOD) ?: return null
        val dxValue = dx(klines, DX_PERIOD) ?: return null
        val uoValue = ultimateOscillator(klines, UO_SHORT, UO_MID, UO_LONG) ?: return null
        val obvRaw = obv(klines)
        val obvNormalized = obvZScore(klines, OBV_LOOKBACK)
        val htValue = htDominantCyclePeriod(closes) ?: return null
        val logVol = logVolume(klines.last().baseVolume)

        return TechnicalIndicatorSnapshot(
            rsi = rsiValue,
            dx = dxValue,
            ultimateOscillator = uoValue,
            obv = obvNormalized,
            htDominantCycle = htValue,
            logVolume = logVol,
            obvRaw = obvRaw,
        )
    }

    /**
     * Wilder's RSI. [closes] must have at least `period + 1` entries. Uses
     * Wilder's recursive smoothing (not a simple moving average) across the
     * *entire* provided series so the reading reflects all available
     * history, not just the trailing window.
     */
    fun rsi(closes: List<Double>, period: Int = RSI_PERIOD): Double? {
        if (closes.size < period + 1) return null

        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) avgGain += change else avgLoss += -change
        }
        avgGain /= period
        avgLoss /= period

        for (i in (period + 1) until closes.size) {
            val change = closes[i] - closes[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) -change else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    /**
     * The Directional Movement Index - the single `DX` reading (not its
     * smoothed average, `ADX`), i.e. `100 * |+DI - -DI| / (+DI + -DI)`,
     * with `+DI`/`-DI` derived from Wilder-smoothed `+DM`/`-DM`/`TR` over
     * [period] bars.
     */
    fun dx(klines: List<Kline>, period: Int = DX_PERIOD): Double? {
        if (klines.size < period + 1) return null

        val plusDM = DoubleArray(klines.size)
        val minusDM = DoubleArray(klines.size)
        val tr = DoubleArray(klines.size)
        for (i in 1 until klines.size) {
            val upMove = klines[i].high - klines[i - 1].high
            val downMove = klines[i - 1].low - klines[i].low
            plusDM[i] = if (upMove > downMove && upMove > 0.0) upMove else 0.0
            minusDM[i] = if (downMove > upMove && downMove > 0.0) downMove else 0.0
            val highLow = klines[i].high - klines[i].low
            val highClose = abs(klines[i].high - klines[i - 1].close)
            val lowClose = abs(klines[i].low - klines[i - 1].close)
            tr[i] = maxOf(highLow, highClose, lowClose)
        }

        var smoothPlusDM = (1..period).sumOf { plusDM[it] }
        var smoothMinusDM = (1..period).sumOf { minusDM[it] }
        var smoothTR = (1..period).sumOf { tr[it] }
        for (i in (period + 1) until klines.size) {
            smoothPlusDM = smoothPlusDM - (smoothPlusDM / period) + plusDM[i]
            smoothMinusDM = smoothMinusDM - (smoothMinusDM / period) + minusDM[i]
            smoothTR = smoothTR - (smoothTR / period) + tr[i]
        }

        if (smoothTR == 0.0) return 0.0
        val plusDI = 100.0 * smoothPlusDM / smoothTR
        val minusDI = 100.0 * smoothMinusDM / smoothTR
        val diSum = plusDI + minusDI
        if (diSum == 0.0) return 0.0
        return 100.0 * abs(plusDI - minusDI) / diSum
    }

    /** Larry Williams' Ultimate Oscillator over [shortP]/[midP]/[longP]-bar windows (default 7/14/28). */
    fun ultimateOscillator(
        klines: List<Kline>,
        shortP: Int = UO_SHORT,
        midP: Int = UO_MID,
        longP: Int = UO_LONG,
    ): Double? {
        if (klines.size < longP + 1) return null
        val n = klines.size

        fun buyingPressure(i: Int): Double {
            val prevClose = klines[i - 1].close
            return klines[i].close - min(klines[i].low, prevClose)
        }

        fun trueRange(i: Int): Double {
            val prevClose = klines[i - 1].close
            return max(klines[i].high, prevClose) - min(klines[i].low, prevClose)
        }

        fun average(period: Int): Double {
            var bpSum = 0.0
            var trSum = 0.0
            for (i in (n - period) until n) {
                bpSum += buyingPressure(i)
                trSum += trueRange(i)
            }
            return if (trSum == 0.0) 0.0 else bpSum / trSum
        }

        val avgShort = average(shortP)
        val avgMid = average(midP)
        val avgLong = average(longP)
        return 100.0 * (4.0 * avgShort + 2.0 * avgMid + avgLong) / 7.0
    }

    /** On Balance Volume, cumulative over the entire provided [klines] series. */
    fun obv(klines: List<Kline>): Double {
        if (klines.isEmpty()) return 0.0
        var running = 0.0
        for (i in 1 until klines.size) {
            running += when {
                klines[i].close > klines[i - 1].close -> klines[i].baseVolume
                klines[i].close < klines[i - 1].close -> -klines[i].baseVolume
                else -> 0.0
            }
        }
        return running
    }

    /**
     * OBV re-expressed as a bounded, scale-free feature: compute the OBV
     * running series over the trailing [lookback] bars, z-score the latest
     * value against that window's own mean/stddev, then squash through
     * `tanh` so a single volume spike can't blow the feature past roughly
     * ±1 - the same reason [dx]/[rsi] are naturally bounded but raw OBV
     * isn't.
     */
    fun obvZScore(klines: List<Kline>, lookback: Int = OBV_LOOKBACK): Double {
        if (klines.size < 2) return 0.0
        val window = klines.takeLast(min(lookback, klines.size) + 1)
        val series = DoubleArray(window.size)
        var running = 0.0
        series[0] = 0.0
        for (i in 1 until window.size) {
            running += when {
                window[i].close > window[i - 1].close -> window[i].baseVolume
                window[i].close < window[i - 1].close -> -window[i].baseVolume
                else -> 0.0
            }
            series[i] = running
        }
        val mean = series.average()
        val variance = series.sumOf { (it - mean) * (it - mean) } / series.size
        val stdDev = kotlin.math.sqrt(variance)
        if (stdDev == 0.0) return 0.0
        val z = (series.last() - mean) / stdDev
        return tanh(z)
    }

    /** `ln(1 + volume)`, so a zero-volume bar maps to `0.0` instead of `-Infinity`. */
    fun logVolume(baseVolume: Double): Double = ln(1.0 + max(0.0, baseVolume))

    /**
     * Ehlers' Hilbert Transform Dominant Cycle Period (`HT_DCPERIOD`) - the
     * MESA-derived estimate of the dominant price cycle length, in bars.
     * This follows the standard published structure of the algorithm
     * (in-phase/quadrature components via a Hilbert-transform approximation,
     * homodyne discriminator, arctangent period estimate, clamped and
     * double-smoothed): a 6-bar-lag transform filter, one-bar-lag
     * quadrature/in-phase smoothing, exponential smoothing of the I/Q and
     * Re/Im components, a period estimate from `atan(Im/Re)`, then
     * period-change and absolute-range clamps before a final smoothing
     * pass. Requires roughly [HT_MIN_BARS] bars to settle past its
     * recursive filters' warm-up.
     */
    fun htDominantCyclePeriod(closes: List<Double>): Double? {
        val n = closes.size
        if (n < HT_MIN_BARS) return null

        val smooth = DoubleArray(n)
        val detrender = DoubleArray(n)
        val i1 = DoubleArray(n)
        val q1 = DoubleArray(n)
        val jI = DoubleArray(n)
        val jQ = DoubleArray(n)
        val i2 = DoubleArray(n)
        val q2 = DoubleArray(n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val period = DoubleArray(n)
        val smoothPeriod = DoubleArray(n)

        for (i in 0 until n) {
            smooth[i] = if (i >= 3) {
                (4.0 * closes[i] + 3.0 * closes[i - 1] + 2.0 * closes[i - 2] + closes[i - 3]) / 10.0
            } else {
                closes[i]
            }
        }

        for (i in 0 until n) {
            val prevPeriod = if (i >= 1) period[i - 1] else 15.0
            val adj = 0.075 * prevPeriod + 0.54

            detrender[i] = if (i >= 6) {
                (0.0962 * smooth[i] + 0.5769 * smooth[i - 2] - 0.5769 * smooth[i - 4] - 0.0962 * smooth[i - 6]) * adj
            } else {
                0.0
            }

            q1[i] = if (i >= 6) {
                (0.0962 * detrender[i] + 0.5769 * detrender[i - 2] - 0.5769 * detrender[i - 4] - 0.0962 * detrender[i - 6]) * adj
            } else {
                0.0
            }
            i1[i] = if (i >= 3) detrender[i - 3] else 0.0

            jI[i] = if (i >= 6) {
                (0.0962 * i1[i] + 0.5769 * i1[i - 2] - 0.5769 * i1[i - 4] - 0.0962 * i1[i - 6]) * adj
            } else {
                0.0
            }
            jQ[i] = if (i >= 6) {
                (0.0962 * q1[i] + 0.5769 * q1[i - 2] - 0.5769 * q1[i - 4] - 0.0962 * q1[i - 6]) * adj
            } else {
                0.0
            }

            val i2Raw = i1[i] - jQ[i]
            val q2Raw = q1[i] + jI[i]
            val prevI2 = if (i >= 1) i2[i - 1] else 0.0
            val prevQ2 = if (i >= 1) q2[i - 1] else 0.0
            i2[i] = 0.2 * i2Raw + 0.8 * prevI2
            q2[i] = 0.2 * q2Raw + 0.8 * prevQ2

            val reRaw = i2[i] * prevI2 + q2[i] * prevQ2
            val imRaw = i2[i] * prevQ2 - q2[i] * prevI2
            val prevRe = if (i >= 1) re[i - 1] else 0.0
            val prevIm = if (i >= 1) im[i - 1] else 0.0
            re[i] = 0.2 * reRaw + 0.8 * prevRe
            im[i] = 0.2 * imRaw + 0.8 * prevIm

            var candidatePeriod = prevPeriod
            if (re[i] != 0.0 && im[i] != 0.0) {
                val angleDegrees = Math.toDegrees(atan(im[i] / re[i]))
                if (angleDegrees != 0.0) candidatePeriod = 360.0 / angleDegrees
            }
            if (candidatePeriod > 1.5 * prevPeriod) candidatePeriod = 1.5 * prevPeriod
            if (candidatePeriod < 0.67 * prevPeriod) candidatePeriod = 0.67 * prevPeriod
            candidatePeriod = candidatePeriod.coerceIn(6.0, 50.0)
            candidatePeriod = 0.2 * candidatePeriod + 0.8 * prevPeriod
            period[i] = candidatePeriod

            val prevSmoothPeriod = if (i >= 1) smoothPeriod[i - 1] else candidatePeriod
            smoothPeriod[i] = 0.33 * candidatePeriod + 0.67 * prevSmoothPeriod
        }

        return smoothPeriod[n - 1]
    }
}
