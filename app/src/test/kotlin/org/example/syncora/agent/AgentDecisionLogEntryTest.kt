package org.example.syncora.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDecisionLogEntryTest {

    @Test
    fun `summarizeFeatures formats every named feature index, not just the first few`() {
        val features = FloatArray(FeatureAssembler.FEATURE_WIDTH)
        features[FeatureAssembler.RETURN_INDEX] = 0.0125f // +1.25%
        features[FeatureAssembler.REALIZED_VOL_INDEX] = 0.004f // 0.40%
        features[FeatureAssembler.SPREAD_INDEX] = 0.00018f // 1.8bps
        features[FeatureAssembler.ORDER_FLOW_IMBALANCE_INDEX] = -0.31f

        val summary = AgentDecisionLogEntry.summarizeFeatures(features)

        assertTrue("expected the formatted return, was: $summary", summary.contains("+1.25%"))
        assertTrue("expected the formatted realized vol, was: $summary", summary.contains("0.40%"))
        assertTrue("expected the formatted spread in bps, was: $summary", summary.contains("1.8bps"))
        assertTrue("expected the formatted order-flow imbalance, was: $summary", summary.contains("-0.31"))
    }

    @Test
    fun `summarizeFeatures tolerates a short or empty array rather than throwing`() {
        // Defensive: a caller should never hand this fewer than FEATURE_WIDTH
        // entries, but a formatting helper on a panel-facing type should not
        // crash the UI thread if one ever does.
        val summary = AgentDecisionLogEntry.summarizeFeatures(FloatArray(0))
        assertEquals("ret +0.00%  vol 0.00%  spread 0.0bps  imb +0.00", summary)
    }

    @Test
    fun `fromDecisionLog condenses every field Prompt 7f asks the panel to show`() {
        val features = FloatArray(FeatureAssembler.FEATURE_WIDTH)
        features[FeatureAssembler.RETURN_INDEX] = 0.001f

        val log = AgentOrchestrator.DecisionLog(
            barIndex = 42,
            startTime = 123_456_789L,
            features = features,
            reservoirState = floatArrayOf(0.1f, 0.2f),
            readoutForecast = 0.05f,
            previousPosition = 0.25f,
            position = 0.5f,
            reward = 0.0012,
            markToMarketPnl = 0.002,
            transactionCost = 0.0001,
            fundingCost = 0.0,
            differentialSharpe = 0.03,
        )

        val entry = AgentDecisionLogEntry.fromDecisionLog(log)

        assertEquals(42, entry.barIndex)
        assertEquals(123_456_789L, entry.timestampMs)
        assertEquals(0.25f, entry.previousPosition, 0f)
        assertEquals(0.5f, entry.position, 0f)
        assertEquals(0.0012, entry.reward, 0.0)
        assertEquals(0.03, entry.differentialSharpe, 0.0)
        assertEquals(AgentDecisionLogEntry.summarizeFeatures(features), entry.featuresSummary)
    }
}
