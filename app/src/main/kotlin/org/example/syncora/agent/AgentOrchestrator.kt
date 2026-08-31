package org.example.syncora.agent

import kotlinx.coroutines.flow.Flow
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.FundingSchedule
import org.example.syncora.bitget.Kline
import kotlin.math.sqrt

/**
 * Wires Phases 1-5 together, bar close by bar close, over a run of cached
 * history (see the reference architecture diagram / `docs/agent-design-
 * contract.md` and `ESN_RRL_Agent_Task_Prompts.md` Prompt 6).
 *
 * ### Zero live or paper orders - true through Prompt 7b, and still true after 7c
 * Neither [runBacktest] nor [processLiveBar] depends anywhere in this file
 * on [org.example.syncora.bitget.LiveTradingRepository],
 * [org.example.syncora.bitget.PaperTradingRepository],
 * [org.example.syncora.ui.PaperTradePanel],
 * [org.example.syncora.bitget.LocalPaperTradingStore], or
 * [org.example.syncora.bitget.BitgetLiveCredentialsStore] - so there is no
 * code path anywhere in this class that could place a live or paper order.
 * "Zero live or paper orders" is a property of what this file does *not*
 * import, not a runtime switch that could be flipped. Order emission
 * (Prompt 7c) is deliberately kept in its own class, [PositionOrderEmitter]
 * - not folded into this one - specifically so this invariant keeps
 * holding for [AgentOrchestrator] itself: a caller wires
 * `orchestrator.processLiveBar(...).position` into
 * [PositionOrderEmitter.onTargetPosition] from *outside* this class, and
 * that wiring, not anything in this file, is where an order can first be
 * placed.
 *
 * ### Live-mode wiring, one small piece at a time (Prompt 7a onward)
 * [LiveBarCloseSubscriber] is Prompt 7a's deliverable: the event-driven
 * bar-close *detector* that hands each closed bar, as a [LiveBarClose], to
 * whatever consumes it next. It is nested here, not in its own file,
 * because it is the live-mode counterpart to the bar-close loop inside
 * [runBacktest] above - same class, same "which bar am I looking at"
 * concern, different data source (a live [Flow] instead of a pre-fetched
 * [List]).
 *
 * [processLiveBar] is Prompt 7b's deliverable: it takes a
 * [LiveBarCloseSubscriber]-produced [LiveBarClose] and drives it through
 * the *same* per-bar chain [runBacktest] uses (both call the private
 * [processBar] - see that method's doc for why this makes bar-for-bar
 * equivalence a construction guarantee, not an assertion to maintain by
 * hand). It still does not touch order emission, checkpointing, or UI
 * (Prompts 7c-7g), and still places zero live or paper orders, same as
 * [runBacktest].
 *
 * ### The chain, per bar
 * 1. [FeatureAssembler.assemble] -> `u_t` (Phase 1).
 * 2. [ReservoirEngine.step] -> `x_t` (Phase 2).
 * 3. [PolicyEngine.step] against `x_t` alone -> `f_t`, this bar's bounded
 *    position (Phase 5). Per gap-closure #2, [ReadoutTrainer] has no role
 *    in this decision - see the "Readout: diagnostics only" note below.
 * 4. [RewardEngine.step] against `f_{t-1}`/`f_t` and this bar's
 *    price/cost/funding inputs -> `r_t` and `dsr_t` (Phase 4).
 * 5. [PolicyEngine.update], via [RewardEngine.RewardBreakdown.differentialSharpeGradientWrtReward]
 *    and [RewardEngine.positionGradient] - closes the loop, training the
 *    policy online against the bar it just decided.
 *
 * Every one of those numbers, per bar, is captured in [DecisionLog] -
 * "logging every feature vector, reservoir state, position decision, and
 * reward for later audit" (Prompt 6) - so a full run is reconstructable
 * after the fact without rerunning it.
 *
 * ### Readout: diagnostics only (gap-closure #2)
 * [ReadoutTrainer] trains `W_out` via RLS against next-bar return, per
 * Phase 3. The source paper (§III-B1) is explicit that no such regression
 * layer belongs in the decision path: "No such regression layer is
 * trained - the augmented state `z_t` is passed straight into the
 * reinforcement learning agent." Accordingly, [readoutTrainer]'s forecast
 * is **never** passed to [PolicyEngine.step] - [PolicyEngine] takes only
 * the reservoir state. [readoutTrainer] is retained solely as an optional,
 * clearly-labeled diagnostic: when [diagnosticsOnly] is `true` (the
 * default), this class still calls [ReadoutTrainer.predict]/[ReadoutTrainer.update]
 * every bar purely so [DecisionLog.readoutForecast] and
 * [readoutCovarianceMagnitude]/[AgentGuardrails]'s RLS-divergence check
 * keep working for offline analysis of "would a supervised proxy have
 * helped" - but nothing computed there feeds back into `f_t`. When
 * [diagnosticsOnly] is `false`, this class never calls a [ReadoutTrainer]
 * method at all (see `AgentOrchestratorBacktestTest`'s regression test),
 * [DecisionLog.readoutForecast] is reported as `Float.NaN` for that run,
 * and [readoutCovarianceMagnitude] reads whatever stale covariance
 * [readoutTrainer] was constructed with (typically its untouched initial
 * value) since it is never updated.
 */
class AgentOrchestrator(
    private val featureAssembler: FeatureAssembler,
    private val reservoir: ReservoirEngine,
    private val readoutTrainer: ReadoutTrainer,
    private val rewardEngine: RewardEngine,
    private val policyEngine: PolicyEngine,
    /**
     * Gates whether [readoutTrainer] is exercised at all - see the class
     * doc's "Readout: diagnostics only" section. `true` (the default)
     * preserves the pre-gap-closure-#2 observable behavior of
     * [DecisionLog.readoutForecast]/[readoutCovarianceMagnitude] for
     * callers relying on them, while still guaranteeing [readoutTrainer]
     * never influences [PolicyEngine]. `false` removes [ReadoutTrainer]
     * from the per-bar loop entirely.
     */
    private val diagnosticsOnly: Boolean = true,
) {
    /** Everything about one bar's decision, kept for audit - see class doc. */
    data class DecisionLog(
        val barIndex: Int,
        val startTime: Long,
        val features: FloatArray,
        val reservoirState: FloatArray,
        /**
         * [ReadoutTrainer.predict]'s forecast for this bar, purely
         * diagnostic (gap-closure #2 - see the class doc) - `Float.NaN`
         * when [diagnosticsOnly] is `false` for this orchestrator, since
         * the readout is never invoked in that mode. Never fed into
         * [PolicyEngine.step] regardless of [diagnosticsOnly].
         */
        val readoutForecast: Float,
        val previousPosition: Float,
        val position: Float,
        val reward: Double,
        val markToMarketPnl: Double,
        val transactionCost: Double,
        val fundingCost: Double,
        val differentialSharpe: Double,
    )

    /** Aggregate output of a full [runBacktest] replay. */
    data class BacktestResult(
        val decisions: List<DecisionLog>,
        /** Per-bar `r_t`, oldest first - the backtest's return series. */
        val returnSeries: List<Double>,
        val meanReturn: Double,
        val stdReturn: Double,
        /**
         * `meanReturn / stdReturn` (population std, un-annualized) - a
         * directional sanity check against the source paper's reported
         * 1.46 IR, per Prompt 6, *not* a target this run is expected to
         * hit. Annualizing this depends on the bar interval the caller fed
         * in (not something this class assumes), so callers wanting an
         * annualized figure should rescale by `sqrt(barsPerYear)`
         * themselves.
         */
        val informationRatio: Double,
        /** Count of bars where `|Δf_t| > tradeThreshold` - Prompt 6's "no runaway over-trading" check. */
        val tradeCount: Int,
        /** Largest peak-to-trough drop in cumulative reward across the run. */
        val maxDrawdown: Double,
        /** True iff every [ReadoutTrainer.isStable] / [PolicyEngine.isStable] check passed on every bar - Prompt 6's "no NaNs and no divergence" exit criterion. */
        val stable: Boolean,
    )

    /**
     * Replays [klines] (oldest first) through the full Phase 1-5 chain,
     * bar close by bar close, training [readoutTrainer] and [policyEngine]
     * online as it goes (exactly as they would be trained in a live
     * session - this is a *replay*, not a held-out evaluation). Nothing
     * about this method places an order; see the class doc.
     *
     * @param klines Full cached history to replay, oldest first, e.g. a [org.example.syncora.bitget.KlineCacheStore] dump.
     * @param depthAt Supplies the order-book snapshot for bar index `t` given that bar's [Kline] - a backtest has no live [org.example.syncora.bitget.DepthMatrix], so the caller provides this from whatever cached/synthetic depth source it has (same seam `ReadoutBacktestTest` uses for Phase 3).
     * @param fundingRateAt Supplies the funding rate applicable at a given wall-clock time - defaults to 0.0 (no funding modeled) so callers without cached funding history can still run a backtest.
     * @param feeRate Bitget's maker/taker rate applied by [RewardEngine] - see design doc §1. Defaults to 0.0.
     * @param tradeThreshold `|Δf_t|` above this counts as a "trade" for [BacktestResult.tradeCount] - a purely diagnostic threshold, not a trading rule. Defaults to a small epsilon so float noise around an unchanged position isn't counted.
     */
    fun runBacktest(
        klines: List<Kline>,
        depthAt: (barIndex: Int, kline: Kline) -> DepthSnapshot,
        fundingRateAt: (nowMs: Long) -> Double = { 0.0 },
        feeRate: Double = 0.0,
        tradeThreshold: Double = 1e-6,
    ): BacktestResult {
        require(klines.isNotEmpty()) { "klines must not be empty" }

        val decisions = ArrayList<DecisionLog>(klines.size)
        val returns = ArrayList<Double>(klines.size)

        val loopState = LiveInferenceState()
        var stable = true
        var trades = 0

        for (t in klines.indices) {
            val kline = klines[t]
            val klinesSoFar = klines.subList(0, t + 1)
            val depth = depthAt(t, kline)

            val step = processBar(
                barIndex = t,
                kline = kline,
                klinesSoFar = klinesSoFar,
                depth = depth,
                state = loopState,
                fundingRateAt = fundingRateAt,
                feeRate = feeRate,
                tradeThreshold = tradeThreshold,
            )

            if (!step.stable) stable = false
            if (step.traded) trades++

            decisions.add(step.log)
            returns.add(step.log.reward)
        }

        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / returns.size
        val std = sqrt(variance)
        val ir = if (std > 0.0) mean / std else 0.0

        var cumulative = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        for (r in returns) {
            cumulative += r
            if (cumulative > peak) peak = cumulative
            val drawdown = peak - cumulative
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }

        return BacktestResult(
            decisions = decisions,
            returnSeries = returns,
            meanReturn = mean,
            stdReturn = std,
            informationRatio = ir,
            tradeCount = trades,
            maxDrawdown = maxDrawdown,
            stable = stable,
        )
    }

    /**
     * One bar's live-mode close event - [LiveBarCloseSubscriber]'s sole
     * output, and Prompt 7a's whole deliverable. Deliberately mirrors the
     * per-bar inputs [runBacktest]'s loop builds internally (`kline`,
     * `klinesSoFar`, a depth snapshot) so that wiring this into the
     * Phase 1-5 chain in Prompt 7b is a direct substitution, not a
     * redesign.
     */
    data class LiveBarClose(
        val barIndex: Int,
        val kline: Kline,
        val klinesSoFar: List<Kline>,
        val depth: DepthSnapshot,
    )

    /**
     * The loop-carried state a bar-by-bar replay needs to thread from one
     * bar to the next - what used to be [runBacktest]'s own local `var`s
     * (`previousState`/`prevMid`/`prevBarStart`) before Prompt 7b pulled
     * the per-bar body out into [processBar] so the live inference loop
     * could reuse it verbatim instead of re-deriving it.
     *
     * One instance belongs to exactly one continuous replay session -
     * [runBacktest] creates and owns its own for the length of one
     * backtest; a live caller ([processLiveBar]) should construct exactly
     * one and keep reusing it across every live bar-close for the
     * lifetime of that trading session, the same way [runBacktest]'s old
     * local vars persisted across its whole loop.
     */
    class LiveInferenceState {
        internal var previousReservoirState: FloatArray? = null
        internal var prevMid: Double = Double.NaN
        internal var prevBarStart: Long = 0L
        internal var hasPriorBar: Boolean = false
    }

    /** [processBar]'s result: the bar's [DecisionLog] plus the two aggregate signals [runBacktest] folds across the whole replay. */
    private class BarStepResult(
        val log: DecisionLog,
        /** True iff [ReadoutTrainer.isStable], [PolicyEngine.isStable], and a finite reward all held for this bar. */
        val stable: Boolean,
        /** True iff `|Δf_t| > tradeThreshold` for this bar. */
        val traded: Boolean,
    )

    /**
     * One bar's full chain - [FeatureAssembler] -> [ReservoirEngine] ->
     * [PolicyEngine] -> [RewardEngine], with [ReadoutTrainer] running
     * alongside as a diagnostics-only signal (gap-closure #2) - exactly as
     * the class doc's step list describes, threading [state] forward
     * exactly the way [runBacktest]'s own for-loop used to inline.
     *
     * This is the *single* implementation of that chain in this class.
     * [runBacktest] and [processLiveBar] both call it and nothing else -
     * neither re-derives the chain independently - which is what makes
     * "live mode matches bar-for-bar the same computation path already
     * proven in Phase 5's offline backtest" (Prompt 7b) true by
     * construction rather than by two implementations happening to agree.
     */
    private fun processBar(
        barIndex: Int,
        kline: Kline,
        klinesSoFar: List<Kline>,
        depth: DepthSnapshot,
        state: LiveInferenceState,
        fundingRateAt: (nowMs: Long) -> Double,
        feeRate: Double,
        tradeThreshold: Double,
    ): BarStepResult {
        val nowMs = kline.startTime

        val u = featureAssembler.assemble(klinesSoFar, depth, nowMs)
        val reservoirState = reservoir.step(u).copyOf() // copy: this bar's audit log must survive the next step() call reusing the buffer

        // Readout: diagnostics only (gap-closure #2 - see the class doc).
        // Never gates or feeds PolicyEngine.step below, whether or not
        // diagnosticsOnly is enabled.
        val forecast = if (diagnosticsOnly) {
            // Complete last bar's forecast now that this bar's actual return is known.
            val prior = state.previousReservoirState
            if (prior != null) {
                val actualReturn = u[FeatureAssembler.RETURN_INDEX]
                readoutTrainer.update(prior, floatArrayOf(actualReturn))
            }

            // Forecast the *next* bar's return from this bar's state - no
            // look-ahead. Logged for offline analysis only; PolicyEngine
            // never sees it.
            readoutTrainer.predict(reservoirState)[0]
        } else {
            Float.NaN
        }

        val prevPosition = policyEngine.currentPosition()
        val currPosition = policyEngine.step(reservoirState)

        val bids = depth.bids
        val asks = depth.asks
        val bid = if (bids.isNotEmpty()) bids[0].price else kline.close
        val ask = if (asks.isNotEmpty()) asks[0].price else kline.close
        val currMid = if (bids.isNotEmpty() && asks.isNotEmpty()) 0.5 * (bid + ask) else kline.close
        if (state.prevMid.isNaN()) state.prevMid = currMid // first bar this session: no prior price yet, so Δp_0 = 0 by convention
        val barSpanMs = if (!state.hasPriorBar) 0L else (kline.startTime - state.prevBarStart).coerceAtLeast(0L)
        val fundingRate = fundingRateAt(nowMs)

        val breakdown = rewardEngine.step(
            prevMidPrice = state.prevMid,
            currMidPrice = currMid,
            prevPosition = prevPosition.toDouble(),
            currPosition = currPosition.toDouble(),
            bid = bid,
            ask = ask,
            feeRate = feeRate,
            fundingRate = fundingRate,
            barSpanMs = barSpanMs,
        )

        val dRewardDPosition = RewardEngine.positionGradient(
            prevPosition = prevPosition.toDouble(),
            currPosition = currPosition.toDouble(),
            currMidPrice = currMid,
            bid = bid,
            ask = ask,
            feeRate = feeRate,
            fundingRate = fundingRate,
            barSpanMs = barSpanMs,
        )
        policyEngine.update(breakdown.differentialSharpeGradientWrtReward, dRewardDPosition)

        val stable = (!diagnosticsOnly || readoutTrainer.isStable()) && policyEngine.isStable() && breakdown.reward.isFinite()
        val traded = kotlin.math.abs(currPosition - prevPosition) > tradeThreshold

        val log = DecisionLog(
            barIndex = barIndex,
            startTime = kline.startTime,
            features = u,
            reservoirState = reservoirState,
            readoutForecast = forecast,
            previousPosition = prevPosition,
            position = currPosition,
            reward = breakdown.reward,
            markToMarketPnl = breakdown.markToMarketPnl,
            transactionCost = breakdown.transactionCost,
            fundingCost = breakdown.fundingCost,
            differentialSharpe = breakdown.differentialSharpe,
        )

        state.previousReservoirState = reservoirState
        state.prevMid = currMid
        state.prevBarStart = kline.startTime
        state.hasPriorBar = true

        return BarStepResult(log = log, stable = stable, traded = traded)
    }

    /**
     * Prompt 7b's live inference loop. Wires one live bar-close event -
     * [LiveBarCloseSubscriber]'s output (Prompt 7a) - into the full chain
     * via [processBar], the exact same call [runBacktest] makes for every
     * offline bar: same [FeatureAssembler.assemble] -> [ReservoirEngine.step]
     * -> [PolicyEngine.step]/`update` -> [RewardEngine.step] sequence
     * (with [ReadoutTrainer.predict]/`update` alongside it purely as a
     * diagnostics-only signal when [diagnosticsOnly] is `true` - see the
     * class doc's gap-closure #2 note), producing a fresh reservoir state
     * and a new target position `f_t` for this bar.
     *
     * Order emission, checkpoint persistence, and the status UI are
     * explicitly out of scope here (Prompts 7c-7g) - this method's only
     * job is to return the [DecisionLog] a caller further up the stack
     * (Prompt 7c's [PositionOrderEmitter], reading `.position`) will act
     * on.
     *
     * @param liveBarClose One bar-close event from [LiveBarCloseSubscriber.collect]/`onSnapshot`.
     * @param state This live session's [LiveInferenceState] - construct exactly one per live trading session and pass the *same* instance to every [processLiveBar] call in that session, so `state.prevBarStart`/`prevMid`/`previousReservoirState` correctly carry forward bar to bar, mirroring [runBacktest]'s own loop-local state across an entire replay.
     * @param fundingRateAt Same contract as [runBacktest]'s parameter of the same name - defaults to 0.0 (no funding modeled).
     * @param feeRate Same contract as [runBacktest]'s parameter of the same name - defaults to 0.0.
     * @param tradeThreshold Same contract as [runBacktest]'s parameter of the same name - purely diagnostic, does not affect the chain's outputs.
     */
    fun processLiveBar(
        liveBarClose: LiveBarClose,
        state: LiveInferenceState,
        fundingRateAt: (nowMs: Long) -> Double = { 0.0 },
        feeRate: Double = 0.0,
        tradeThreshold: Double = 1e-6,
    ): DecisionLog = processBar(
        barIndex = liveBarClose.barIndex,
        kline = liveBarClose.kline,
        klinesSoFar = liveBarClose.klinesSoFar,
        depth = liveBarClose.depth,
        state = state,
        fundingRateAt = fundingRateAt,
        feeRate = feeRate,
        tradeThreshold = tradeThreshold,
    ).log

    /**
     * Captures Prompt 7d's four required checkpoint components -
     * [ReservoirEngine.currentState] (`x_t`), [ReadoutTrainer]'s `W_out`
     * and RLS covariance (via [ReadoutTrainer.toCheckpoint]), and
     * [PolicyEngine.weightsSnapshot] - as a single [AgentCheckpoint], ready
     * to hand to an [AgentCheckpointStore].
     *
     * Reads directly off [reservoir]/[readoutTrainer]/[policyEngine]'s own
     * live state rather than a caller-supplied [LiveInferenceState], so the
     * result always reflects exactly what the most recent [processBar] call
     * (via [runBacktest] or [processLiveBar]) left behind - "matching
     * in-memory state at the moment of the stop signal" (Prompt 7d) is true
     * by construction, not by the caller remembering to pass the right
     * state object. Pure, synchronous, and allocation-light (three
     * `copyOf()`s) by design: callers on an Android lifecycle callback
     * (`onStop`/`onDestroy`, neither of which can suspend) need to capture
     * this snapshot on the calling thread *before* handing it off to an
     * async store write - see [AgentLiveSession.stop].
     *
     * @param savedAtMs Wall-clock capture time, epoch millis - diagnostic only.
     */
    /**
     * True iff [readoutTrainer] and [policyEngine]'s own internal state is
     * finite right now - Phase 7's guardrail hardening (`ESN_RRL_Agent_Task_Prompts.md`
     * Prompt 8) checks this after every live bar, in addition to the
     * per-bar [BarStepResult.stable] already folded into [runBacktest]'s
     * aggregate [BacktestResult.stable], because [processLiveBar] (unlike
     * [runBacktest]) has no aggregate result to report it through - a
     * guardrail supervisor sitting outside this class needs its own way to
     * ask "is the chain still healthy" after each individual live bar.
     *
     * When [diagnosticsOnly] is `false`, [readoutTrainer] is never updated
     * by [processBar] (see the class doc's gap-closure #2 note), so its own
     * [ReadoutTrainer.isStable] trivially stays `true` on its untouched
     * initial state - this method's result then reduces to
     * [PolicyEngine.isStable], which is the only thing gap-closure #2's
     * decision path can actually make unstable.
     */
    fun isStable(): Boolean = (!diagnosticsOnly || readoutTrainer.isStable()) && policyEngine.isStable()

    /**
     * Passthrough to [ReadoutTrainer.covarianceMagnitude] - Phase 7's
     * "RLS divergence (e.g. covariance blow-up)" failure mode is detected
     * by a guardrail supervisor reading this after each live bar, the same
     * way [isStable] is. Kept as a passthrough rather than folded into
     * [isStable] itself because a magnitude *ceiling* is a policy decision
     * (how much growth is still healthy) that belongs to the guardrail
     * layer, not to this class or [ReadoutTrainer].
     *
     * When [diagnosticsOnly] is `false`, [readoutTrainer] is never updated
     * (gap-closure #2), so this reads whatever covariance it was
     * constructed with (typically its untouched `initialCovarianceScale * I`)
     * for the lifetime of this orchestrator - a divergence check against a
     * readout that isn't running is a no-op by construction, not a bug.
     */
    fun readoutCovarianceMagnitude(): Float = readoutTrainer.covarianceMagnitude()

    fun currentCheckpoint(savedAtMs: Long = System.currentTimeMillis()): AgentCheckpoint = AgentCheckpoint(
        savedAtMs = savedAtMs,
        reservoirState = reservoir.currentState().copyOf(),
        readout = readoutTrainer.toCheckpoint(),
        policyWeights = policyEngine.weightsSnapshot(),
        policyNHidden = policyEngine.nHidden,
        policyNBack = policyEngine.nBack,
    )

    /**
     * Turns [org.example.syncora.bitget.TradingChartPipeline.klines]'s
     * live stream - the *entire buffer snapshot*, re-emitted on every
     * tick, whether that tick just mutated the still-forming bar in place
     * or appended a brand-new one - into a stream of [LiveBarClose]
     * events, exactly one per bar that actually closes, in order, with no
     * duplicates and no gaps.
     *
     * ### Why "any bar before the last slot is closed" is safe
     * [org.example.syncora.bitget.KlineBuffer.upsertLocked] only ever
     * appends a new slot once a *strictly newer* `startTime` arrives, and
     * never rewrites an older slot once a newer one exists (its `else`
     * branch is dead in a well-formed live stream; `applyUpdates` also
     * always hands ticks to `upsertLocked` in-order). So every element of
     * a snapshot except the last one is final and will never be
     * overwritten again - "closed" is a structural property of position
     * in the list, not something that needs its own timer or heuristic.
     * The last element is the only one still being mutated in place and
     * is therefore never emitted until a later snapshot supersedes it.
     *
     * ### Robust to [kotlinx.coroutines.flow.StateFlow] conflation
     * `TradingChartPipeline.klines` is a `StateFlow`: a slow collector can
     * miss intermediate emissions entirely, only ever seeing the latest
     * value. That is harmless here because every snapshot is inspected in
     * full, not diffed against the previous one - a bar's *final* closed
     * state is whatever slot `snapshot[i]` holds by the time this
     * collector next runs, regardless of how many intermediate tick
     * updates to that bar were conflated away in between. What must never
     * be conflated away is which *bars* closed, and since a closed bar's
     * slot is stable once superseded, no closed bar can vanish from the
     * list - it can only scroll out once the buffer's capacity is
     * exceeded, long after this collector had its chance to see it.
     *
     * ### No double-processing across the backtest -> live handoff
     * [resumeAfterStartTime] is the `startTime` of the last bar some
     * other source (typically [runBacktest]'s
     * `BacktestResult.decisions.last().startTime`) already processed.
     * Every bar at or before it is treated as already accounted for and
     * is never re-emitted, no matter how many of them are still sitting
     * in the live buffer snapshot the moment this subscriber starts
     * collecting. Pass `null` (the default) when there was no prior
     * backtest to hand off from; the *already-closed* bars in the first
     * snapshot this subscriber sees (every slot except the still-forming
     * last one) are then treated as the pre-existing baseline (cold-start
     * buffer contents, not "new" live closes), while the bar that happens
     * to be forming at subscribe time is left open to fire normally once
     * it closes - so the very first live bar close is never dropped
     * (including when subscribing catches a bar mid-formation), and
     * nothing already-closed at subscribe time is replayed as if it were
     * new.
     *
     * Not thread-safe by design: like any [Flow] collector, [collect] is
     * meant to be driven by a single sequential coroutine (the same
     * contract `TradingChartPipeline.klines` collectors already rely on),
     * so no internal locking is needed or added.
     */
    class LiveBarCloseSubscriber(resumeAfterStartTime: Long? = null) {
        private var lastEmittedStartTime: Long = resumeAfterStartTime ?: Long.MIN_VALUE
        private var baselined: Boolean = resumeAfterStartTime != null
        private var nextBarIndex: Int = 0

        /** The `startTime` of the most recently emitted bar close, or null if none has fired yet. */
        val lastEmittedBarStartTime: Long?
            get() = if (baselined && lastEmittedStartTime != Long.MIN_VALUE) lastEmittedStartTime else null

        /**
         * Collects [klines] forever (until the flow itself completes or is
         * cancelled), invoking [onBarClose] exactly once per closed bar in
         * chronological order. [depthAt] is called synchronously at the
         * moment each close is detected to attach that bar's depth
         * snapshot - matching Prompt 7a's "subscribes to real-time
         * bar-close events from `TradingChartPipeline` and `DepthMatrix`"
         * requirement, since [org.example.syncora.bitget.DepthMatrix.snapshot]
         * is itself synchronous, not a flow.
         */
        suspend fun collect(
            klines: Flow<List<Kline>>,
            depthAt: () -> DepthSnapshot,
            onBarClose: suspend (LiveBarClose) -> Unit,
        ) {
            klines.collect { snapshot -> onSnapshot(snapshot, depthAt, onBarClose) }
        }

        /**
         * Processes a single buffer snapshot. Exposed (not private) so a
         * test harness can feed synthetic snapshots directly, one at a
         * time, without needing a real [Flow] - see
         * `LiveBarCloseSubscriberTest`.
         */
        suspend fun onSnapshot(
            snapshot: List<Kline>,
            depthAt: () -> DepthSnapshot,
            onBarClose: suspend (LiveBarClose) -> Unit,
        ) {
            if (snapshot.isEmpty()) return

            if (!baselined) {
                // First snapshot ever seen with no explicit handoff point.
                // Every slot except the last is already closed (cold-start
                // buffer contents / whatever the pipeline primed before we
                // started collecting) - pre-existing, not a newly-closed
                // live bar, so baseline off the last *closed* one (index
                // size-2). The last slot itself is still forming and must
                // stay open to fire normally once it closes - baselining
                // off it instead would silently drop the very first
                // genuinely new close whenever subscription happens to
                // start mid-bar.
                if (snapshot.size >= 2) {
                    lastEmittedStartTime = snapshot[snapshot.size - 2].startTime
                }
                baselined = true
                return
            }

            // Every slot before the last one is closed by construction (see
            // class doc); walk them in order so a snapshot that jumped
            // forward by more than one bar (StateFlow conflation) still
            // emits every intervening close, in order, not just the latest.
            for (i in 0 until snapshot.size - 1) {
                val bar = snapshot[i]
                if (bar.startTime > lastEmittedStartTime) {
                    lastEmittedStartTime = bar.startTime
                    onBarClose(
                        LiveBarClose(
                            barIndex = nextBarIndex++,
                            kline = bar,
                            klinesSoFar = snapshot.subList(0, i + 1),
                            depth = depthAt(),
                        ),
                    )
                }
            }
        }
    }
}
