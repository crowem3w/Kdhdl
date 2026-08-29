package org.example.syncora.agent

import java.util.Locale

/**
 * Prompt 7f's compact, UI-ready shape for one bar's decision - everything
 * `ESN_RRL_Agent_Task_Prompts.md`'s Prompt 7f asks the status/log panel to
 * surface ("the bar timestamp, computed features summary, position taken,
 * and reward/`dsr_t` value") and nothing else. Deliberately *not* the same
 * type as [AgentOrchestrator.DecisionLog]: that class carries the raw
 * `FloatArray` feature vector and reservoir state needed for audit/replay
 * (Phase 6's soak-test cross-check, Prompt 7g), which is both more detail
 * than a status panel should render per row and, being a mutable-looking
 * `FloatArray`, not something that should be handed to a UI layer to hold
 * onto indefinitely. [fromDecisionLog] is the one place that boundary is
 * crossed, condensing a [AgentOrchestrator.DecisionLog] down to immutable,
 * already-formatted primitives.
 *
 * @param barIndex Same bar index as [AgentOrchestrator.DecisionLog.barIndex] - lets a panel row correlate back to the full audit log if needed.
 * @param timestampMs The bar's close time, epoch millis - [AgentOrchestrator.DecisionLog.startTime].
 * @param featuresSummary A short, already-formatted one-line digest of `u_t` (return / realized vol / spread / order-flow imbalance) - see [fromDecisionLog]. Kept as a pre-built [String] rather than the raw feature values so the panel does no numeric formatting of its own and every row is guaranteed to render the same way a log line would.
 * @param previousPosition `f_{t-1}` - kept alongside [position] so a panel can highlight a genuine change (open/flip/flatten) versus a repeated no-op decision, the same distinction [PositionOrderEmitter] itself acts on.
 * @param position `f_t ∈ [-1, 1]`, this bar's target position.
 * @param reward `r_t` for this bar.
 * @param differentialSharpe `dsr_t`, the utility signal [PolicyEngine] is trained against - see `docs/agent-design-contract.md` / Phase 4.
 */
data class AgentDecisionLogEntry(
    val barIndex: Int,
    val timestampMs: Long,
    val featuresSummary: String,
    val previousPosition: Float,
    val position: Float,
    val reward: Double,
    val differentialSharpe: Double,
) {
    companion object {
        /**
         * Builds the [featuresSummary] string from a raw `u_t` vector.
         * Pulled out as its own function (rather than inlined in
         * [fromDecisionLog]) so a test can assert on the summary format
         * directly without constructing a full [AgentOrchestrator.DecisionLog].
         *
         * Indices match [FeatureAssembler]'s own named constants exactly -
         * this function reads [FeatureAssembler.RETURN_INDEX] etc. rather
         * than hardcoding `0`, `1`, `2`, `3` so a future change to
         * [FeatureAssembler]'s layout can't silently desync the summary
         * from what it claims to describe.
         */
        fun summarizeFeatures(features: FloatArray): String {
            val ret = features.getOrElse(FeatureAssembler.RETURN_INDEX) { 0f }
            val vol = features.getOrElse(FeatureAssembler.REALIZED_VOL_INDEX) { 0f }
            val spread = features.getOrElse(FeatureAssembler.SPREAD_INDEX) { 0f }
            val imbalance = features.getOrElse(FeatureAssembler.ORDER_FLOW_IMBALANCE_INDEX) { 0f }
            return String.format(
                Locale.US,
                "ret %+.2f%%  vol %.2f%%  spread %.1fbps  imb %+.2f",
                ret * 100.0,
                vol * 100.0,
                spread * 10_000.0,
                imbalance,
            )
        }

        /** Condenses a full audit-grade [AgentOrchestrator.DecisionLog] into this panel-ready summary - see class doc. */
        fun fromDecisionLog(log: AgentOrchestrator.DecisionLog): AgentDecisionLogEntry = AgentDecisionLogEntry(
            barIndex = log.barIndex,
            timestampMs = log.startTime,
            featuresSummary = summarizeFeatures(log.features),
            previousPosition = log.previousPosition,
            position = log.position,
            reward = log.reward,
            differentialSharpe = log.differentialSharpe,
        )
    }
}
