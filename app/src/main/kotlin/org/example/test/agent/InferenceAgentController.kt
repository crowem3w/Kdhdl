package org.example.test.agent

import org.example.test.bitget.DepthLevel
import org.example.test.bitget.Kline
import org.example.test.bitget.PaperAccountBalance
import org.example.test.bitget.PaperPosition
import org.example.test.bitget.PaperTradingRepository
import org.example.test.bitget.PaperTradingResult
import org.example.test.bitget.PositionSide
import org.example.test.ui.QuickTradePanel
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * On- device deployment path for the trading agent. It is inference-only:
 * PPO/recurrent/distributional training, validation and promotion happen
 * offline. A runner backed by ONNX Runtime or TFLite can be injected once a
 * signed, validated model is available; the default runner is safely flat.
 */
class InferenceAgentController(
    private val paperTradingRepository: PaperTradingRepository,
    private val quickTradePanel: QuickTradePanel,
    private var runner: RecurrentPolicyRunner = SafeFlatPolicyRunner,
    private var provenance: PolicyProvenance? = null,
) {
    private var config = QuickTradePanel.AgentConfig(
        learningRate = 0.0, explorationRate = 0.0,
        rewardFunction = QuickTradePanel.RewardFunction.RISK_ADJUSTED,
        updateFrequency = QuickTradePanel.UpdateFrequency.PER_TICK,
        learningFrozen = true, maxPositionSizeUsdt = 0.0, maxLeverage = 5,
        maxDailyLossUsdt = 0.0, riskPerTradePct = 2.0, minConfidenceToTrade = 0.0,
    )
    private var running = false
    private var heldSide: PositionSide? = null
    private var dailyAnchor: Double? = null
    private var cumulativeReward = 0.0
    private var tradeCount = 0
    private var winCount = 0
    private val returns = ArrayDeque<Double>()

    init { renderProvenance() }

    fun installValidatedPolicy(policy: RecurrentPolicyRunner, metadata: PolicyProvenance) {
        runner = policy
        provenance = metadata
        renderProvenance()
        AgentLogBus.log(AgentLogLevel.INFO, "Promoted validated policy ${metadata.version}: ${metadata.validationResult}")
    }

    fun setRunning(value: Boolean) {
        running = value
        quickTradePanel.renderAgentState(if (value) QuickTradePanel.AgentState.OBSERVING else QuickTradePanel.AgentState.IDLE)
        AgentLogBus.log(AgentLogLevel.INFO, if (value) "Inference-only agent started" else "Agent stopped")
    }

    fun updateConfig(value: QuickTradePanel.AgentConfig) { config = value.copy(learningFrozen = true) }
    fun saveNow() = Unit
    fun reset() {
        cumulativeReward = 0.0; tradeCount = 0; winCount = 0; returns.clear(); dailyAnchor = null
        quickTradePanel.renderAgentPerformance(0.0, 0.0, 0.0, 0)
        AgentLogBus.clear()
    }

    fun killSwitch() { running = false; flatten(); quickTradePanel.renderAgentState(QuickTradePanel.AgentState.IDLE) }

    fun onMarketTick(candles: List<Kline>, bids: List<DepthLevel>, asks: List<DepthLevel>, balance: PaperAccountBalance?, positions: List<PaperPosition>) {
        if (!running || balance == null || candles.size < 2) return
        if (dailyAnchor == null) dailyAnchor = balance.equity
        if (config.maxDailyLossUsdt > 0 && (dailyAnchor!! - balance.equity) >= config.maxDailyLossUsdt) {
            AgentLogBus.log(AgentLogLevel.GUARDRAIL, "Daily loss limit reached; flattening")
            killSwitch(); return
        }
        val position = positions.singleOrNull()
        heldSide = position?.side
        val window = rawWindow(candles, bids, asks, position)
        val start = System.nanoTime()
        val decision = runner.infer(window)
        quickTradePanel.recordAgentInferenceLatency((System.nanoTime() - start) / 1_000_000)
        val target = decision.positionTarget.coerceIn(-1.0, 1.0)
        val allowed = decision.cvar5 >= config.minConfidenceToTrade && position?.let { it.leverage <= config.maxLeverage } != false
        AgentLogBus.log(AgentLogLevel.DECIDE, "target=${"%.2f".format(target)} CVaR(5%)=${"%.4f".format(decision.cvar5)} spread=${"%.4f".format(decision.quantileSpread)}${if (!allowed) " -> risk gate: FLAT" else ""}")
        executeTarget(if (allowed) target else 0.0, balance, position)
        val reward = riskAdjustedReward(balance, position, decision)
        cumulativeReward += reward
        publishPerformance()
        quickTradePanel.renderAgentState(if (heldSide == null) QuickTradePanel.AgentState.OBSERVING else QuickTradePanel.AgentState.TRADING)
    }

    private fun rawWindow(c: List<Kline>, b: List<DepthLevel>, a: List<DepthLevel>, p: PaperPosition?): List<MarketObservation> =
        c.takeLast(32).map { k -> MarketObservation(k.open, k.high, k.low, k.close, k.volume,
            b.take(10).map { it.price }.toDoubleArray(), b.take(10).map { it.size }.toDoubleArray(),
            a.take(10).map { it.price }.toDoubleArray(), a.take(10).map { it.size }.toDoubleArray(),
            if (p?.side == PositionSide.LONG) 1.0 else if (p?.side == PositionSide.SHORT) -1.0 else 0.0,
            p?.pnlPercentOfMargin ?: 0.0, if (p == null) 100.0 else 80.0 / p.leverage, p?.markPrice ?: k.close) }

    private fun executeTarget(target: Double, balance: PaperAccountBalance, position: PaperPosition?) {
        val desired = if (abs(target) < 0.05) null else if (target > 0) PositionSide.LONG else PositionSide.SHORT
        if (desired == heldSide) return
        flatten()
        if (desired == null) return
        val cap = if (config.maxPositionSizeUsdt > 0) config.maxPositionSizeUsdt else balance.available
        val notional = (balance.equity * (config.riskPerTradePct / 100.0) * abs(target)).coerceAtMost(cap).coerceAtMost(balance.available)
        if (notional < 1.0) return
        if (paperTradingRepository.openPositionByNotional(desired, notional.toString(), config.maxLeverage) is PaperTradingResult.Success) {
            heldSide = desired; tradeCount++
            AgentLogBus.log(AgentLogLevel.TRADE, "Target ${"%.2f".format(target)} -> $desired ${"%.2f".format(notional)} USDT")
        }
    }

    private fun flatten() {
        val side = heldSide ?: return
        paperTradingRepository.positions.value.find { it.side == side }?.let {
            if (it.unrealizedPnl > 0) winCount++
            paperTradingRepository.closePosition(it)
        }
        heldSide = null
    }

    private fun riskAdjustedReward(balance: PaperAccountBalance, position: PaperPosition?, d: PolicyDecision): Double {
        val pnl = position?.unrealizedPnl ?: 0.0
        returns.addLast(pnl); while (returns.size > 200) returns.removeFirst()
        val volatility = if (returns.size > 1) sqrt(returns.sumOf { (it - returns.average()) * (it - returns.average()) } / returns.size) else 0.0
        val liquidationPenalty = position?.let { if (80.0 / it.leverage < 5.0) 2.0 else 0.0 } ?: 0.0
        return pnl - volatility * 0.1 + d.cvar5 * 0.05 - liquidationPenalty
    }

    private fun publishPerformance() {
        val mean = returns.average(); val std = if (returns.size > 1) sqrt(returns.sumOf { (it - mean) * (it - mean) } / returns.size) else 0.0
        quickTradePanel.renderAgentPerformance(cumulativeReward, if (tradeCount == 0) 0.0 else 100.0 * winCount / tradeCount, if (std == 0.0) 0.0 else mean / std, tradeCount)
    }

    private fun renderProvenance() {
        val p = provenance
        quickTradePanel.renderAgentPolicyInfo(if (p == null) "No validated policy loaded · cold start stays flat" else "${p.version} · ${p.validationWindow} · ${p.validationResult}")
    }
}
