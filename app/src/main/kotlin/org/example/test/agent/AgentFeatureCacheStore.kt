package org.example.test.agent

/**
 * Persistence for recent [MarketFeatureFrame]s, so a crash or redeploy
 * doesn't wipe the short-term context the agent had built up (design doc
 * §7.3 "State Management Across Restarts"). Mirrors
 * [org.example.test.bitget.KlineCacheStore]'s shape exactly - same
 * load-whole-thing/save-whole-thing contract, just for feature frames
 * instead of candles.
 *
 * This is *not* the long-horizon training dataset from design doc §4/§5 -
 * it's a small rolling window (see [AgentDataIngestionService]'s capacity)
 * meant only to warm-start [AgentFeatureStore]'s derived-feature windows
 * (realized vol, trade flow, OI history) on restart. A real replay buffer
 * / regime-stratified dataset store is out of scope here - see design doc
 * §9's open follow-up on replay buffer sampling.
 */
interface AgentFeatureCacheStore {
    suspend fun load(): List<MarketFeatureFrame>?
    suspend fun save(frames: List<MarketFeatureFrame>)
}

object NoopAgentFeatureCacheStore : AgentFeatureCacheStore {
    override suspend fun load(): List<MarketFeatureFrame>? = null
    override suspend fun save(frames: List<MarketFeatureFrame>) = Unit
}
