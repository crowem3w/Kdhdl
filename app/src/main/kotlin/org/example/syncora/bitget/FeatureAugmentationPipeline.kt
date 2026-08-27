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

/**
 * One time-step of the direct-RL readout's actual input: the augmented
 * state z_t = [u_t, x_t, ŷ_t] (`recurrent-reinforcement-learning-crypto-agent.md`
 * Eq. 5), bundled with the raw [RLFeatureSample] fields (Δp_t, δ_t, κ_t)
 * a readout needs to compute r_t (Eq. 8) and its gradient (Eq. 12) - so
 * nothing downstream needs to re-derive them or re-touch the pipeline
 * that produced [source].
 */
data class AugmentedFeatureSample(
    val timestampMs: Long,

    /** z_t = [u_t, x_t, ŷ_t] (Eq. 5) - the reservoir's augmented state. Dimension [EchoStateReservoir.augmentedSize]. */
    val z: DoubleArray,

    /**
     * x_t alone - the reservoir's raw hidden state at this tick.
     * Dimension [EchoStateReservoir.nHidden]. This is exactly the middle
     * slice of [z] (between u_t and ŷ_t); it's surfaced separately purely
     * for diagnostics/logging (e.g. tracking reservoir saturation), not
     * because a readout needs it independently of [z].
     */
    val x: DoubleArray,

    /** The [RLFeatureSample] this augmented sample was built from - carries Δp_t, δ_t, κ_t, and the raw u_t/ŷ_t that went into [z]. */
    val source: RLFeatureSample,
)

/**
 * Bridges [RLFeatureVectorPipeline] (u_t, Δp_t, δ_t, κ_t, ŷ_t - the *data*
 * side) and [EchoStateReservoir] (x_t - the *reservoir* side) into the
 * single augmented feature stream z_t that a direct-RL readout (w_out,
 * the EKF update of Algorithm 1) actually consumes - paper §3.2, Eq. 5.
 * See [DirectRLReadout] (and [DirectRLPositionPipeline] for the analogous
 * "wire it against a live StateFlow" role this class plays for the
 * reservoir).
 *
 * This owns the reservoir's *sequencing*: it drives exactly one
 * [EchoStateReservoir.step] per emitted [RLFeatureSample], in arrival
 * order, on a dispatcher restricted to a single worker. The reservoir's
 * recurrence (x_t depends on x_{t-1}) makes in-order, one-at-a-time
 * stepping a correctness requirement here, not just a performance
 * courtesy - unlike sibling pipelines (e.g. [RLFeatureVectorPipeline])
 * whose own per-tick math has no such ordering dependency and can run on
 * an unrestricted dispatcher.
 *
 * It does not own [reservoir]'s construction or long-term lifecycle -
 * only calls [EchoStateReservoir.reset] on [start] - so a caller retains
 * the ability to construct it once and inspect [EchoStateReservoir.wHidden]
 * / [EchoStateReservoir.spectralRadius] etc. directly if needed.
 */
class FeatureAugmentationPipeline(
    private val source: RLFeatureVectorPipeline,
    private val reservoir: EchoStateReservoir,
) {
    private companion object {
        const val TAG = "FeatureAugmentationPipeline"
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in FeatureAugmentationPipeline coroutine scope", throwable)
    }

    // Restricted to a single worker: reservoir.step() mutates recurrent
    // state and must observe samples strictly in arrival order, one at a
    // time - concurrent or reordered steps would silently corrupt x_t.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + exceptionHandler,
    )
    private var job: Job? = null

    private val _augmentedFeature = MutableStateFlow<AugmentedFeatureSample?>(null)
    val augmentedFeature: StateFlow<AugmentedFeatureSample?> = _augmentedFeature.asStateFlow()

    /** Starts consuming [source]'s samples. Resets the reservoir to x_0 = 0 first, so a restart doesn't carry over stale state from a previous run. */
    fun start() {
        stop()
        reservoir.reset()
        job = source.featureVector
            .filterNotNull()
            .onEach { sample -> _augmentedFeature.value = augment(sample) }
            .catch { e -> Log.e(TAG, "Error augmenting RL feature sample; dropping tick", e) }
            .launchIn(scope)
    }

    fun stop() {
        job?.cancel()
    }

    private fun augment(sample: RLFeatureSample): AugmentedFeatureSample {
        val z = reservoir.step(sample)
        return AugmentedFeatureSample(
            timestampMs = sample.timestampMs,
            z = z,
            x = reservoir.currentState,
            source = sample,
        )
    }
}
