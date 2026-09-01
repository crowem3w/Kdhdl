package org.example.syncora.agent

import kotlin.math.tanh
import kotlin.random.Random

/**
 * The Recurrent Reinforcement Learning (RRL) trading policy (see the
 * reference architecture diagram / `docs/agent-design-contract.md` and
 * Borrageiro, Firoozye & Barucca 2022, subsection III-B2). This sits on top
 * of [ReservoirEngine]'s augmented state `z_t` (Phase 2, extended by
 * gap-closure #1) and [RewardEngine]'s `dsr_t` utility signal and its
 * gradient helpers (Phase 4), and produces a single bounded position
 * `f_t ∈ [-1, 1]`.
 *
 * ### Gap-closure #1: no own feedback path - `z_t` is a single opaque input
 * Earlier revisions of this class kept its *own* ad hoc recurrence: the
 * policy's last `nBack` positions `f_{t-1}, ..., f_{t-nBack}` were appended
 * as extra regressor slots computed and threaded entirely inside this
 * class, and trained via real-time recurrent learning (RTRL) restricted to
 * that feedback path - never touching [ReservoirEngine] at all. Per the
 * source paper (§III-B1), that own-output feedback loop belongs on the
 * *reservoir's* `W_back` input channel, not the policy:
 * ```
 * x_t = tanh(W_input·u_t + W_hidden·x_{t-1} + W_back·ŷ_t)
 * ŷ_t = [f_{t-nBack}, ..., f_{t-1}]
 * z_t = concat(u_t, x_t, ŷ_t)
 * ```
 * [ReservoirEngine] now owns exactly that (`ReservoirEngine.step`'s
 * `ownOutput` argument / `ReservoirEngine.augmentedState`). This class no
 * longer keeps its own `pastPositions` history or any RTRL trace state for
 * a recurrence of its own - [step] takes the reservoir's already-assembled
 * `z_t` (width `nInput + nHidden + nBack`, matching [ReservoirWeights]'
 * shapes exactly) as a single, opaque regressor vector, with a trailing
 * bias slot appended:
 * ```
 * f_t = tanh(w · [z_t, 1])
 * ```
 * Since `z_t` carries no dependence on this class's own weights `w` (its
 * own-output component is the *reservoir's* fixed-weight feedback, not a
 * function of `w`), there is no recurrence left to differentiate through:
 * `d(f_t)/d(w_i)` is exactly `(1 - f_t²) · [z_t, 1]_i`, the ordinary
 * single-layer `tanh` gradient - no trace history, no ring buffer, no
 * indirect term. What used to be "RTRL-lite" (Gap-closure plan's Gap 3
 * still replaces the *update rule* this direct gradient feeds into with an
 * EKF; that is a separate, later change) is now just the direct term.
 *
 * ### Training: gradient ascent on `dsr_t`
 * The weights `w` are trained online via gradient ascent on the
 * differential Sharpe ratio, per Moody & Saffell's RRL scheme:
 * `dU/dw_i = d(dsr_t)/d(r_t) · d(r_t)/d(f_t) · d(f_t)/d(w_i)`. The first
 * two factors are supplied by the caller each [update] call (from
 * [RewardEngine.RewardBreakdown.differentialSharpeGradientWrtReward] and
 * [RewardEngine.positionGradient] respectively). The third factor,
 * `d(f_t)/d(w_i)`, is [lastTrace], computed directly in [step] per the
 * gap-closure #1 note above.
 *
 * ### Gap-closure #2 (unchanged by this revision): no trained readout
 * [ReadoutTrainer]'s one-step-ahead return forecast has no role in this
 * class's regressor either, same as before - see [AgentOrchestrator]'s
 * class doc for where that forecast is (and is not) wired.
 *
 * ### Performance
 * Flat `FloatArray`s throughout, no boxed `Double`, no object graphs,
 * consistent with every phase since 1. [step] and [update] together are
 * the only two allocation-free hot-path calls; [weightsSnapshot] is the
 * one exception (a fresh copy, for logging/checkpointing use only).
 */
class PolicyEngine(
    val nInput: Int,
    val nHidden: Int,
    val nBack: Int = DEFAULT_N_BACK,
    val learningRate: Float = DEFAULT_LEARNING_RATE,
    val weightClip: Float = DEFAULT_WEIGHT_CLIP,
    initialWeights: FloatArray? = null,
    seed: Long = 0L,
) {
    companion object {
        const val DEFAULT_N_BACK = 5
        const val DEFAULT_LEARNING_RATE = 0.01f
        /** `|w_i|` is clamped to this after every [update] - online gradient ascent has no forgetting-factor stabilizer the way RLS does, so an explicit bound is this class's substitute. */
        const val DEFAULT_WEIGHT_CLIP = 5f
        /** `|Δw_i|` from a single [update] call is clamped to this before being applied, same defense-in-depth spirit as [DEFAULT_WEIGHT_CLIP]. */
        const val DEFAULT_MAX_WEIGHT_DELTA = 0.5f
        /** Initial weights are drawn uniformly from `[-INIT_SCALE, INIT_SCALE]` - small, to start near `f_t ≈ 0` (flat) rather than pinned at ±1 from the first bar. */
        const val DEFAULT_INIT_SCALE = 0.05f
    }

    /**
     * Regressor width: the reservoir's augmented state `z_t`
     * (`nInput + nHidden + nBack` - see [ReservoirEngine.augmentedState])
     * plus a trailing bias slot. No slot of this class's own for a
     * feedback recurrence (gap-closure #1) or a readout forecast
     * (gap-closure #2).
     */
    val nRegressors: Int = nInput + nHidden + nBack + 1

    /** Index of the trailing bias slot, immediately after `z_t`'s own `nInput + nHidden + nBack` entries. */
    private val biasIndex: Int = nInput + nHidden + nBack

    init {
        require(nInput >= 0) { "nInput must be >= 0, was $nInput" }
        require(nHidden >= 1) { "nHidden must be >= 1, was $nHidden" }
        require(nBack >= 0) { "nBack must be >= 0, was $nBack" }
        require(learningRate > 0f) { "learningRate must be > 0, was $learningRate" }
        require(weightClip > 0f) { "weightClip must be > 0, was $weightClip" }
    }

    // ---- weights (the only thing trained) ----
    private val weights: FloatArray = if (initialWeights != null) {
        require(initialWeights.size == nRegressors) {
            "initialWeights size ${initialWeights.size} != nRegressors $nRegressors"
        }
        initialWeights.copyOf()
    } else {
        val rng = Random(seed)
        FloatArray(nRegressors) { (rng.nextFloat() * 2f - 1f) * DEFAULT_INIT_SCALE }
    }

    // Scratch buffer - allocated once, reused every step() call.
    private val regressorScratch = FloatArray(nRegressors)

    /** `d(f_t)/d(w_i)` from the most recent [step] call - what [update] applies the gradient against. */
    private val lastTrace = FloatArray(nRegressors)

    private var lastPosition: Float = 0f
    private var stepsTaken: Long = 0L

    /** The position `f_t` produced by the most recent [step] call (0 if [step] hasn't been called yet). */
    fun currentPosition(): Float = lastPosition

    /**
     * Advances the policy by one bar-close step, producing `f_t` from the
     * reservoir's augmented state `z_t` alone (plus this class's own bias
     * slot - see the class doc's gap-closure #1 note). Must be called
     * exactly once per bar, and (when training online) followed by exactly
     * one [update] call before the next [step] - [update] reads
     * [lastTrace], which this method overwrites every call.
     *
     * @param z [ReservoirEngine.augmentedState]'s `z_t = concat(u_t, x_t, ŷ_t)`, `(nInput + nHidden + nBack)`-shaped. Per gap-closure #1, this is the *only* external signal in the regressor besides this class's own bias - there is no feedback-slot or readout-forecast parameter of its own.
     */
    fun step(z: FloatArray): Float {
        require(z.size == nInput + nHidden + nBack) {
            "z size ${z.size} != nInput + nHidden + nBack ${nInput + nHidden + nBack}"
        }
        buildRegressor(z)

        var acc = 0f
        var i = 0
        while (i < nRegressors) {
            acc += weights[i] * regressorScratch[i]
            i++
        }
        val f = tanh(acc.toDouble()).toFloat()
        val dtanh = 1f - f * f

        // d(f_t)/d(w_i) = dtanh * [z_t, 1]_i - the direct term only: z_t
        // carries no dependence on this class's own weights (its
        // own-output component is the reservoir's fixed-weight W_back
        // feedback, not a function of w), so there is no recurrence left
        // to differentiate through (see the class doc's gap-closure #1
        // note).
        i = 0
        while (i < nRegressors) {
            lastTrace[i] = dtanh * regressorScratch[i]
            i++
        }

        lastPosition = f
        stepsTaken++
        return f
    }

    /**
     * One gradient-ascent update against the utility signal derived from
     * the bar this [step] call's `f_t` just priced in. See the class doc's
     * "Training" section for the chain rule this multiplies out;
     * `dUtilityDReward * dRewardDPosition` is `dU/d(f_t)`, so the applied
     * per-weight gradient is `dU/d(f_t) * d(f_t)/d(w_i)` (this class's own
     * [lastTrace]) - standard gradient *ascent* (utility is maximised, not
     * minimised, hence `+=`, not `-=`).
     *
     * @param dUtilityDReward `d(dsr_t)/d(r_t)` - [RewardEngine.RewardBreakdown.differentialSharpeGradientWrtReward] from the *same* bar's [RewardEngine.step] call.
     * @param dRewardDPosition `d(r_t)/d(f_t)` - [RewardEngine.positionGradient] computed with the *same* `prevPosition`/`currPosition` pair this bar's [step] call produced.
     */
    fun update(dUtilityDReward: Double, dRewardDPosition: Double) {
        val gradientCoefficient = (dUtilityDReward * dRewardDPosition).toFloat()
        if (!gradientCoefficient.isFinite()) return // guard: never let a bad gradient corrupt stable weights

        var i = 0
        while (i < nRegressors) {
            var delta = learningRate * gradientCoefficient * lastTrace[i]
            if (!delta.isFinite()) delta = 0f
            delta = delta.coerceIn(-DEFAULT_MAX_WEIGHT_DELTA, DEFAULT_MAX_WEIGHT_DELTA)
            val updated = (weights[i] + delta).coerceIn(-weightClip, weightClip)
            weights[i] = updated
            i++
        }
    }

    private fun buildRegressor(z: FloatArray) {
        System.arraycopy(z, 0, regressorScratch, 0, z.size)
        regressorScratch[biasIndex] = 1f
    }

    /**
     * True iff every weight and every trace entry is finite. A backtest
     * replay should assert this after every [update] - mirrors
     * [ReadoutTrainer.isStable]'s role for Phase 3, now for this class's
     * own online-learning stability requirement.
     */
    fun isStable(): Boolean {
        for (w in weights) if (!w.isFinite()) return false
        for (t in lastTrace) if (!t.isFinite()) return false
        val f = lastPosition
        if (!f.isFinite() || f < -1f || f > 1f) return false
        return true
    }

    /** Snapshot (fresh copy) of the trained weights - for logging/future checkpointing (Phase 6), not read on the hot path. */
    fun weightsSnapshot(): FloatArray = weights.copyOf()

    /**
     * Resets recurrent state (the last-computed trace/position) to a
     * fresh, no-history state, without touching the trained weights - same
     * split [ReservoirEngine.resetState] draws between state and weights.
     * Use when starting a new replay/episode with an already-trained
     * policy. Note this class no longer owns any own-output feedback
     * history itself (gap-closure #1) - that lives in [ReservoirEngine]
     * now, and [ReservoirEngine.resetState] is the call that clears it.
     */
    fun resetState() {
        lastTrace.fill(0f)
        lastPosition = 0f
    }
}
