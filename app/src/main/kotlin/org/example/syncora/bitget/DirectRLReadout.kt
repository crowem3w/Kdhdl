package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * One time-step of the direct-RL readout's output, per
 * `recurrent-reinforcement-learning-crypto-agent.md` (Borrageiro, Firoozye,
 * Barucca - IEEE Access, 2022) §3.2.2 (Eq. 6-12) and §3.3's trading gate.
 *
 * This is deliberately the *decision*, not the *fill*: [targetPosition] is
 * the model's raw f_t (Eq. 10) - the differentiable quantity the EKF
 * update (Algorithm 1) actually trains towards - while [gatedPosition] is
 * what §3.3 says should actually reach an execution engine ("trade freely
 * if mu_t >= 0; else flatten and wait"). Turning [gatedPosition] into
 * orders (and reading back the position that was actually filled, which is
 * what feeds [RLFeatureSample.yHat]) is a distinct execution-engine
 * concern that consumes this class's output; it is not implemented here.
 */
data class DirectRLDecision(
    val timestampMs: Long,

    /** f_t (Eq. 10) - the readout's raw target position, bounded to [-1, 1] by tanh. This is what [DirectRLReadout] actually trains w_out against. */
    val targetPosition: Double,

    /** mu_t >= 0 gate (§3.3: "trade freely if mu_t >= 0; else flatten and wait"), evaluated against the running mean *entering* this tick (mu_{t-1}), since mu_t itself isn't known until r_t is realized. */
    val shouldTrade: Boolean,

    /** [targetPosition] if [shouldTrade], else 0.0 - the position an execution engine should actually target. */
    val gatedPosition: Double,

    /**
     * r_t (Eq. 8), computed off the *raw* (ungated) target position
     * f_{t-1}->f_t - the differentiable training target that [mu],
     * [variance], [utility], and the EKF gradient (Eq. 12) are all derived
     * from. Deliberately *not* gated: the paper's chain-rule derivatives
     * (Eq. 12's constituent-derivatives block) are written entirely in
     * terms of f_t, and a hard mu_t>=0 cutoff has no gradient to
     * differentiate through, so gating this would either stall learning
     * during abstention or require inventing a derivative the paper
     * doesn't define. Zero on the very first tick (no prior position to
     * have carried P&L on). See [realizedReturn] for the P&L an execution
     * engine actually earns once §3.3's "flatten and wait" gate is
     * applied.
     */
    val netReturn: Double,

    /**
     * The P&L an execution engine actually realizes this tick, i.e. r_t
     * (Eq. 8) recomputed off [gatedPosition] and the *previous tick's*
     * gated position, rather than the raw target - so it goes to exactly
     * 0.0 while [shouldTrade] is false (§3.3: "flatten and wait"),
     * matching the flat stretches Table 1's position/execution/carry/pnl
     * series show during abstention. This is a reporting quantity only;
     * it plays no part in training (see [netReturn]).
     */
    val realizedReturn: Double,

    /** mu_t (Eq. 7) - online exponentially-decayed mean of r_t, *after* folding in this tick's [netReturn]. */
    val mu: Double,

    /** sigma_t^2 (Eq. 7) - online exponentially-decayed variance of r_t, *after* folding in this tick's [netReturn]. */
    val variance: Double,

    /** upsilon_t = mu_t - (lambda/2) sigma_t^2 (Eq. 6) - the quadratic risk-adjusted utility the readout is maximising. */
    val utility: Double,

    /** 252^0.5 * mu_t / sqrt(sigma_t^2) (Appendix A's ir_t, with an implicit zero baseline b_t since no external benchmark is threaded through this class) - purely a diagnostic, not used anywhere in the update itself. 0.0 while sigma_t^2 is ~0 (e.g. the first few ticks). */
    val informationRatio: Double,
)

/**
 * The *target* (direct-RL) half of the paper's transfer-learning pipeline:
 * consumes the augmented state z_t = [u_t, x_t, y_hat_t] that
 * [EchoStateReservoir] / [FeatureAugmentationPipeline] produce, and learns
 * a readout w_out that targets a position f_t directly (Eq. 10) by
 * differentiating the quadratic utility upsilon_t (Eq. 6) w.r.t. w_out and
 * applying it via an extended Kalman filter (Algorithm 1) - rather than
 * bootstrapping a state-action value function, for exactly the
 * credit-assignment reasons the paper's §6 lays out.
 *
 * Everything here is pure, synchronous state-mutating math - [step] is not
 * safe to call out of sequence or concurrently, for the same reason
 * [EchoStateReservoir.step] isn't: w_out_t, P_t, and the eligibility trace
 * this class carries between calls (see [lastGradient]) are all recurrent,
 * depending on this instance's own immediately-preceding call.
 *
 * A units caveat worth being explicit about: [RLFeatureSample.deltaP] and
 * `executionCostLong`/`executionCostShort` are raw quote-currency price
 * deltas (see that class's own docs), not returns normalised by price -
 * this class folds them into Eq. 8 exactly as the paper writes it, with no
 * additional normalisation invented here. That keeps this class faithful
 * to both the paper's literal formula and the data contract
 * [RLFeatureVectorPipeline] already established; it does mean [lambda]
 * needs to be tuned against whatever price scale the traded instrument
 * actually has (the paper's own default of 1e-5, §3.3, is XBTUSD-scale).
 */
class DirectRLReadout(
    /** Dimensionality of z_t = w_out - must match [EchoStateReservoir.augmentedSize] / [AugmentedFeatureSample.z]'s length. */
    val augmentedSize: Int,
    /** lambda - risk-appetite constant in the quadratic utility (Eq. 6). Paper §3.3 default: 1e-5. */
    private val lambda: Double = DEFAULT_LAMBDA,
    /** beta - Ridge penalty / EKF prior precision (Algorithm 1's P_0 = I_d / beta). Paper §3.3 default: 1. */
    private val beta: Double = DEFAULT_BETA,
    /** tau - exponential decay factor shared by the moment estimates (Eq. 7) and the EKF update (Algorithm 1). Paper §3.3 default: 0.999. */
    private val tau: Double = DEFAULT_TAU,
) {
    /** Convenience constructor sized directly off a reservoir, so a caller doesn't have to keep [augmentedSize] in sync by hand. */
    constructor(
        reservoir: EchoStateReservoir,
        lambda: Double = DEFAULT_LAMBDA,
        beta: Double = DEFAULT_BETA,
        tau: Double = DEFAULT_TAU,
    ) : this(reservoir.augmentedSize, lambda, beta, tau)

    companion object {
        const val DEFAULT_LAMBDA: Double = 0.00001
        const val DEFAULT_BETA: Double = 1.0
        const val DEFAULT_TAU: Double = 0.999

        /** Trading days per year, for [DirectRLDecision.informationRatio] (Appendix A's ir_t). */
        private const val ANNUALISATION_FACTOR: Double = 252.0
    }

    /** Index of z_t's/w_out's last component - Appendix A: "n = n_input + n_hidden + n_back - 1 (0-indexed)" - i.e. the ŷ_t slot carrying f_{t-1}, since z_t = [u_t, x_t, ŷ_t] and ŷ_t is oldest-first. */
    private val n: Int = augmentedSize - 1

    // w_out_0 = 0_d (Algorithm 1's Initialise line).
    private var wOut: DoubleArray = DoubleArray(augmentedSize)

    // P_0 = I_d / beta (Algorithm 1's Initialise line).
    private var p: Array<DoubleArray> = identityOverBeta()

    // mu_0, sigma^2_0 - no prior return history yet.
    private var mu: Double = 0.0
    private var sigma2: Double = 0.0

    // f_{t-1} - the readout's own previous raw target (Eq. 10), which is
    // what Eq. 8's r_t and Eq. 12's recurrent term are actually defined
    // against; deliberately *not* re-derived from [RLFeatureSample.yHat],
    // since that field tracks realised (possibly gate-flattened or
    // execution-lagged) account state rather than this class's own
    // differentiable output - see [RLFeatureSample.yHat]'s own docs.
    private var prevF: Double = 0.0

    // f_{t-1} of the *executed* (gate-respecting) position series - the
    // previous tick's [DirectRLDecision.gatedPosition], not [prevF]. Kept
    // separate because during a "flatten and wait" tick prevF still
    // advances to the raw f_t (so training keeps seeing the true
    // recurrent f_t sequence), but the position an execution engine
    // actually carried into the next tick was 0 - [realizedReturn] needs
    // that distinction to go to zero while gated off.
    private var prevExecutedF: Double = 0.0

    /** df_{t-1}/dw_{t-1}^out (the recurrent term of Eq. 12) - an eligibility trace carried between [step] calls. Exposed read-only purely for diagnostics/tests. */
    var lastGradient: DoubleArray = DoubleArray(augmentedSize)
        private set

    /** Resets the readout to its just-constructed state - e.g. when starting a fresh online-learning run or switching instruments. Does *not* reset [EchoStateReservoir]; a caller resetting both should also call that separately. */
    fun reset() {
        wOut = DoubleArray(augmentedSize)
        p = identityOverBeta()
        mu = 0.0
        sigma2 = 0.0
        prevF = 0.0
        prevExecutedF = 0.0
        lastGradient = DoubleArray(augmentedSize)
    }

    /**
     * One readout tick. Returns null (and leaves all internal state
     * untouched) if [sample] is missing something Eq. 8 needs to realise
     * r_t safely - the funding rate, or (only when this tick would
     * actually change position, i.e. f_t != f_{t-1}) the execution-cost
     * side that trade direction would walk - matching the
     * "read it, never assume it" discipline [RLFeatureVectorPipeline]
     * already established rather than silently substituting a zero cost.
     */
    fun step(sample: AugmentedFeatureSample): DirectRLDecision? {
        val z = sample.z
        require(z.size == augmentedSize) { "z has size ${z.size}, expected augmentedSize=$augmentedSize" }
        val source = sample.source
        val kappaT = source.kappaT ?: return null

        // f_t = tanh(w_t^out . z_t)  (Eq. 10).
        var s = 0.0
        for (i in 0 until augmentedSize) s += wOut[i] * z[i]
        val f = tanh(s)
        val oneMinusTanhSq = 1.0 - f * f

        val deltaF = f - prevF

        // §3.3's execution gate ("trade freely if mu_t >= 0; else flatten
        // and wait"), evaluated against mu *entering* this tick (mu_{t-1}),
        // since mu_t itself isn't known until r_t is realized below.
        val shouldTrade = mu >= 0.0
        val executedF = if (shouldTrade) f else 0.0
        val deltaExecuted = executedF - prevExecutedF

        // Resolve whichever execution-cost side(s) this tick actually
        // needs - the raw delta for the differentiable training target
        // below, and the executed delta for [DirectRLDecision.realizedReturn]
        // - bailing out (no state mutated) rather than silently assuming a
        // zero cost if a needed side is missing. Short-circuits before any
        // mutation, matching the class's existing "read it, never assume
        // it" discipline.
        fun costFor(delta: Double): Double? = when {
            delta == 0.0 -> 0.0
            delta > 0.0 -> source.executionCostLong
            else -> source.executionCostShort
        }
        val executionCost = costFor(deltaF) ?: return null
        val executionCostExecuted = costFor(deltaExecuted) ?: return null

        // g_t = df_t/dw_t^out = (1 - tanh^2(s_t)) * (z_t + w_t^out[n] * g_{t-1}),
        // the recurrent eligibility trace behind Eq. 12's "df_t/dw_t^out"
        // constituent derivative (§3.2.2's "Constituent derivatives" block).
        val wn = wOut[n]
        val g = DoubleArray(augmentedSize)
        for (i in 0 until augmentedSize) {
            g[i] = oneMinusTanhSq * (z[i] + wn * lastGradient[i])
        }

        // r_t = Δp_t f_{t-1} - δ_t|Δf_t| - κ_t f_t  (Eq. 8) - the
        // differentiable training target, off the *raw* f_t/f_{t-1}. See
        // [DirectRLDecision.netReturn]'s docs for why this stays ungated.
        val r = source.deltaP * prevF - executionCost * kotlin.math.abs(deltaF) - kappaT * f

        // mu_t, sigma_t^2 (Eq. 7) - note sigma_t^2 uses mu_t (post-update), per Eq. 7 as written.
        // (mu itself, read above for shouldTrade, is mu_{t-1} entering this tick.)
        val muAfter = tau * mu + (1.0 - tau) * r
        val sigma2After = tau * sigma2 + (1.0 - tau) * (r - muAfter) * (r - muAfter)
        val utility = muAfter - (lambda / 2.0) * sigma2After

        // Constituent derivatives (§3.2.2):
        //   dupsilon_t/dr_t = (1-tau)[1 - lambda(r_t - mu_t)]
        //   dr_t/df_t       = -delta_t sign(Delta f_t) - kappa_t
        //   dr_t/df_{t-1}   = Delta p_t + delta_t sign(Delta f_t)   -- from
        //     differentiating Eq. 8 w.r.t. f_{t-1} directly (not given as
        //     a standalone line in the paper's "Constituent derivatives"
        //     block, but implied by Eq. 8 the same way dr_t/df_t is).
        val deltaFSign = sign(deltaF)
        val dUpsilonDr = (1.0 - tau) * (1.0 - lambda * (r - muAfter))
        val drDf = -executionCost * deltaFSign - kappaT
        val drDfPrev = source.deltaP + executionCost * deltaFSign

        // nabla upsilon_t (Eq. 12): dupsilon/dr_t * { dr_t/df_t * g_t + dr_t/df_{t-1} * g_{t-1} }.
        val gradient = DoubleArray(augmentedSize)
        for (i in 0 until augmentedSize) {
            gradient[i] = dUpsilonDr * (drDf * g[i] + drDfPrev * lastGradient[i])
        }

        applyEkfUpdate(gradient)

        // Realized P&L (§3.3's gate reflected in what an execution engine
        // actually earns - zero while shouldTrade is false), kept separate
        // from r_t above so gating never has to be threaded through Eq. 12.
        val realizedReturn = source.deltaP * prevExecutedF -
            executionCostExecuted * kotlin.math.abs(deltaExecuted) - kappaT * executedF

        val ir = if (sigma2After > 1e-18) sqrt(ANNUALISATION_FACTOR) * muAfter / sqrt(sigma2After) else 0.0

        // Roll state forward for the next tick.
        mu = muAfter
        sigma2 = sigma2After
        prevF = f
        prevExecutedF = executedF
        lastGradient = g

        return DirectRLDecision(
            timestampMs = sample.timestampMs,
            targetPosition = f,
            shouldTrade = shouldTrade,
            gatedPosition = executedF,
            netReturn = r,
            realizedReturn = realizedReturn,
            mu = muAfter,
            variance = sigma2After,
            utility = utility,
            informationRatio = ir,
        )
    }

    /**
     * Algorithm 1 (Extended Kalman Filter update), applied in-place to
     * [wOut] / [p] - transcribed line-for-line rather than pre-simplified,
     * so it stays directly auditable against the paper's pseudocode.
     */
    private fun applyEkfUpdate(gradient: DoubleArray) {
        // P_{t-1} . nabla_upsilon_t
        val pGrad = DoubleArray(augmentedSize)
        for (i in 0 until augmentedSize) {
            var acc = 0.0
            val row = p[i]
            for (j in 0 until augmentedSize) acc += row[j] * gradient[j]
            pGrad[i] = acc
        }

        // q = 1 + nabla_upsilon_t^T P_{t-1} nabla_upsilon_t / tau
        var gradPGrad = 0.0
        for (i in 0 until augmentedSize) gradPGrad += gradient[i] * pGrad[i]
        val q = 1.0 + gradPGrad / tau

        // Degenerate step (q ~ 0, e.g. a first tick with an all-zero
        // gradient): leave w_out/P untouched rather than divide by ~0.
        if (kotlin.math.abs(q) < 1e-12) return

        // k = P_{t-1} nabla_upsilon_t / (q tau)
        val k = DoubleArray(augmentedSize) { i -> pGrad[i] / (q * tau) }

        // w_out_t = w_out_{t-1} + k
        for (i in 0 until augmentedSize) wOut[i] += k[i]

        // P_t = P_{t-1}/tau - k k^T q ; P_t = P_t * tau  (variance stabilisation).
        val next = Array(augmentedSize) { DoubleArray(augmentedSize) }
        for (i in 0 until augmentedSize) {
            for (j in 0 until augmentedSize) {
                next[i][j] = (p[i][j] / tau - k[i] * k[j] * q) * tau
            }
        }
        p = next
    }

    private fun identityOverBeta(): Array<DoubleArray> =
        Array(augmentedSize) { i -> DoubleArray(augmentedSize) { j -> if (i == j) 1.0 / beta else 0.0 } }
}

/**
 * Drives [DirectRLReadout] off [FeatureAugmentationPipeline]'s live
 * augmented-state stream, the same "wire an existing pure model against an
 * existing live StateFlow" role [FeatureAugmentationPipeline] plays for
 * [EchoStateReservoir]. Does not turn [DirectRLDecision.gatedPosition]
 * into orders - that's an execution engine's job, consuming
 * [positionTarget].
 */
class DirectRLPositionPipeline(
    private val source: FeatureAugmentationPipeline,
    private val readout: DirectRLReadout,
) {
    private companion object {
        const val TAG = "DirectRLPositionPipeline"
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in DirectRLPositionPipeline coroutine scope", throwable)
    }

    // Restricted to a single worker for the same reason
    // [FeatureAugmentationPipeline] is: [DirectRLReadout.step] mutates
    // recurrent state (w_out, P, the eligibility trace) and must observe
    // samples strictly in arrival order, one at a time.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + exceptionHandler,
    )
    private var job: Job? = null

    private val _positionTarget = MutableStateFlow<DirectRLDecision?>(null)
    val positionTarget: StateFlow<DirectRLDecision?> = _positionTarget.asStateFlow()

    /** Starts consuming [source]'s augmented-state stream. Resets [readout] first, so a restart doesn't carry over a stale w_out/EKF-covariance from a previous run. */
    fun start() {
        stop()
        readout.reset()
        job = source.augmentedFeature
            .filterNotNull()
            .onEach { sample -> readout.step(sample)?.let { decision -> _positionTarget.value = decision } }
            .catch { e -> Log.e(TAG, "Error computing direct-RL position; dropping tick", e) }
            .launchIn(scope)
    }

    fun stop() {
        job?.cancel()
    }
}
