package org.example.syncora.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Every [AgentSoakHarness] run collects [AgentLiveSession.decisionLog] on
 * this dedicated, unconfined-dispatcher scope rather than [AgentSoakHarness]'s
 * own `scope` - [kotlinx.coroutines.Dispatchers.Unconfined] resumes a
 * suspended collector *synchronously*, on the calling thread, the instant
 * [kotlinx.coroutines.flow.MutableSharedFlow.tryEmit] hands it a value
 * (rather than merely scheduling that resumption for some later dispatch),
 * so by the time [AgentLiveSession.processLiveBar] returns from the call
 * that triggered a given `tryEmit`, this harness's own bookkeeping of "was
 * this bar's decision-log entry observed" is already up to date - no
 * `join()`/timeout/settle-delay needed before comparing "bars fed" against
 * "log entries observed", and no risk of a real bug (an entry that never
 * arrives) being masked by a collector that just hadn't caught up yet.
 */
private val decisionLogCollectorScope = CoroutineScope(Dispatchers.Unconfined)

/**
 * Prompt 7g: drives Prompts 7a-7f's fully-assembled live stack -
 * [AgentLiveSession] (bar-close subscription + inference + order emission +
 * checkpoint save/load + decision log) - through an extended, unattended
 * run, injecting the same kind of background/stop/restart cycles a real
 * multi-week on-device soak naturally goes through, and reporting exactly
 * the three things `ESN_RRL_Agent_Task_Prompts.md`'s Prompt 7g exit
 * criterion names: crashes, missed bar ticks, and checkpoint corruption.
 *
 * ### Why this class exists rather than just "run the app for three weeks"
 * A real soak is, by definition, not something a build step can wait for.
 * This harness is the reusable *driver* both ends of that gap share:
 * - On a real device, a debug entry point can construct one of these
 *   against the production [AgentLiveSession]/[AgentCheckpointStore]/
 *   [org.example.syncora.bitget.TradingChartPipeline] wiring and let it run
 *   for real wall-clock weeks, exactly as Prompt 7g asks for.
 * - In this repo's fast, deterministic test suite ([AgentSoakTest]), the
 *   *same* class drives a long synthetic bar sequence spanning many
 *   simulated funding cycles in milliseconds, with background/restart
 *   cycles injected on a schedule instead of waiting for the OS to
 *   actually background the app. What's being exercised - the live
 *   subscription, the inference chain, order emission, checkpoint
 *   save/load, and the decision-log stream, all wired together exactly as
 *   [AgentLiveSession] wires them - is identical in both cases; only the
 *   bar source and the restart trigger differ. This is the same
 *   "determinism first" principle the task-prompts notes call out for
 *   every other phase, applied to the one phase whose exit criterion is
 *   inherently about *duration*, not a single computation.
 *
 * ### What "crash" means here
 * [AgentLiveSession.processLiveBar] is not expected to throw - every
 * engine downstream of it degrades to logged instability
 * ([AgentOrchestrator.DecisionLog] carries no `stable` flag itself, but
 * [PolicyEngine.isStable]/[ReadoutTrainer.isStable] would have caught a
 * divergence during [AgentOrchestrator]'s own backtest path; Phase 7 is
 * where those are turned into a hard kill switch). If a bar-close *does*
 * throw here anyway, that is exactly the kind of defect an unattended soak
 * exists to surface: it is caught, recorded as a [SoakCrashEvent] (so the
 * run can keep going and surface every occurrence, not just the first),
 * and counted - a soak with `crashEvents.isNotEmpty()` fails Prompt 7g's
 * "zero crashes" bar regardless of how many other bars processed cleanly.
 *
 * ### What "missed bar tick" means here
 * Two independent counts are compared for every bar: the return value of
 * [AgentLiveSession.processLiveBar] itself (a direct call/return, nothing
 * asynchronous) and a [AgentDecisionLogEntry] collected off
 * [AgentLiveSession.decisionLog] - the same *pushed*, not polled, stream
 * [AgentStatusLogPanel] subscribes to in Prompt 7f. A missed tick is a bar
 * this harness fed in that produced no matching log-stream entry; a
 * duplicate tick is a [AgentDecisionLogEntry.barIndex] seen more than once.
 * Both are counted and both must be zero.
 *
 * ### What "checkpoint corruption" means here
 * Every simulated background/restart boundary ([restartAfter]) calls
 * [AgentLiveSession.stop] (the real save trigger), then immediately tries
 * to [AgentCheckpointStore.load] what was just written before handing off
 * to [AgentLiveSession.start] for the "restart". Any exception from either
 * call, or a `load()` that returns `null` right after a `stop()` that had
 * *already processed at least one bar* (a load failure indistinguishable
 * from "no checkpoint was ever written" - see [AgentCheckpoint]'s own
 * "missing-or-corrupt-checkpoint fallback" doc), is recorded as a
 * [SoakCheckpointCorruptionEvent]. [AgentLiveSession.start] already falls
 * back cleanly to a fresh orchestrator on a corrupt/missing checkpoint (per
 * Prompt 7e) - the run does not stop - but Prompt 7g's exit criterion is
 * that this fallback path is never actually exercised mid-soak, so this
 * count must be zero, not just non-fatal.
 *
 * @param featureAssembler Phase 1 - passed straight through to every reconstructed session, same instance reused (it is stateless per Phase 1's own contract).
 * @param reservoirWeights Phase 2's fixed weights - identical across every restart, since a restart must resume the *same* reservoir, not a freshly-randomized one.
 * @param rewardEngineFactory Builds a fresh [RewardEngine] for each (re)constructed session - [RewardEngine] carries its own EWMA moment state (Phase 4), which is deliberately *not* part of [AgentCheckpoint] (see that class's doc), so each restart starts it fresh, same as a real app process restart would.
 * @param checkpointStore Where every simulated background/stop persists to and every simulated restart resumes from - typically a [FileAgentCheckpointStore] against a temp file for a test, or the real production store for an on-device run.
 * @param orderEmitterFactory Builds a fresh [PositionOrderEmitter] for each (re)constructed session - an emitter has no persisted state of its own (it reads [PositionOrderEmitter]'s `currentPosition` supplier fresh every call), so "fresh" here just means "wired to the same underlying paper account", same as a real restart reconnecting to the same [org.example.syncora.bitget.PaperTradingRepository].
 * @param scope Where each session's async checkpoint-save [Job]s run - see [AgentLiveSession]'s own doc for why this is async even though this harness immediately `.join()`s it (matching the real lifecycle-callback shape exactly, not skipping it for convenience).
 */
class AgentSoakHarness(
    private val featureAssembler: FeatureAssembler,
    private val reservoirWeights: ReservoirWeights,
    private val rewardEngineFactory: () -> RewardEngine,
    private val checkpointStore: AgentCheckpointStore,
    private val orderEmitterFactory: () -> PositionOrderEmitter,
    private val policyNHidden: Int = reservoirWeights.nHidden,
    private val policyNBack: Int = PolicyEngine.DEFAULT_N_BACK,
    private val policyLearningRate: Float = PolicyEngine.DEFAULT_LEARNING_RATE,
    private val policyWeightClip: Float = PolicyEngine.DEFAULT_WEIGHT_CLIP,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** One bar-close this harness will feed through the live session, alongside that bar's funding-rate/fee context. */
    data class SoakBar(
        val liveBarClose: AgentOrchestrator.LiveBarClose,
        val fundingRateAt: (nowMs: Long) -> Double = { 0.0 },
        val feeRate: Double = 0.0,
    )

    /** An unhandled exception a live bar-close produced - see class doc's "What 'crash' means here". */
    data class SoakCrashEvent(val barIndex: Int, val throwable: Throwable)

    /** A background/stop/restart boundary whose save-then-reload round trip did not come back clean - see class doc's "What 'checkpoint corruption' means here". */
    data class SoakCheckpointCorruptionEvent(val afterBarIndex: Int, val reason: String, val throwable: Throwable? = null)

    /** Full soak-run outcome - Prompt 7g's exit criterion is [isClean]. */
    data class SoakReport(
        val barsFed: Int,
        val barsProcessed: Int,
        val restartCount: Int,
        val crashEvents: List<SoakCrashEvent>,
        val missedBarIndices: List<Int>,
        val duplicateBarIndices: List<Int>,
        val checkpointCorruptionEvents: List<SoakCheckpointCorruptionEvent>,
        /** Every successfully-processed bar's full audit-grade [AgentOrchestrator.DecisionLog], in order - what [AgentSoakCrossCheck] samples from afterward. */
        val decisions: List<AgentOrchestrator.DecisionLog>,
    ) {
        /** Prompt 7g's whole exit bar: zero crashes, zero missed/duplicate ticks, zero checkpoint corruption. */
        val isClean: Boolean
            get() = crashEvents.isEmpty() &&
                missedBarIndices.isEmpty() &&
                duplicateBarIndices.isEmpty() &&
                checkpointCorruptionEvents.isEmpty()
    }

    /**
     * Runs the full soak. [bars] must be in chronological order (oldest
     * first), matching every other bar-ordered contract in this package.
     *
     * @param restartAfter The set of bar indices (positions within [bars],
     *   0-based, into how many bars have been fed so far) after which this
     *   harness simulates a background/stop event followed immediately by
     *   a restart-from-checkpoint - e.g. `setOf(99, 199)` backgrounds and
     *   restarts once after the 100th bar and again after the 200th. Empty
     *   by default (a soak with no restarts exercised, still a valid - if
     *   less thorough - run).
     * @param beforeBar Called with each [SoakBar] immediately before it is
     *   handed to [AgentLiveSession.processLiveBar] - the hook a caller
     *   wanting to keep an independent side-channel ledger (e.g.
     *   [RecordingPaperLedger] in Prompt 7g's cross-check test) in
     *   lockstep with this bar's reference price uses, since order
     *   emission happens *inside* that same `processLiveBar` call, before
     *   this method gets a chance to react to its result. No-op by default.
     * @param afterBar Called after each bar is processed (or, if it threw,
     *   after that throw was caught and recorded) with the resulting
     *   [AgentOrchestrator.DecisionLog], or `null` if this bar crashed -
     *   the hook a caller settling per-bar side effects (e.g. funding
     *   accrual against a side-channel ledger) after a bar's order has
     *   already been emitted uses. No-op by default.
     */
    suspend fun run(
        bars: List<SoakBar>,
        restartAfter: Set<Int> = emptySet(),
        beforeBar: (SoakBar) -> Unit = {},
        afterBar: (SoakBar, AgentOrchestrator.DecisionLog?) -> Unit = { _, _ -> },
    ): SoakReport {
        val crashEvents = ArrayList<SoakCrashEvent>()
        val corruptionEvents = ArrayList<SoakCheckpointCorruptionEvent>()
        val decisions = ArrayList<AgentOrchestrator.DecisionLog>(bars.size)
        val seenLogBarIndices = HashSet<Int>(bars.size)
        val duplicateBarIndices = ArrayList<Int>()
        val directlyProcessedBarIndices = HashSet<Int>(bars.size)
        var restartCount = 0
        var barsFedSoFarThisSession = 0

        var session = startSession()
        var logCollectorJob: Job = decisionLogCollectorScope.launch {
            session.decisionLog.collect { entry ->
                if (!seenLogBarIndices.add(entry.barIndex)) {
                    duplicateBarIndices.add(entry.barIndex)
                }
            }
        }

        for ((index, bar) in bars.withIndex()) {
            beforeBar(bar)
            var thisDecision: AgentOrchestrator.DecisionLog? = null
            try {
                val decision = session.processLiveBar(
                    liveBarClose = bar.liveBarClose,
                    fundingRateAt = bar.fundingRateAt,
                    feeRate = bar.feeRate,
                )
                decisions.add(decision)
                directlyProcessedBarIndices.add(bar.liveBarClose.barIndex)
                barsFedSoFarThisSession++
                thisDecision = decision
            } catch (t: Throwable) {
                crashEvents.add(SoakCrashEvent(barIndex = bar.liveBarClose.barIndex, throwable = t))
            }
            afterBar(bar, thisDecision)

            if (index in restartAfter) {
                // Simulated background/stop.
                val processedBeforeStop = barsFedSoFarThisSession
                try {
                    session.stop().join()
                } catch (t: Throwable) {
                    corruptionEvents.add(
                        SoakCheckpointCorruptionEvent(
                            afterBarIndex = bar.liveBarClose.barIndex,
                            reason = "stop() threw while saving the checkpoint",
                            throwable = t,
                        ),
                    )
                }

                // Verify the save actually round-trips before trusting the
                // restart to resume from it - a stop() that silently wrote
                // nothing (or something unparsable) is exactly the
                // "checkpoint corruption" this soak exists to catch, not a
                // condition to paper over by just calling start() anyway.
                if (processedBeforeStop > 0) {
                    val reloaded = try {
                        checkpointStore.load()
                    } catch (t: Throwable) {
                        corruptionEvents.add(
                            SoakCheckpointCorruptionEvent(
                                afterBarIndex = bar.liveBarClose.barIndex,
                                reason = "checkpointStore.load() threw immediately after a successful stop()",
                                throwable = t,
                            ),
                        )
                        null
                    }
                    if (reloaded == null) {
                        corruptionEvents.add(
                            SoakCheckpointCorruptionEvent(
                                afterBarIndex = bar.liveBarClose.barIndex,
                                reason = "checkpointStore.load() returned null immediately after a stop() that had processed $processedBeforeStop bar(s) this session",
                            ),
                        )
                    }
                }

                logCollectorJob.cancel()

                // Simulated restart: a brand-new session, resolved the same
                // "restore-or-fresh" way AgentLiveSession.start always is
                // (Prompt 7e) - never assuming the checkpoint we just wrote
                // is the one that gets loaded back.
                session = startSession()
                restartCount++
                barsFedSoFarThisSession = 0
                logCollectorJob = decisionLogCollectorScope.launch {
                    session.decisionLog.collect { entry ->
                        if (!seenLogBarIndices.add(entry.barIndex)) {
                            duplicateBarIndices.add(entry.barIndex)
                        }
                    }
                }
            }
        }
        logCollectorJob.cancel()

        val missedBarIndices = bars.map { it.liveBarClose.barIndex }
            .filter { it in directlyProcessedBarIndices && it !in seenLogBarIndices }

        return SoakReport(
            barsFed = bars.size,
            barsProcessed = decisions.size,
            restartCount = restartCount,
            crashEvents = crashEvents,
            missedBarIndices = missedBarIndices,
            duplicateBarIndices = duplicateBarIndices,
            checkpointCorruptionEvents = corruptionEvents,
            decisions = decisions,
        )
    }

    private suspend fun startSession(): AgentLiveSession = AgentLiveSession.start(
        checkpointStore = checkpointStore,
        featureAssembler = featureAssembler,
        reservoirWeights = reservoirWeights,
        rewardEngine = rewardEngineFactory(),
        orderEmitter = orderEmitterFactory(),
        policyNHidden = policyNHidden,
        policyNBack = policyNBack,
        policyLearningRate = policyLearningRate,
        policyWeightClip = policyWeightClip,
        scope = scope,
    )
}
