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
 * ### This class is offline/backtest-only - by construction, not by flag
 * [runBacktest] is the *only* entry point this class exposes. There is no
 * live-mode method, no dependency anywhere in this file on
 * [org.example.syncora.bitget.LiveTradingRepository],
 * [org.example.syncora.bitget.PaperTradingRepository],
 * [org.example.syncora.ui.PaperTradePanel],
 * [org.example.syncora.bitget.LocalPaperTradingStore], or
 * [org.example.syncora.bitget.BitgetLiveCredentialsStore] - so there is no
 * code path anywhere in this class that could place a live or paper order.
 * "Zero live or paper orders" is a property of what this file does *not*
 * import, not a runtime switch that could be flipped. Live-mode wiring
 * (real-time bar-close subscription, emitting a target position into the
 * paper-trading order path) is explicitly Phase 6's job
 * (`ESN_RRL_Agent_Task_Prompts.md` Prompt 7) - extending this same class,
 * not something this phase does early.
 *
 * ### Live-mode wiring, one small piece at a time (Prompt 7a onward)
 * [LiveBarCloseSubscriber] is Prompt 7a's entire deliverable: the
 * event-driven bar-close *detector* that will eventually hand each closed
 * bar to the Phase 1-5 chain above. It deliberately stops at detection -
 * it does not call [FeatureAssembler], does not touch order emission,
 * checkpointing, or UI (Prompts 7b-7g), and still places zero live or
 * paper orders, same as [runBacktest]. It is nested here, not in its own
 * file, because it is the live-mode counterpart to the bar-close loop
 * inside [runBacktest] above - same class, same "which bar am I looking
 * at" concern, different data source (a live [Flow] instead of a
 * pre-fetched [List]).
 *
 * ### The chain, per bar
 * 1. [FeatureAssembler.assemble] -> `u_t` (Phase 1).
 * 2. [ReservoirEngine.step] -> `x_t` (Phase 2).
 * 3. [ReadoutTrainer.predict] against `x_t` -> a one-step-ahead forecast of
 *    the *next* bar's return, made with no look-ahead (Phase 3) - used
 *    both as [PolicyEngine]'s forecast input for this bar's decision and,
 *    one bar later, as the supervised target [ReadoutTrainer.update]
 *    trains against once that forecast's actual outcome is known (the same
 *    predict-then-update-next-bar ordering `ReadoutBacktestTest`
 *    establishes for Phase 3).
 * 4. [PolicyEngine.step] against `x_t` and that forecast -> `f_t`,
 *    this bar's bounded position (Phase 5).
 * 5. [RewardEngine.step] against `f_{t-1}`/`f_t` and this bar's
 *    price/cost/funding inputs -> `r_t` and `dsr_t` (Phase 4).
 * 6. [PolicyEngine.update], via [RewardEngine.RewardBreakdown.differentialSharpeGradientWrtReward]
 *    and [RewardEngine.positionGradient] - closes the loop, training the
 *    policy online against the bar it just decided.
 *
 * Every one of those six numbers, per bar, is captured in [DecisionLog] -
 * "logging every feature vector, reservoir state, position decision, and
 * reward for later audit" (Prompt 6) - so a full run is reconstructable
 * after the fact without rerunning it.
 */
class AgentOrchestrator(
    private val featureAssembler: FeatureAssembler,
    private val reservoir: ReservoirEngine,
    private val readoutTrainer: ReadoutTrainer,
    private val rewardEngine: RewardEngine,
    private val policyEngine: PolicyEngine,
) {
    /** Everything about one bar's decision, kept for audit - see class doc. */
    data class DecisionLog(
        val barIndex: Int,
        val startTime: Long,
        val features: FloatArray,
        val reservoirState: FloatArray,
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

        var previousState: FloatArray? = null
        var prevMid = Double.NaN
        var prevBarStart = 0L
        var stable = true
        var trades = 0

        for (t in klines.indices) {
            val kline = klines[t]
            val klinesSoFar = klines.subList(0, t + 1)
            val depth = depthAt(t, kline)
            val nowMs = kline.startTime

            val u = featureAssembler.assemble(klinesSoFar, depth, nowMs)
            val state = reservoir.step(u).copyOf() // copy: this bar's audit log must survive the next step() call reusing the buffer

            // Complete last bar's forecast now that this bar's actual return is known.
            val prior = previousState
            if (prior != null) {
                val actualReturn = u[FeatureAssembler.RETURN_INDEX]
                readoutTrainer.update(prior, floatArrayOf(actualReturn))
            }

            // Forecast the *next* bar's return from this bar's state - no
            // look-ahead, and what PolicyEngine decides f_t from.
            val forecast = readoutTrainer.predict(state)[0]

            val prevPosition = policyEngine.currentPosition()
            val currPosition = policyEngine.step(state, forecast)

            val bids = depth.bids
            val asks = depth.asks
            val bid = if (bids.isNotEmpty()) bids[0].price else kline.close
            val ask = if (asks.isNotEmpty()) asks[0].price else kline.close
            val currMid = if (bids.isNotEmpty() && asks.isNotEmpty()) 0.5 * (bid + ask) else kline.close
            if (prevMid.isNaN()) prevMid = currMid // first bar: no prior price yet, so Δp_0 = 0 by convention
            val barSpanMs = if (t == 0) 0L else (kline.startTime - prevBarStart).coerceAtLeast(0L)
            val fundingRate = fundingRateAt(nowMs)

            val breakdown = rewardEngine.step(
                prevMidPrice = prevMid,
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

            if (!readoutTrainer.isStable() || !policyEngine.isStable() || !breakdown.reward.isFinite()) {
                stable = false
            }

            if (kotlin.math.abs(currPosition - prevPosition) > tradeThreshold) trades++

            decisions.add(
                DecisionLog(
                    barIndex = t,
                    startTime = kline.startTime,
                    features = u,
                    reservoirState = state,
                    readoutForecast = forecast,
                    previousPosition = prevPosition,
                    position = currPosition,
                    reward = breakdown.reward,
                    markToMarketPnl = breakdown.markToMarketPnl,
                    transactionCost = breakdown.transactionCost,
                    fundingCost = breakdown.fundingCost,
                    differentialSharpe = breakdown.differentialSharpe,
                ),
            )
            returns.add(breakdown.reward)

            previousState = state
            prevMid = currMid
            prevBarStart = kline.startTime
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
