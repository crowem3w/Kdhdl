package org.example.syncora.bitget

/**
 * One realization of the MDP state `S_t` from the on-device RL design doc
 * (§3.1): `S_t = [b_t, h_t, p_t, f_t, q_t, F_t]` for BTCUSDT.
 *
 * A note on dimensionality: the design doc states `K = 1 + 3 + 6 = 10`, but
 * enumerates six *named* slots (`b_t, h_t, p_t, f_t, q_t, F_t`) where `f_t`
 * alone is a 6-vector - flattened, that's `1+1+1+6+1+1 = 11`, not 10. Rather
 * than silently dropping one of `q_t`/`F_t` to force the count to match an
 * arithmetic slip in the doc, [toDoubleArray] flattens every named
 * component faithfully and [STATE_DIMENSION] reports the true length (11)
 * of what it actually produces, so a policy network is built against a
 * value that matches the real array size rather than a stale constant.
 *
 * - [balance]: `b_t`, unencumbered USDT collateral (from
 *   [LiveTradingRepository]/Bitget's account endpoint).
 * - [positionSize]: `h_t`, signed BTCUSDT position size - positive for a
 *   long, negative for a short, `0.0` when flat (from Bitget's position
 *   endpoint).
 * - [markPrice]: `p_t` (from [TradingChartPipeline], or the position's own
 *   mark price when one is open).
 * - [indicators]: `f_t`, the six-indicator block (from
 *   [TechnicalIndicators]).
 * - [liquidationDistance]: `q_t`, signed distance to liquidation as a
 *   fraction of mark price - positive and shrinking toward `0` as the
 *   position approaches liquidation, `0.0` when flat (from Bitget's
 *   position endpoint's `liquidationPrice`).
 * - [fundingRate]: `F_t` (from [BitgetFundingRateClient]).
 *
 * [unrealizedPnl] rides along as context the design doc also calls out for
 * `q_t` (§3.1: "distance to liquidation, unrealized PnL") but isn't part of
 * the flattened vector itself - it's exactly the kind of thing a reward
 * function (§3.5) or a risk guardrail (§5) wants read directly rather than
 * reverse-engineered out of a normalized state feature.
 */
data class MdpStateSnapshot(
    val timestampMs: Long,
    val symbol: String,
    val balance: Double,
    val positionSize: Double,
    val markPrice: Double,
    val indicators: TechnicalIndicatorSnapshot,
    val liquidationDistance: Double,
    val fundingRate: Double,
    val unrealizedPnl: Double,
) {
    /** Flattened `S_t` in the doc's declared order: `[b_t, h_t, p_t, f_t(6), q_t, F_t]`. */
    fun toDoubleArray(): DoubleArray {
        val out = DoubleArray(STATE_DIMENSION)
        out[0] = balance
        out[1] = positionSize
        out[2] = markPrice
        val f = indicators.toDoubleArray()
        System.arraycopy(f, 0, out, 3, f.size)
        out[3 + f.size] = liquidationDistance
        out[3 + f.size + 1] = fundingRate
        return out
    }

    companion object {
        /** `b_t, h_t, p_t` + `f_t` (6) + `q_t, F_t` = 11, flattened. See class kdoc for why this isn't 10. */
        const val STATE_DIMENSION = 3 + TechnicalIndicatorSnapshot.DIMENSION + 2
    }
}
