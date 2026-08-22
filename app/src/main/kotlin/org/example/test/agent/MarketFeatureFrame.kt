package org.example.test.agent

/**
 * One point-in-time snapshot of everything the agent's latent-state
 * encoder (design doc §3.1) would condition on: price/liquidity/flow/
 * derivatives context for BTCUSDTP, assembled from whichever live Bitget
 * streams [AgentDataIngestionService] has wired into [AgentFeatureStore].
 *
 * This is deliberately *not* an ML feature vector (normalized, fixed-width
 * float array) - it's the shared, human-readable feature-store record the
 * design doc's §7.4 pipeline describes ("Data ingestion service → writes
 * to a shared feature store"). Turning this into the encoder's actual
 * input tensor is the regime-detector/policy's job, once those exist.
 *
 * Every field is nullable rather than defaulted to 0.0/false on missing
 * data: a silently-zeroed feature looks like a real reading to anything
 * downstream, while a genuinely absent one should be treated as "unknown"
 * (design doc §2's "calibrated uncertainty" starts here, at ingestion -
 * an agent that can't tell missing OI from zero OI can't reason about its
 * own uncertainty correctly).
 */
data class MarketFeatureFrame(
    val timestampMs: Long,

    // -- price / basis (design doc §5.1, §5.4) --
    val lastPrice: Double?,
    val markPrice: Double?,
    val indexPrice: Double?,
    val basisBps: Double?,

    // -- order book (design doc §5.1) --
    val bestBid: Double?,
    val bestAsk: Double?,
    val midPrice: Double?,
    val spreadBps: Double?,
    val orderBookImbalance: Double?,

    // -- derivatives structure (design doc §5.1, §5.4) --
    val openInterest: Double?,
    val openInterestChangePct15m: Double?,
    val fundingRate: Double?,
    val nextFundingTimeMs: Long?,

    // -- trade flow (design doc §5.1 → §5.7 derived) --
    val tradeFlow: TradeFlowAggregator.Snapshot?,

    // -- realized volatility (design doc §5.7) --
    val realizedVol5m: Double?,
    val realizedVol1h: Double?,

    // -- context / provenance --
    val klineBarCount: Int,
    val quality: DataQuality,
) {
    /**
     * Per-source freshness, in milliseconds since that source last updated
     * (null = never received anything yet), plus a blunt [allFresh]
     * summary. [AgentDataIngestionService] feeds this every emission cycle
     * rather than [MarketFeatureFrame] computing it lazily, since "fresh
     * relative to what instant" only makes sense at emission time.
     */
    data class DataQuality(
        val klineAgeMs: Long?,
        val depthAgeMs: Long?,
        val tickerAgeMs: Long?,
        val staleThresholdMs: Long,
    ) {
        val klineStale: Boolean get() = isStale(klineAgeMs)
        val depthStale: Boolean get() = isStale(depthAgeMs)
        val tickerStale: Boolean get() = isStale(tickerAgeMs)

        /** True only when every source has reported at least once and none are stale. */
        val allFresh: Boolean
            get() = klineAgeMs != null && depthAgeMs != null && tickerAgeMs != null &&
                !klineStale && !depthStale && !tickerStale

        private fun isStale(ageMs: Long?): Boolean = ageMs == null || ageMs > staleThresholdMs
    }
}
