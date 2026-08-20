package org.example.test.agent

import org.example.test.bitget.DepthLevel
import org.example.test.bitget.Kline
import org.example.test.bitget.PaperAccountBalance
import org.example.test.bitget.PaperPosition
import org.example.test.bitget.PaperTradingRepository
import org.example.test.bitget.PaperTradingResult
import org.example.test.bitget.PositionSide
import org.example.test.ui.QuickTradePanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Owns the actual online-learning loop behind the Agent tab: turns a market
 * tick into features ([MarketFeatureExtractor]), asks [policy] for an
 * action, executes that action against [paperTradingRepository] - paper
 * trading only, for now, exactly as asked for - and reports
 * state/performance/inference-latency back to [quickTradePanel] so the UI
 * stays truthful about what the agent is actually doing. Every decision,
 * learning step, trade, and guardrail trip is also pushed to
 * [AgentLogBus] so the transparency terminal shows exactly what the loop
 * below is doing, in the same terms it's doing it in.
 *
 * [onMarketTick] runs synchronously on whatever thread calls it (the main
 * thread, same as the rest of [org.example.test.MainActivity]'s collectors)
 * - the policy is a small MLP (8 features -> 16-unit hidden layer -> 3
 * actions) over [MarketFeatureExtractor.FEATURE_COUNT] features, so a full
 * decide+learn step is still well under a millisecond and doesn't need its
 * own dispatcher, unlike the network/disk work elsewhere in this app.
 */
class RlAgentController(
    private val paperTradingRepository: PaperTradingRepository,
    private val quickTradePanel: QuickTradePanel,
    private val policyStore: AgentPolicyStore? = null,
) {
    private companion object {
        const val MIN_LEVERAGE = 1
        // Below this, a risk-adjusted (equity% * confidence) notional is
        // small enough that it's not worth the round-trip fee/slippage
        // just to act on a barely-above-threshold decision.
        const val MIN_VIABLE_TRADE_USDT = 1.0
    }

    private val policy = MlpQLearningPolicy()

    // Weight export (fast - a handful of doubles) stays on the caller's
    // thread; only the JSON build + disk write in AgentPolicyStore.save
    // move here, off the main thread, so a long scalping session doesn't
    // accumulate main-thread I/O work tick after tick.
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        val saved = policyStore?.load()
        if (saved != null && policy.restoreFrom(saved.weights)) {
            AgentLogBus.log(
                AgentLogLevel.INFO,
                "Restored learned weights from disk (saved ${saved.savedAtMs.let { formatAge(it) }} ago)",
            )
        } else if (policyStore != null) {
            AgentLogBus.log(AgentLogLevel.INFO, "No saved policy on disk - starting from a fresh (zero) policy")
        }
    }

    // ---- bounded experience replay ----
    // A small fixed-size window of past (state, action, reward, nextState)
    // transitions. On every real learn step we also replay a handful of
    // random past transitions through the same update rule, so a single
    // mistake gets corrected in the context of what else the agent has
    // seen recently instead of being immediately overwritten by whatever
    // tick comes next. The buffer is a fixed array of a few doubles per
    // transition - bounded and tiny by construction, not the kind of
    // unbounded context that risks compounding noise over time.
    private data class Transition(
        val state: DoubleArray,
        val action: AgentAction,
        val reward: Double,
        val nextState: DoubleArray,
    )

    private val replayBuffer = ArrayDeque<Transition>()
    private val replayCapacity = 64
    private val replaySampleSize = 4
    private val replayRandom = Random.Default

    private var config = QuickTradePanel.AgentConfig(
        learningRate = 0.001,
        explorationRate = 0.1,
        rewardFunction = QuickTradePanel.RewardFunction.PNL,
        updateFrequency = QuickTradePanel.UpdateFrequency.PER_TICK,
        learningFrozen = false,
        maxPositionSizeUsdt = 0.0,
        maxLeverage = 5,
        maxDailyLossUsdt = 0.0,
        riskPerTradePct = 2.0,
        minConfidenceToTrade = 0.40,
    )

    private var isRunning = false

    // Transition bookkeeping for the one-step Q-learning update: the
    // (state, action, equity) the *previous* onMarketTick call settled on,
    // whose reward only becomes observable on the *next* call.
    private var pendingState: DoubleArray? = null
    private var pendingAction: AgentAction? = null
    private var pendingEquity: Double? = null

    // Which side the agent itself currently holds, so it only trades on a
    // *change* of decision instead of re-averaging into the same position
    // on every qualifying tick. Reset from ground truth every tick by
    // [reconcilePositionState] - never trusted as-is across a tick boundary.
    private var heldSide: PositionSide? = null

    // ---- performance bookkeeping ----
    private var cumulativeReward = 0.0
    private var tradeCount = 0
    private var winCount = 0
    private val rewardHistory = ArrayDeque<Double>()
    private val pnlWindow = ArrayDeque<Double>()
    private val maxHistorySize = 200

    // ---- daily-loss guardrail ----
    private var dailyLossAnchorEquity: Double? = null
    private var dailyAnchorDay = -1

    // ---- update-frequency bookkeeping ----
    private var tickCounter = 0
    private var lastCandleStartTime = -1L
    private val perNStepsInterval = 5

    fun setRunning(running: Boolean) {
        isRunning = running
        AgentLogBus.log(AgentLogLevel.INFO, if (running) "Agent started" else "Agent stopped")
        quickTradePanel.renderAgentState(if (running) QuickTradePanel.AgentState.OBSERVING else QuickTradePanel.AgentState.IDLE)
        if (!running) {
            pendingState = null
            pendingAction = null
            persistPolicy()
        }
    }

    fun updateConfig(newConfig: QuickTradePanel.AgentConfig) {
        config = newConfig
    }

    /** Force-persists the current weights immediately - call from the host Activity's onStop/onPause so backgrounding never loses more than the last few ticks of learning. */
    fun saveNow() = persistPolicy()

    /** Stop trading immediately and flatten anything the agent itself opened. Learned weights and performance history are left alone. */
    fun killSwitch() {
        isRunning = false
        pendingState = null
        pendingAction = null
        flattenHeldPosition()
        quickTradePanel.renderAgentState(QuickTradePanel.AgentState.IDLE)
        persistPolicy()
    }

    /** Wipes learned weights and running performance stats - a fresh episode. Does not touch any currently-open position. */
    fun reset() {
        policy.reset()
        replayBuffer.clear()
        AgentLogBus.clear()
        policyStore?.clear()
        cumulativeReward = 0.0
        tradeCount = 0
        winCount = 0
        rewardHistory.clear()
        pnlWindow.clear()
        pendingState = null
        pendingAction = null
        dailyLossAnchorEquity = null
        tickCounter = 0
        lastCandleStartTime = -1L
        quickTradePanel.renderAgentPerformance(cumulativeReward = 0.0, winRatePct = 0.0, sharpe = 0.0, tradeCount = 0)
    }

    /**
     * Feed one market update in - call this from wherever candles already
     * get collected (see [org.example.test.MainActivity]), passing the
     * latest top-of-book alongside it. No-ops entirely while stopped, and
     * respects [QuickTradePanel.AgentConfig.updateFrequency] internally so
     * callers don't need to throttle themselves.
     */
    fun onMarketTick(
        candles: List<Kline>,
        bids: List<DepthLevel>,
        asks: List<DepthLevel>,
        balance: PaperAccountBalance?,
        positions: List<PaperPosition>,
    ) {
        if (!isRunning) return
        val last = candles.lastOrNull() ?: return
        if (!shouldStepNow(last.startTime)) return
        if (balance == null) return

        checkDailyLossGuardrail(balance)
        if (!isRunning) return // the guardrail above may have just tripped the kill switch

        // Reconcile against the *actual* positions the repository reports,
        // not a remembered in-memory flag - this is what stops the agent
        // from ending up long and short simultaneously after an app
        // restart or a manual trade it wasn't aware of (heldSide used to
        // reset to null on process restart while a real position stayed
        // open on disk, so the agent would "flatten" nothing and open a
        // second, opposite-side position on top of the first).
        reconcilePositionState(positions)

        val startNanos = System.nanoTime()

        val heldPosition = positions.find { it.side == heldSide }
        val positionSideSign = when (heldSide) {
            PositionSide.LONG -> 1
            PositionSide.SHORT -> -1
            null -> 0
        }
        val state = MarketFeatureExtractor.extract(
            candles = candles,
            bids = bids,
            asks = asks,
            positionSideSign = positionSideSign,
            unrealizedPnlPercent = heldPosition?.pnlPercentOfMargin ?: 0.0,
        )

        val decision = policy.decide(state, config.explorationRate)

        // Confidence gate: below this, the raw argmax/explore action isn't
        // acted on - the agent still learns from the tick (it needs to
        // learn about the action it actually takes, so this becomes the
        // action of record for both execution *and* learning below), it
        // just doesn't risk capital on a low-conviction call. This is the
        // concrete answer to "does it have a confidence to open this
        // position" - a raw Q-argmax always picks *something*, confidence
        // is what decides whether that something is worth acting on.
        val volatility = state.getOrElse(3) { 0.0 }
        val confidenceGated = decision.action != AgentAction.FLAT && decision.confidence < config.minConfidenceToTrade
        val effectiveAction = if (confidenceGated) AgentAction.FLAT else decision.action

        // Only the forward pass above counts as "inference" - the learning
        // step below is training, timed separately by nobody because it's
        // not user-facing the way decision latency is.
        val inferenceLatencyMs = (System.nanoTime() - startNanos) / 1_000_000
        quickTradePanel.recordAgentInferenceLatency(inferenceLatencyMs)

        AgentLogBus.log(
            AgentLogLevel.DECIDE,
            "tick#$tickCounter ${if (decision.explored) "explore" else "exploit"} -> ${decision.action} " +
                "confidence=${"%.2f".format(decision.confidence)} " +
                "[${AgentLogBus.formatQValues(decision.qValues, policy.actions)}] (${inferenceLatencyMs}ms)" +
                if (confidenceGated) " -> overridden to FLAT (below min confidence ${"%.2f".format(config.minConfidenceToTrade)})" else "",
        )

        val prevState = pendingState
        val prevAction = pendingAction
        val prevEquity = pendingEquity
        if (prevState != null && prevAction != null && prevEquity != null && !config.learningFrozen) {
            val reward = computeReward(prevEquity, balance.equity)
            // Dampen the effective learning rate on high-volatility ticks
            // (feature index 3) so one violent candle can't single-handedly
            // dominate the weights the way it would at a fixed rate - the
            // agent still learns from volatile moves, just more cautiously.
            val volatilityDamping = (1.0 - 0.6 * abs(volatility)).coerceIn(0.25, 1.0)
            val effectiveLearningRate = config.learningRate * volatilityDamping

            val learnResult = policy.learn(prevState, prevAction, reward, state, effectiveLearningRate)
            cumulativeReward += reward
            rewardHistory.addLast(reward)
            while (rewardHistory.size > maxHistorySize) rewardHistory.removeFirst()

            AgentLogBus.log(
                AgentLogLevel.LEARN,
                "reward=${"%.4f".format(reward)} predictedQ=${"%.4f".format(learnResult.predictedQ)} " +
                    "target=${"%.4f".format(learnResult.target)} tdErr=${"%.4f".format(learnResult.tdErrorClipped)}" +
                    (if (learnResult.tdErrorRaw != learnResult.tdErrorClipped) " (clipped from ${"%.4f".format(learnResult.tdErrorRaw)})" else "") +
                    " lr=${"%.5f".format(effectiveLearningRate)}${if (volatilityDamping < 1.0) " (volatility-damped x${"%.2f".format(volatilityDamping)})" else ""}",
            )

            replayBuffer.addLast(Transition(prevState, prevAction, reward, state))
            while (replayBuffer.size > replayCapacity) replayBuffer.removeFirst()
            replayPastMistakes(effectiveLearningRate)

            persistPolicyThrottled()
        }

        executeAction(effectiveAction, balance, decision.confidence, volatility)

        pendingState = state
        pendingAction = effectiveAction
        pendingEquity = balance.equity

        publishPerformance()
        quickTradePanel.renderAgentState(
            when {
                heldSide != null -> QuickTradePanel.AgentState.TRADING
                config.learningFrozen -> QuickTradePanel.AgentState.OBSERVING
                else -> QuickTradePanel.AgentState.LEARNING
            },
        )
    }

    private fun shouldStepNow(candleStartTime: Long): Boolean {
        tickCounter++
        return when (config.updateFrequency) {
            QuickTradePanel.UpdateFrequency.PER_TICK -> true
            QuickTradePanel.UpdateFrequency.PER_CANDLE -> {
                val changed = candleStartTime != lastCandleStartTime
                if (changed) lastCandleStartTime = candleStartTime
                changed
            }
            QuickTradePanel.UpdateFrequency.PER_N_STEPS -> tickCounter % perNStepsInterval == 0
        }
    }

    private fun computeReward(prevEquity: Double, currentEquity: Double): Double {
        val pnl = currentEquity - prevEquity
        pnlWindow.addLast(pnl)
        while (pnlWindow.size > maxHistorySize) pnlWindow.removeFirst()

        return when (config.rewardFunction) {
            QuickTradePanel.RewardFunction.PNL -> pnl
            QuickTradePanel.RewardFunction.SHARPE -> {
                if (pnlWindow.size >= 2) {
                    val mean = pnlWindow.average()
                    val std = sqrt(pnlWindow.sumOf { (it - mean) * (it - mean) } / pnlWindow.size)
                    if (std > 1e-9) pnl / std else pnl
                } else {
                    pnl
                }
            }
            // Flat penalty proportional to the size of the swing, so the
            // agent is nudged toward steadier equity growth rather than
            // volatile PnL that nets out the same over time.
            QuickTradePanel.RewardFunction.RISK_ADJUSTED -> pnl - abs(pnl) * 0.1
        }
    }

    /**
     * Replays a small random sample of past transitions through the same
     * update rule as the live step above. This is the "learn from mistakes"
     * mechanism: a transition that produced a bad reward doesn't just get
     * one gradient step and then get buried under whatever tick comes
     * next - it keeps getting revisited (and corrected further) for as
     * long as it survives in the fixed-size [replayBuffer]. The buffer
     * itself is capped at [replayCapacity] transitions of a few doubles
     * each, so this never grows unbounded.
     */
    private fun replayPastMistakes(learningRate: Double) {
        if (replayBuffer.size < 2) return
        repeat(minOf(replaySampleSize, replayBuffer.size)) {
            val transition = replayBuffer[replayRandom.nextInt(replayBuffer.size)]
            policy.learn(transition.state, transition.action, transition.reward, transition.nextState, learningRate)
        }
    }

    private fun persistPolicy() {
        val store = policyStore ?: return
        val weights = policy.exportWeights()
        ioScope.launch { store.save(weights) }
    }

    // Saving after every single tick would be wasteful disk churn for a
    // scalping-frequency loop; every 20 learn steps is frequent enough that
    // an app kill loses at most a few seconds of learning.
    private var ticksSinceLastPersist = 0
    private fun persistPolicyThrottled() {
        ticksSinceLastPersist++
        if (ticksSinceLastPersist >= 20) {
            ticksSinceLastPersist = 0
            persistPolicy()
        }
    }

    private fun formatAge(savedAtMs: Long): String {
        if (savedAtMs <= 0L) return "unknown time"
        val minutes = (System.currentTimeMillis() - savedAtMs) / 60_000
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "${minutes}m"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }

    private fun executeAction(action: AgentAction, balance: PaperAccountBalance, confidence: Double, volatility: Double) {
        val desiredSide = when (action) {
            AgentAction.FLAT -> null
            AgentAction.LONG -> PositionSide.LONG
            AgentAction.SHORT -> PositionSide.SHORT
        }
        if (desiredSide == heldSide) return // no change in stance

        flattenHeldPosition()

        if (desiredSide != null) {
            val leverage = riskAdjustedLeverage(volatility)
            val sizeUsdt = riskAdjustedPositionSizeUsdt(balance, confidence)
            if (sizeUsdt >= MIN_VIABLE_TRADE_USDT) {
                val result = paperTradingRepository.openPositionByNotional(
                    desiredSide,
                    sizeUsdt.toString(),
                    leverage,
                )
                if (result is PaperTradingResult.Success) {
                    heldSide = desiredSide
                    tradeCount++
                    AgentLogBus.log(
                        AgentLogLevel.TRADE,
                        "Opened $desiredSide, size=${"%.2f".format(sizeUsdt)} USDT " +
                            "(${"%.2f".format(sizeUsdt / balance.equity * 100.0)}% of equity, confidence=${"%.2f".format(confidence)}), " +
                            "leverage=${leverage}x (volatility=${"%.2f".format(volatility)})",
                    )
                } else {
                    AgentLogBus.log(AgentLogLevel.ERROR, "Failed to open $desiredSide: $result")
                }
            } else {
                AgentLogBus.log(
                    AgentLogLevel.INFO,
                    "Skipped $desiredSide - risk-adjusted size (${"%.2f".format(sizeUsdt)} USDT) below minimum viable trade ($MIN_VIABLE_TRADE_USDT USDT)",
                )
            }
        } else {
            AgentLogBus.log(AgentLogLevel.TRADE, "Flattened - agent decided FLAT")
        }
    }

    /**
     * Position size as a % of *equity*, not a flat dollar figure - this is
     * the actual answer to "is a $2 position too risky": $2 is 20% of a
     * $10 account and negligible on a $10,000 one, so risk has to be sized
     * relative to equity to mean anything. [QuickTradePanel.AgentConfig.riskPerTradePct]
     * is what a *fully confident* trade risks; below full confidence the
     * size scales down linearly with [confidence], so a barely-over-threshold
     * decision commits noticeably less capital than a high-conviction one.
     */
    private fun riskAdjustedPositionSizeUsdt(balance: PaperAccountBalance, confidence: Double): Double {
        val riskFraction = (config.riskPerTradePct / 100.0).coerceIn(0.0, 0.20)
        val notional = balance.equity * riskFraction * confidence.coerceIn(0.0, 1.0)
        val ceiling = if (config.maxPositionSizeUsdt > 0.0) config.maxPositionSizeUsdt else balance.available
        return notional.coerceAtMost(ceiling).coerceAtMost(balance.available).coerceAtLeast(0.0)
    }

    /**
     * Leverage inversely scaled with the same volatility feature the policy
     * itself sees: near [config.maxLeverage] when the market is
     * consolidating (volatility near 0), stepped down toward [MIN_LEVERAGE]
     * as volatility rises toward its max. The same % price swing produces a
     * proportionally larger liquidation-risk move at high leverage, so this
     * is the leverage-side counterpart to the learning-rate volatility
     * damping already applied during learning.
     */
    private fun riskAdjustedLeverage(volatility: Double): Int {
        val maxLeverage = config.maxLeverage.coerceAtLeast(MIN_LEVERAGE)
        val calmness = 1.0 - volatility.coerceIn(0.0, 1.0) // 1.0 = calm/consolidating, ~0 = violent
        val leverage = MIN_LEVERAGE + (maxLeverage - MIN_LEVERAGE) * calmness
        return leverage.toInt().coerceIn(MIN_LEVERAGE, maxLeverage)
    }

    /**
     * Overwrites [heldSide] from the *actual* positions the repository
     * reports every tick, instead of trusting the in-memory value from the
     * last time this controller touched a position. Also the guardrail
     * against the exact bug that prompted this: if both LONG and SHORT are
     * somehow open at once (stale desynced state, or a manual trade placed
     * outside the agent), that's never a state the agent should reason or
     * learn from - flatten both and start the next decision from flat.
     */
    private fun reconcilePositionState(positions: List<PaperPosition>) {
        val sidesOpen = positions.map { it.side }.distinct()
        if (sidesOpen.size > 1) {
            AgentLogBus.log(
                AgentLogLevel.GUARDRAIL,
                "Both LONG and SHORT open simultaneously ($sidesOpen) - not a state the agent should hold or learn from, flattening both",
            )
            positions.forEach { paperTradingRepository.closePosition(it) }
            heldSide = null
        } else {
            heldSide = sidesOpen.firstOrNull()
        }
    }

    private fun flattenHeldPosition() {
        val side = heldSide ?: return
        val position = paperTradingRepository.positions.value.find { it.side == side }
        heldSide = null
        if (position == null) return
        val result = paperTradingRepository.closePosition(position)
        if (result is PaperTradingResult.Success) {
            val won = position.unrealizedPnl > 0.0
            if (won) winCount++
            AgentLogBus.log(
                AgentLogLevel.TRADE,
                "Closed $side, pnl=${"%.4f".format(position.unrealizedPnl)} USDT (${if (won) "win" else "loss"})",
            )
        }
    }

    private fun checkDailyLossGuardrail(balance: PaperAccountBalance) {
        if (config.maxDailyLossUsdt <= 0.0) return
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (dailyAnchorDay != today) {
            dailyAnchorDay = today
            dailyLossAnchorEquity = balance.equity
        }
        val anchor = dailyLossAnchorEquity ?: return
        val drawdown = anchor - balance.equity
        if (drawdown >= config.maxDailyLossUsdt) {
            AgentLogBus.log(
                AgentLogLevel.GUARDRAIL,
                "Daily loss guardrail tripped: drawdown=${"%.2f".format(drawdown)} USDT >= limit=${"%.2f".format(config.maxDailyLossUsdt)} USDT - killing switch",
            )
            killSwitch()
        }
    }

    private fun publishPerformance() {
        val winRate = if (tradeCount > 0) (winCount.toDouble() / tradeCount) * 100.0 else 0.0
        val sharpe = if (rewardHistory.size >= 2) {
            val mean = rewardHistory.average()
            val std = sqrt(rewardHistory.sumOf { (it - mean) * (it - mean) } / rewardHistory.size)
            if (std > 1e-9) (mean / std) * sqrt(rewardHistory.size.toDouble()) else 0.0
        } else {
            0.0
        }
        quickTradePanel.renderAgentPerformance(
            cumulativeReward = cumulativeReward,
            winRatePct = winRate,
            sharpe = sharpe,
            tradeCount = tradeCount,
        )
    }
}
