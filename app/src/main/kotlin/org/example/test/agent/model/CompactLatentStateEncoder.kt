package org.example.test.agent.model

import org.example.test.agent.MarketFeatureFrame

/** Hyperparameters shared by pretraining and live inference - both must agree on these, which is exactly what [EncoderCheckpoint] pins down. */
data class LatentStateEncoderConfig(
    val hiddenDim: Int = 48,
    val seed: Long = 42L,
) {
    val inputDim: Int = FeatureVectorizer.FEATURE_NAMES.size
}

/**
 * Design doc §3.1's latent state encoder: "A recurrent or attention-based
 * encoder... compresses a rolling observation window into a belief state,
 * so the policy conditions on inferred context rather than raw features
 * that 'expire.'" This is the recurrent (GRU) choice, kept intentionally
 * *compact* - one layer, tens of hidden units - so it: (a) is cheap enough
 * for the on-device live path (§7.5's low-latency execution constraint), and
 * (b) doesn't have enough capacity to simply memorize the synthetic
 * curriculum instead of learning the regime-summarizing behavior server
 * pretraining is meant to instill.
 *
 * One instance serves two roles, deliberately sharing the same
 * [FeatureVectorizer] + [GRUCell] code path both use:
 *
 * - **Live/streaming** ([encodeStep]): call once per incoming
 *   [MarketFeatureFrame], holding hidden state across calls, exactly the
 *   way [org.example.test.agent.AgentDataIngestionService] emits frames.
 * - **Offline/training** ([encodeEpisode]): unroll an entire
 *   [org.example.test.agent.sim.SyntheticEpisode]'s frames at once and get
 *   every intermediate hidden state + [GRUCell.StepCache] back, which is
 *   what [org.example.test.agent.pretrain.EncoderPretrainer] needs for BPTT.
 *
 * Whatever weights server pretraining converges on are the ones shipped to
 * every live instance via [EncoderCheckpoint] - this class itself never
 * decides *when* to retrain, it only knows how to run forward (and, when
 * asked, backward) through one GRU layer.
 */
class CompactLatentStateEncoder(val config: LatentStateEncoderConfig = LatentStateEncoderConfig()) {
    val gru = GRUCell(inputDim = config.inputDim, hiddenDim = config.hiddenDim, seed = config.seed)
    val vectorizer = FeatureVectorizer()

    private var hiddenState = DoubleArray(config.hiddenDim)

    /** Zeros the recurrent state and the vectorizer's return-computation memory - call at the start of a new stream/episode so nothing leaks across the boundary. */
    fun resetState() {
        hiddenState = DoubleArray(config.hiddenDim)
        vectorizer.reset()
    }

    /**
     * Live path: feeds one [MarketFeatureFrame] through the vectorizer and
     * one GRU step, updating and returning the belief state. `fitNormalizer`
     * defaults to false here - live inference standardizes against the
     * frozen statistics baked into the loaded checkpoint, it does not keep
     * adjusting them from live data (that drift would silently change the
     * meaning of "normalized" out from under the trained weights).
     */
    fun encodeStep(frame: MarketFeatureFrame, fitNormalizer: Boolean = false): DoubleArray {
        val x = vectorizer.vectorize(frame, fit = fitNormalizer)
        val cache = gru.forwardStep(x, hiddenState)
        hiddenState = cache.h.copyOf()
        return hiddenState.copyOf()
    }

    /** Current belief state without advancing anything - e.g. for a policy that wants to read context without also feeding a frame. */
    fun currentState(): DoubleArray = hiddenState.copyOf()

    /**
     * Offline/training path: unrolls the whole [frames] sequence from a
     * fresh (zero) hidden state, in order, returning every timestep's
     * [GRUCell.StepCache] (needed for BPTT) and hidden state. Does not touch
     * or depend on [encodeStep]'s streaming hidden state - training episodes
     * are independent of whatever live stream this instance might otherwise
     * be serving.
     *
     * @param fitNormalizer Whether to fold each frame into the vectorizer's
     *   running normalization stats as it goes - true for the pretraining
     *   corpus's first pass(es), false when replaying a held-out validation
     *   episode against already-fit stats.
     */
    fun encodeEpisode(frames: List<MarketFeatureFrame>, fitNormalizer: Boolean): EpisodeEncoding {
        vectorizer.reset()
        var h = DoubleArray(config.hiddenDim)
        val caches = ArrayList<GRUCell.StepCache>(frames.size)
        val states = ArrayList<DoubleArray>(frames.size)
        for (frame in frames) {
            val x = vectorizer.vectorize(frame, fit = fitNormalizer)
            val cache = gru.forwardStep(x, h)
            h = cache.h.copyOf()
            caches.add(cache)
            states.add(h.copyOf())
        }
        return EpisodeEncoding(caches, states)
    }

    /** [caches] for BPTT, [hiddenStates] the plain per-timestep output any auxiliary head reads from. */
    class EpisodeEncoding(val caches: List<GRUCell.StepCache>, val hiddenStates: List<DoubleArray>)
}
