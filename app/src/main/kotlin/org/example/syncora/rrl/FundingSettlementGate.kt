package org.example.syncora.rrl

import org.example.syncora.bitget.FundingSchedule








internal class FundingSettlementGate {
    private var lastSettlementSeen: Long = -1L

    
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

    
    fun snapshot(): Long = lastSettlementSeen

    
    fun restore(savedLastSettlementSeen: Long) {
        lastSettlementSeen = savedLastSettlementSeen
    }
}