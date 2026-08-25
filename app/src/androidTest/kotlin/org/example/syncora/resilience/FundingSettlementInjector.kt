package org.example.syncora.resilience

import org.example.syncora.agent.ExperienceLogStore

/**
 * Fires a synthetic Bitget funding-settlement event on demand rather than
 * waiting for the real 8-hour cycle.
 *
 * The design doc's original sketch routed this through a
 * `FundingSettlementHandler` shared with the live
 * [org.example.syncora.bitget.BitgetFundingRateClient] callback path - no
 * such shared handler exists in this codebase. The one real production
 * write path for a settlement's effect on the experience log is
 * [ExperienceLogStore.backfillFundingSettlement] (currently invoked by
 * [org.example.syncora.bitget.PaperTradingRepository]'s own funding job,
 * independently of [org.example.syncora.agent.DecisionLoopScheduler]), so
 * this injector calls that directly - same back-fill logic under test,
 * deterministic trigger, just without an intermediate handler object to
 * share.
 */
class FundingSettlementInjector(private val experienceLogStore: ExperienceLogStore) {

    /** @return how many experience-log rows this settlement was applied to. */
    fun triggerSettlement(fundingComponent: Double, settledAtMs: Long): Int =
        experienceLogStore.backfillFundingSettlement(settledAtMs, fundingComponent)
}
