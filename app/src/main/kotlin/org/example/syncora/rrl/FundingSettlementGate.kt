package org.example.syncora.rrl

import org.example.syncora.bitget.FundingSchedule

/**
 * The perpetual swap's funding profit or loss (eq. 4) is only realised at
 * the 8-hourly settlement boundary, not on every sampled bar. This tracks
 * the last settlement seen so [RrlAgentLayer] can pass kappa_t = 0 on bars
 * that do not cross a boundary, matching the sparse "carry" column of
 * Table 1 in the paper.
 */
internal class FundingSettlementGate {
    private var lastSettlementSeen: Long = -1L

    /** Returns true exactly once per settlement boundary crossed between calls. */
    fun didSettle(previousTimestampMs: Long, currentTimestampMs: Long): Boolean {
        if (previousTimestampMs <= 0L) {
            lastSettlementSeen = FundingSchedule.previousSettlement(currentTimestampMs)
            return false
        }
        val settlement = FundingSchedule.previousSettlement(currentTimestampMs)
        if (settlement > lastSettlementSeen && settlement in (previousTimestampMs + 1)..currentTimestampMs) {
            lastSettlementSeen = settlement
            return true
        }
        return false
    }

    fun reset() {
        lastSettlementSeen = -1L
    }
}
