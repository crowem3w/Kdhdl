package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Why a [snapshot] failed - lets a caller at a decision boundary (§3.1/§3.6
 * of the design doc) distinguish "nothing to do yet" from "something is
 * actually broken," rather than collapsing every failure into a single
 * `null`.
 */
sealed class StateVectorUnavailable {
    /** No Bitget Live API key saved, or [LiveTradingRepository] hasn't connected yet. */
    data object NotConnected : StateVectorUnavailable()

    /** [LiveTradingRepository] is configured but its last poll failed (see its `lastError`). */
    data object AccountDataStale : StateVectorUnavailable()

    /**
     * [TradingChartPipeline]'s kline buffer doesn't have enough history for
     * every indicator in `f_t` to clear its warm-up yet (see
     * [TechnicalIndicators.compute]). Normal right after the app/pipeline
     * (re)starts; resolves itself once enough candles have streamed in.
     */
    data object InsufficientKlineHistory : StateVectorUnavailable()
}

/**
 * Assembles the 10-dimensional (per the design doc's stated arithmetic; see
 * [MdpStateSnapshot]'s kdoc for why the faithfully-flattened vector is
 * actually 11-wide) MDP state `S_t` for BTCUSDT, per design doc §3.1, by
 * combining:
 *
 * - `b_t`, `h_t`, `q_t`: read off [liveTradingRepository]'s already-polled
 *   [LiveTradingRepository.balance]/[LiveTradingRepository.positions]
 *   `StateFlow`s. These are cheap `.value` reads, not new network calls -
 *   the repository's own 4-second poll loop keeps them current, and
 *   [snapshot] just observes whatever it last saw.
 * - `p_t`: the open position's own `markPrice` when one exists (the exact
 *   price Bitget is computing `q_t`/PnL against), otherwise the latest
 *   kline close from [chartPipeline].
 * - `f_t`: computed fresh on every call from [chartPipeline]'s current
 *   kline buffer, via [TechnicalIndicators.compute].
 * - `F_t`: pulled from [fundingRateClient], cached for
 *   [fundingRateCacheTtlMs] so a decision boundary firing every few seconds
 *   doesn't hammer a rate that only actually changes on Bitget's 8-hour
 *   settlement cadence (design doc §3.5).
 *
 * [snapshot] is the callable meant to be invoked at each decision boundary
 * (design doc §3.6: "the policy only *acts* at a fixed decision boundary,
 * e.g. each kline close"). It never places or modifies an order and never
 * throws for ordinary "not ready yet" conditions - those come back as a
 * typed [StateVectorUnavailable] on the `Result`'s failure side instead, so
 * a caller wiring this into a decision loop can log/skip a tick without a
 * try/catch around every call.
 */
class StateVectorBuilder(
    private val liveTradingRepository: LiveTradingRepository,
    private val chartPipeline: TradingChartPipeline,
    private val fundingRateClient: BitgetFundingRateClient,
    private val symbol: String = "BTCUSDT",
    private val fundingRateCacheTtlMs: Long = 60_000L,
) {
    private companion object {
        const val TAG = "StateVectorBuilder"
    }

    private val fundingLock = Mutex()
    private var cachedFundingRate: FundingRateInfo? = null
    private var cachedFundingFetchedAtMs: Long = 0L

    /**
     * Builds one [MdpStateSnapshot] from the current live state. Safe to
     * call repeatedly/concurrently - reads of [liveTradingRepository] and
     * [chartPipeline] are just `StateFlow.value` snapshots, and the only
     * network call this makes ([fundingRateClient]) is internally cached
     * and mutex-guarded.
     */
    suspend fun snapshot(): Result<MdpStateSnapshot> {
        val connectionState = liveTradingRepository.connectionState.value
        if (connectionState == PaperTradingConnectionState.NOT_CONFIGURED) {
            return Result.failure(StateVectorUnavailableException(StateVectorUnavailable.NotConnected))
        }
        val balance = liveTradingRepository.balance.value
        if (balance == null) {
            val unavailable = if (connectionState == PaperTradingConnectionState.ERROR) {
                StateVectorUnavailable.AccountDataStale
            } else {
                StateVectorUnavailable.NotConnected
            }
            return Result.failure(StateVectorUnavailableException(unavailable))
        }

        val klines = chartPipeline.klines.value
        val indicators = TechnicalIndicators.compute(klines)
            ?: return Result.failure(StateVectorUnavailableException(StateVectorUnavailable.InsufficientKlineHistory))

        val position = liveTradingRepository.positions.value.firstOrNull { it.symbol == symbol }
        val markPrice = position?.markPrice?.takeIf { it > 0.0 } ?: klines.last().close

        val fundingRate = currentFundingRate()

        val snapshot = MdpStateSnapshot(
            timestampMs = System.currentTimeMillis(),
            symbol = symbol,
            balance = balance.available,
            positionSize = signedPositionSize(position),
            markPrice = markPrice,
            indicators = indicators,
            liquidationDistance = liquidationDistance(position, markPrice),
            fundingRate = fundingRate,
            unrealizedPnl = position?.unrealizedPnl ?: 0.0,
        )
        return Result.success(snapshot)
    }

    private fun signedPositionSize(position: PaperPosition?): Double {
        if (position == null) return 0.0
        return if (position.side == PositionSide.LONG) position.total else -position.total
    }

    /**
     * Signed distance to liquidation as a fraction of mark price - `0.0`
     * when flat (no liquidation risk to speak of), shrinking toward `0`
     * from the *positive* side as an open position approaches its
     * liquidation price regardless of side, so the sign convention doesn't
     * flip between longs and shorts.
     */
    private fun liquidationDistance(position: PaperPosition?, markPrice: Double): Double {
        if (position == null) return 0.0
        if (position.liquidationPrice <= 0.0 || markPrice <= 0.0) return 0.0
        return when (position.side) {
            PositionSide.LONG -> (markPrice - position.liquidationPrice) / markPrice
            PositionSide.SHORT -> (position.liquidationPrice - markPrice) / markPrice
        }
    }

    private suspend fun currentFundingRate(): Double = fundingLock.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedFundingRate
        if (cached != null && now - cachedFundingFetchedAtMs < fundingRateCacheTtlMs) {
            return@withLock cached.fundingRate
        }
        try {
            val info = fundingRateClient.fetchCurrentFundingRate(symbol = symbol)
            cachedFundingRate = info
            cachedFundingFetchedAtMs = now
            info.fundingRate
        } catch (e: Exception) {
            Log.w(TAG, "Funding rate refresh failed, reusing last known value: ${e.message}")
            cached?.fundingRate ?: 0.0
        }
    }
}

class StateVectorUnavailableException(val reason: StateVectorUnavailable) :
    Exception("State vector unavailable: $reason")
