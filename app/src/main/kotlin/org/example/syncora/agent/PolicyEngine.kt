package org.example.syncora.agent

import kotlin.math.tanh
import kotlin.random.Random

/**
 * The Recurrent Reinforcement Learning (RRL) trading policy (see the
 * reference architecture diagram / `docs/agent-design-contract.md` and
 * Borrageiro, Firoozye & Barucca 2022, subsection III-B2). This is Phase 5:
 * it sits on top of two fixed building blocks - [ReservoirEngine]'s hidden
 * state `x_t` (Phase 2) and [RewardEngine]'s `dsr_t` utility signal and its
 * gradient helpers (Phase 4) - and produces a single bounded position
 * `f_t ∈ [-1, 1]`.
 *
 * ### Gap-closure #2: no trained readout in the regressor
 * Earlier revisions of this class also took [ReadoutTrainer]'s one-step-
 * ahead return forecast `ŷ_t` as a regressor input. Per the source paper
 * (§III-B1): "No such regression layer is trained - the augmented state
 * `z_t` is passed straight into the reinforcement learning agent." This
 * class no longer has any parameter, slot, or trace term for a readout
 * forecast - [ReadoutTrainer] has no role in the online decision loop, full
 * stop. (It may still run purely as an optional diagnostic/backtest signal
 * upstream, gated behind a `diagnosticsOnly` flag - see
 * [AgentOrchestrator] - but nothing it produces reaches this class.)
 *
 * ### Architecture: reservoir state + the diagram's own-output feedback path
 * The reference diagram feeds a network's own recent outputs `{ŷ_j}` for
 * `j` from `t-n_back` to `t-1` back into the reservoir via `W_back`. This
 * class reuses exactly that pattern, but applied to the *policy's own*
 * output stream: `f_t` is a `tanh`-squashed linear combination of the
 * reservoir state `x_t` and the policy's own last `nBack` positions
 * `f_{t-1}, ..., f_{t-nBack}` -
 * ```
 * z_t = w · [x_t, f_{t-1}, ..., f_{t-nBack}, 1]
 * f_t = tanh(z_t)
 * ```
 * `tanh` is what makes `f_t ∈ [-1, 1]` a hard guarantee, not a clamp -
 * `+1` is max long, `-1` is max short, matching design doc §1's `f_{t-1}`
 * convention exactly.
 *
 * ### Training: RTRL trace + an Extended Kalman Filter weight update
 * The weights `w` are trained online against the utility signal supplied
 * by the caller, following Moody & Saffell's RRL scheme:
 * `dU/dw_i = d(dsr_t)/d(r_t) · d(r_t)/d(f_t) · d(f_t)/d(w_i)`. The first
 * two factors are supplied by the caller each [update] call (from
 * [RewardEngine.RewardBreakdown.differentialSharpeGradientWrtReward] and
 * [RewardEngine.positionGradient] respectively - see those docs for why
 * the reward gradient is truncated to `r_t`'s *immediate* dependence on
 * `f_t`, not `r_{t+1}`'s). The third factor, `d(f_t)/d(w_i)`, is what this
 * class computes itself via **real-time recurrent learning restricted to
 * the feedback path**: because `f_t` depends on `w` both directly (through
 * `x_t`/bias) and indirectly through `f_{t-1}, ..., f_{t-nBack}`
 * (which are themselves functions of `w` from earlier steps), the total
 * derivative needs the chain rule through that recurrence:
 * ```
 * d(f_t)/d(w_i) = (1 - f_t²) · [ regressor_i(t) + Σ_{k=1}^{nBack} w_backₖ · d(f_{t-k})/d(w_i) ]
 * ```
 * This is exact RTRL for this network - not an approximation - but it is
 * only ever applied to the small feedback path (`nBack` lagged scalars),
 * never back into the reservoir itself: [ReservoirEngine]'s weights are
 * fixed by construction (Phase 2), so there is nothing to differentiate
 * there, and that is precisely what keeps "RTRL" cheap enough to call
 * "-lite" here. Full RTRL through an `n`-unit *reservoir* would be
 * `O(n_regressors² · n)` per step; this is `O(n_regressors · nBack)` per
 * step - trivial next to the on-device reservoir-step budget
 * [ReservoirEngineBenchmarkTest] already showed has enormous headroom
 * (Phase 2's on-device benchmark budgets a single reservoir step at under
 * 1% of even the shortest, 60s, bar interval).
 *
 * ### Gap-closure #3: EKF replaces plain gradient ascent
 * Earlier revisions of [update] applied `dU/dw_i` directly, scaled by a
 * fixed `learningRate`, then clamped. Per the source paper (§III-B2), the
 * trace above (`∇υ_t` once combined with the caller's utility/reward
 * gradients) is instead fed through an Extended Kalman Filter -
 * [EkfWeightUpdater] - which adapts its effective step size per weight via
 * a `nRegressors x nRegressors` covariance `P`, rather than using one
 * fixed scalar learning rate for every weight for the whole run. The RTRL
 * trace computation in [step] is unchanged by this gap - only how [update]
 * turns that trace into a weight delta changed. See [EkfWeightUpdater]'s
 * own doc for the recursion. [DEFAULT_WEIGHT_CLIP]/[DEFAULT_MAX_WEIGHT_DELTA]
 * remain as an outer defense-in-depth bound around the EKF's output - the
 * paper's EKF is not itself guaranteed bounded, so this class keeps its
 * existing safety net rather than trusting the filter alone (a deliberate,
 * intentional divergence from the paper, in favor of a more conservative
 * posture - not an oversight).
 *
 * ### Performance
 * Flat `FloatArray`s throughout, no boxed `Double`, no object graphs,
 * consistent with every phase since 1. [step] and [update] together are
 * the only two allocation-free hot-path calls; [weightsSnapshot] is the
 * one exception (a fresh copy, for logging/checkpointing use only, mirrors
 * [ReadoutTrainer.wOutSnapshot]).
 */
class PolicyEngine(
    val nHidden: Int,
    val nBack: Int = DEFAULT_N_BACK,
    val beta: Float = EkfWeightUpdater.DEFAULT_BETA,
    val tau: Float = EkfWeightUpdater.DEFAULT_TAU,
    val weightClip: Float = DEFAULT_WEIGHT_CLIP,
    initialWeights: FloatArray? = null,
    seed: Long = 0L,
) {
    companion object {
        const val DEFAULT_N_BACK = 5
        /** `|w_i|` is clamped to this after every [update] - the EKF recursion (gap-closure #3) is not itself guaranteed bounded, so an explicit bound is this class's outer safety net, same role this constant has always played. */
        const val DEFAULT_WEIGHT_CLIP = 5f
        /** `|Δw_i|` from a single [update] call is clamped to this before being applied, same defense-in-depth spirit as [DEFAULT_WEIGHT_CLIP]. */
        const val DEFAULT_MAX_WEIGHT_DELTA = 0.5f
        /** Initial weights are drawn uniformly from `[-INIT_SCALE, INIT_SCALE]` - small, to start near `f_t ≈ 0` (flat) rather than pinned at ±1 from the first bar. */
        const val DEFAULT_INIT_SCALE = 0.05f
    }

    /** Regressor width: reservoir state (`nHidden`) + own-output feedback (`nBack`) + bias (1). No readout-forecast slot - see the class doc's gap-closure #2 note. */
    val nRegressors: Int = nHidden + nBack + 1

    /** Base index of the `nBack` feedback slots (`f_{t-1}` at `feedbackBase`, ..., `f_{t-nBack}` at `feedbackBase+nBack-1`). */
    private val feedbackBase: Int = nHidden

    /** Index of the trailing bias slot. */
    private val biasIndex: Int = nHidden + nBack

    init {
        require(nHidden >= 1) { "nHidden must be >= 1, was $nHidden" }
        require(nBack >= 0) { "nBack must be >= 0, was $nBack" }
        require(weightClip > 0f) { "weightClip must be > 0, was $weightClip" }
        // beta/tau are validated by EkfWeightUpdater's own init block below.
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

    // ---- recurrent state: the policy's own last nBack outputs ----
    // pastPositions[0] = f_{t-1}, pastPositions[1] = f_{t-2}, ..., pastPositions[nBack-1] = f_{t-nBack}.
    private val pastPositions: FloatArray = FloatArray(nBack)

    // ---- RTRL trace state ----
    // traceHistory[k * nRegressors + i] = d(f_{t-1-k}) / d(w_i), for k in 0 until nBack.
    // traceHistory[0] is therefore d(f_{t-1})/d(w_i) - the trace of the
    // *immediately preceding* step, exactly what the recurrence above needs.
    private val traceHistory: FloatArray = FloatArray(nBack * nRegressors)

    // Scratch buffers - allocated once, reused every step()/update() call.
    private val regressorScratch = FloatArray(nRegressors)
    private val newTraceScratch = FloatArray(nRegressors)

    /** `d(f_t)/d(w_i)` from the most recent [step] call - what [update] applies the gradient against. */
    private val lastTrace = FloatArray(nRegressors)

    // ---- gap-closure #3: EKF weight updater, replacing plain gradient ascent ----
    private val ekf = EkfWeightUpdater(nWeights = nRegressors, beta = beta, tau = tau)

    // Scratch buffer for the per-weight utility gradient passed to the EKF each [update] call.
    private val gradUtilityScratch = FloatArray(nRegressors)

    private var lastPosition: Float = 0f
    private var stepsTaken: Long = 0L

    /** The position `f_t` produced by the most recent [step] call (0 if [step] hasn't been called yet). */
    fun currentPosition(): Float = lastPosition

    /**
     * Advances the policy by one bar-close step, producing `f_t` from the
     * reservoir's current hidden state alone (plus the policy's own
     * feedback/bias slots - see the class doc's gap-closure #2 note). Must be
     * called exactly once per bar, and (when training online) followed by
     * exactly one [update] call before the next [step] - [update] reads
     * [lastTrace], which this method overwrites every call.
     *
     * @param reservoirState [ReservoirEngine.step] / [ReservoirEngine.currentState]'s `x_t`, `nHidden`-shaped. Per gap-closure #2, this is the *only* external signal in the regressor besides the policy's own feedback and bias - there is no readout-forecast parameter.
     */
    fun step(reservoirState: FloatArray): Float {
        require(reservoirState.size == nHidden) {
            "reservoirState size ${reservoirState.size} != nHidden $nHidden"
        }
        buildRegressor(reservoirState)

        var z = 0f
        var i = 0
        while (i < nRegressors) {
            z += weights[i] * regressorScratch[i]
            i++
        }
        val f = tanh(z.toDouble()).toFloat()
        val dtanh = 1f - f * f

        // d(f_t)/d(w_i) = dtanh * [ regressor_i(t) + sum_k w_backK * d(f_{t-1-k})/d(w_i) ]
        i = 0
        while (i < nRegressors) {
            var indirect = 0f
            var k = 0
            while (k < nBack) {
                indirect += weights[feedbackBase + k] * traceHistory[k * nRegressors + i]
                k++
            }
            newTraceScratch[i] = dtanh * (regressorScratch[i] + indirect)
            i++
        }
        System.arraycopy(newTraceScratch, 0, lastTrace, 0, nRegressors)

        // Shift the trace ring buffer: what was d(f_{t-1-k}) becomes d(f_{t-2-k}) for next step.
        var k = nBack - 1
        while (k >= 1) {
            System.arraycopy(traceHistory, (k - 1) * nRegressors, traceHistory, k * nRegressors, nRegressors)
            k--
        }
        if (nBack > 0) System.arraycopy(lastTrace, 0, traceHistory, 0, nRegressors)

        // Shift the position feedback buffer the same way.
        var p = nBack - 1
        while (p >= 1) {
            pastPositions[p] = pastPositions[p - 1]
            p--
        }
        if (nBack > 0) pastPositions[0] = f

        lastPosition = f
        stepsTaken++
        return f
    }

    /**
     * One EKF-driven update against the utility signal derived from the
     * bar this [step] call's `f_t` just priced in (gap-closure #3 - see
     * the class doc's "Training" section and [EkfWeightUpdater]). See the
     * class doc's "Training" section for the chain rule this multiplies
     * out; `dUtilityDReward * dRewardDPosition` is `dU/d(f_t)`, so the
     * per-weight utility gradient `∇υ_t` fed to [EkfWeightUpdater] is
     * `dU/d(f_t) * d(f_t)/d(w_i)` (this class's own [lastTrace]).
     *
     * @param dUtilityDReward `d(dsr_t)/d(r_t)` - [RewardEngine.RewardBreakdown.differentialSharpeGradientWrtReward] from the *same* bar's [RewardEngine.step] call.
     * @param dRewardDPosition `d(r_t)/d(f_t)` - [RewardEngine.positionGradient] computed with the *same* `prevPosition`/`currPosition` pair this bar's [step] call produced.
     */
    fun update(dUtilityDReward: Double, dRewardDPosition: Double) {
        val gradientCoefficient = (dUtilityDReward * dRewardDPosition).toFloat()
        if (!gradientCoefficient.isFinite()) return // guard: never let a bad gradient corrupt stable weights

        var i = 0
        while (i < nRegressors) {
            var g = gradientCoefficient * lastTrace[i]
            if (!g.isFinite()) g = 0f
            gradUtilityScratch[i] = g
            i++
        }

        val delta = ekf.computeDelta(gradUtilityScratch)

        i = 0
        while (i < nRegressors) {
            var d = delta[i]
            if (!d.isFinite()) d = 0f
            d = d.coerceIn(-DEFAULT_MAX_WEIGHT_DELTA, DEFAULT_MAX_WEIGHT_DELTA)
            val updated = (weights[i] + d).coerceIn(-weightClip, weightClip)
            weights[i] = updated
            i++
        }
    }

    private fun buildRegressor(reservoirState: FloatArray) {
        System.arraycopy(reservoirState, 0, regressorScratch, 0, nHidden)
        var k = 0
        while (k < nBack) {
            regressorScratch[feedbackBase + k] = pastPositions[k]
            k++
        }
        regressorScratch[biasIndex] = 1f
    }

    /**
     * True iff every weight, every RTRL trace entry, and the EKF's own
     * covariance `P` (gap-closure #3 - see [EkfWeightUpdater.isStable])
     * are finite. A backtest replay should assert this after every
     * [update] - mirrors [ReadoutTrainer.isStable]'s role for Phase 3, now
     * for Phase 5's own online-learning stability requirement.
     */
    fun isStable(): Boolean {
        for (w in weights) if (!w.isFinite()) return false
        for (t in traceHistory) if (!t.isFinite()) return false
        for (t in lastTrace) if (!t.isFinite()) return false
        if (!ekf.isStable()) return false
        val f = lastPosition
        if (!f.isFinite() || f < -1f || f > 1f) return false
        return true
    }

    /**
     * The largest absolute value anywhere in the EKF's covariance matrix
     * `P` (gap-closure #3) - see [EkfWeightUpdater.covarianceMagnitude]'s
     * doc for why this, not [isStable] alone, is the meaningful early
     * divergence signal; same role [ReadoutTrainer.covarianceMagnitude]
     * plays for the readout's RLS covariance.
     */
    fun ekfCovarianceMagnitude(): Float = ekf.covarianceMagnitude()

    /** Snapshot (fresh copy) of the trained weights - for logging/future checkpointing (Phase 6), not read on the hot path. */
    fun weightsSnapshot(): FloatArray = weights.copyOf()

    /**
     * Snapshot (fresh copy) of `d(f_t)/d(w_i)` from the most recent [step]
     * call - test-only visibility, so gradient-correctness tests can
     * compare this class's own RTRL trace directly against a
     * finite-difference gradient without needing to reverse-engineer it
     * out of [update]'s EKF-shaped output (see `PolicyEngineTest`).
     */
    internal fun lastTraceSnapshot(): FloatArray = lastTrace.copyOf()

    /**
     * Resets recurrent state (own-output feedback history and RTRL traces)
     * to a fresh, no-history state, without touching the trained weights -
     * same split [ReservoirEngine.resetState] draws between state and
     * weights. Use when starting a new replay/episode with an
     * already-trained policy.
     */
    fun resetState() {
        pastPositions.fill(0f)
        traceHistory.fill(0f)
        lastTrace.fill(0f)
        lastPosition = 0f
    }
}
