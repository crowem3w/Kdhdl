package org.example.syncora.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prompt 8c's kill switch (`ESN_RRL_Agent_Task_Prompts.md`, building on
 * 8a's caps and 8b's stop-independence verification - both left untouched
 * here): a single control that halts the live agent and flattens whatever
 * position it currently holds, and - per `docs/agent-design-contract.md`
 * §2's "a *third*, orchestrator-level layer, independent of both of the
 * above" - one that must keep working even when the layer most likely to
 * be unhealthy at the moment someone needs it (the UI) is not.
 *
 * ### Why this cannot be "a plain UI button callback"
 * A conventional `Button.setOnClickListener { ... }` only ever runs if
 * Android's main-thread `Looper` gets around to dispatching that click
 * message - which means a kill switch built that way is *exactly as
 * unreachable* as whatever hung, deadlocked, or is churning through a
 * long synchronous computation on the main thread at the moment it's
 * needed most. That failure mode - the one moment operator intervention
 * matters is the one moment the UI can't process it - is precisely what
 * this class exists to rule out.
 *
 * [engage] is a plain function, not a UI event handler: it can be called
 * from anywhere that can hold a reference to this object and call a
 * method on it - a background watchdog thread, a foreground service, a
 * `BroadcastReceiver` reacting to a hardware button or an OS-level
 * "emergency stop" intent, or (still supported, just no longer the *only*
 * path) a UI callback that happens to still be responsive. Nothing about
 * calling it is routed through the main thread's message queue, so a
 * stalled or blocked UI thread cannot delay, drop, or otherwise gate this
 * call the way it would a click listener.
 *
 * ### The two things [engage] does, and how each stays independent of a stalled caller thread
 * 1. **Halt.** [engagedFlag] is an [AtomicBoolean], set the moment
 *    [engage] is called, on whatever thread called it - no dispatcher hop,
 *    no queueing, no suspend point before the flag flips. [isEngaged] and
 *    [guard] read the same flag from any thread with immediate
 *    visibility (an `AtomicBoolean`'s read/write pair is a full
 *    happens-before edge), so a live loop polling [isEngaged] - or, more
 *    typically, driving every bar through [guard] - sees the halt on its
 *    very next check, regardless of what thread it runs on or what thread
 *    [engage] was called from.
 * 2. **Flatten.** The actual close-out order is dispatched onto [scope]
 *    (`Dispatchers.IO` by default - the same "own, dedicated,
 *    never-Main" scope shape [org.example.syncora.bitget.StopLossGuard]
 *    already uses for the identical reason), never onto
 *    `Dispatchers.Main` or whatever dispatcher a live bar-close collector
 *    happens to run on. If that collector's thread is the one that's
 *    stalled, the flatten still runs to completion on this class's own
 *    thread, untouched by the stall. [engage] returns the launched [Job]
 *    so a caller that can wait (a watchdog, a test) can confirm the
 *    flatten actually completed rather than merely having been requested.
 *
 * ### Halting the orchestrator, without touching it
 * Neither [AgentOrchestrator] nor [AgentLiveSession] is modified by this
 * class or aware of it - matching the same reasoning
 * [PositionOrderEmitter]'s class doc gives for keeping order emission out
 * of [AgentOrchestrator] itself: a control this safety-critical must not
 * be woven into the one class it's meant to be able to override
 * unconditionally. Instead, [guard] is the one call site a live-bar-close
 * driver (Prompt 7a's [AgentOrchestrator.LiveBarCloseSubscriber] callback,
 * or an equivalent production wiring) should route every
 * [AgentLiveSession.processLiveBar] call through: once [isEngaged] is
 * true, [guard] short-circuits to `null` and never calls into
 * [AgentLiveSession]/[AgentOrchestrator]/[PolicyEngine] again for the rest
 * of that session's life - "halts `AgentOrchestrator`" as a property of
 * what stops being called, the same structural argument
 * [AgentOrchestrator]'s own class doc already makes for "zero live or
 * paper orders" (a property of what it does *not* import, not a runtime
 * switch inside it).
 *
 * ### Idempotent and terminal by design
 * A second, third, or Nth [engage] call while already engaged is a no-op
 * beyond re-launching an (already redundant, but harmless) flatten - see
 * [engage]'s own doc. There is deliberately no public "un-halt"/resume
 * method on the production path: once tripped, this switch stays tripped
 * for the life of this instance, and a fresh trading session is expected
 * to start from a fresh [AgentKillSwitch] alongside its fresh
 * [AgentLiveSession] - "kill switch" implies a one-way trip, not a pause
 * button. [resetForTesting] exists solely so a single test class can
 * exercise multiple engage scenarios against one switch instance without
 * that being mistaken for a supported production reset path.
 *
 * @param orderEmitter The already fully-configured order path
 *   [engage]'s flatten step calls into - same instance the live session
 *   this switch guards uses, so the flatten obeys the exact same Prompt
 *   8a caps, position-tracking, and no-redundant-order behaviour every
 *   other order this emitter places does. Flattening is expressed as
 *   `onTargetPosition(0f)`, [PositionOrderEmitter]'s own "target implied
 *   by `f_t` collapses to ~0" case - it closes whatever is open and opens
 *   nothing, by construction, without this class needing to know or care
 *   which side (if any) is currently held.
 * @param scope Where the flatten order actually runs - defaults to a
 *   fresh switch-owned `Dispatchers.IO` scope; see "Flatten" above for why
 *   this must never default to `Dispatchers.Main`. Overridable so a test
 *   can inject a scope it fully controls.
 */
class AgentKillSwitch(
    private val orderEmitter: PositionOrderEmitter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private companion object {
        const val TAG = "AgentKillSwitch"
    }

    private val engagedFlag = AtomicBoolean(false)

    /**
     * `true` from the instant [engage] is first called, from any thread,
     * with immediate cross-thread visibility - see class doc's "Halt"
     * section. Never becomes `false` again outside of [resetForTesting].
     */
    val isEngaged: Boolean get() = engagedFlag.get()

    /**
     * Trips the switch: flips [isEngaged] synchronously on the calling
     * thread (no dispatcher hop before that happens - see class doc), then
     * launches the flatten order on [scope]. Safe to call from any thread,
     * any number of times, including concurrently from more than one
     * caller (e.g. a watchdog and an operator both reaching for it at
     * once) - [AtomicBoolean.getAndSet] makes exactly one of those calls
     * observe the "first" transition (and log it), while every call,
     * first or not, still launches its own flatten attempt, so a flatten
     * that happened to fail or race an in-flight order change on the
     * first call isn't the only chance this position ever gets to be
     * closed.
     *
     * @param reason Free-text, logged only - what triggered this call
     *   (e.g. `"watchdog: heartbeat stale"`, `"operator: manual stop"`).
     *   Never affects behaviour.
     * @return The [Job] running the flatten order on [scope] - callers
     *   that can wait (a watchdog confirming the position is actually
     *   flat before reporting success, or a test) may `join()` it; a
     *   fire-and-forget caller (e.g. a `BroadcastReceiver.onReceive`,
     *   which cannot suspend) is free to ignore the return value, exactly
     *   as production callers of
     *   [org.example.syncora.agent.AgentLiveSession.stop]'s own launched
     *   [Job] already do.
     */
    fun engage(reason: String = "unspecified"): Job {
        val wasAlreadyEngaged = engagedFlag.getAndSet(true)
        if (!wasAlreadyEngaged) {
            Log.w(TAG, "Kill switch engaged ($reason) - halting the live agent and flattening its position.")
        }
        return scope.launch {
            try {
                orderEmitter.onTargetPosition(0f)
            } catch (e: Exception) {
                // A failed flatten attempt must never crash the caller (a
                // watchdog thread, a service) - it already has nothing
                // better to fall back on than logging and letting whatever
                // retry policy sits above this (e.g. a watchdog re-calling
                // engage on its next tick) try again.
                Log.e(TAG, "Kill switch flatten attempt failed: ${e.message}", e)
            }
        }
    }

    /**
     * The one call site a live bar-close driver should route every bar
     * through instead of calling [session] directly - see class doc's
     * "Halting the orchestrator, without touching it". Once [isEngaged],
     * this is a pure, immediate `null` return: [session] and everything
     * beneath it ([AgentOrchestrator], [PolicyEngine], [orderEmitter]) are
     * never invoked again through this call site, regardless of what
     * thread calls [guard] or how long it has been since [engage] was
     * called on some other thread.
     *
     * @return The bar's [AgentOrchestrator.DecisionLog], exactly as
     *   [AgentLiveSession.processLiveBar] would have produced it, or
     *   `null` if this call was suppressed because the switch is engaged.
     */
    fun guard(
        session: AgentLiveSession,
        liveBarClose: AgentOrchestrator.LiveBarClose,
        fundingRateAt: (nowMs: Long) -> Double = { 0.0 },
        feeRate: Double = 0.0,
    ): AgentOrchestrator.DecisionLog? {
        if (isEngaged) return null
        return session.processLiveBar(liveBarClose, fundingRateAt, feeRate)
    }

    /**
     * Test-only escape hatch back to un-engaged - see class doc's
     * "Idempotent and terminal by design". Never called from production
     * code.
     */
    fun resetForTesting() {
        engagedFlag.set(false)
    }
}
