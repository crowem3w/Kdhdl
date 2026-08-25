package org.example.syncora.resilience

/**
 * Test-side deterministic timestamp source - "multi-day" simulated in a
 * test run that has to finish in minutes. Not wired into production code
 * (see package-info.kt gap #2: [org.example.syncora.agent.DecisionLoopScheduler]
 * has no injectable clock to substitute this into); instead this generates
 * the strictly-increasing timestamps [PipelineResilienceTest] stamps onto
 * synthetic klines fed through
 * [org.example.syncora.bitget.TradingChartPipeline.injectTestKline] and
 * onto synthetic funding settlements fed through [FundingSettlementInjector].
 */
class SimulatedClock(private var currentMillis: Long) {
    fun nowMillis(): Long = currentMillis

    fun advanceBy(millis: Long): Long {
        currentMillis += millis
        return currentMillis
    }
}
