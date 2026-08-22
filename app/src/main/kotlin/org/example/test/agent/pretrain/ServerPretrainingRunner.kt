package org.example.test.agent.pretrain

import org.example.test.agent.model.CompactLatentStateEncoder
import org.example.test.agent.model.EncoderCheckpoint
import org.example.test.agent.model.LatentStateEncoderConfig
import org.example.test.agent.sim.SyntheticExperienceSource
import java.io.File

/**
 * Design doc §7.6's server-side half of the training/inference split, for
 * this specific model: an offline JVM job (no Android runtime, no live
 * exchange connection - see `agent/sim`'s dependency chain, which is pure
 * Kotlin) that pretrains [CompactLatentStateEncoder] entirely against Tier 1
 * synthetic data (§4.1) and writes a checkpoint the live app loads.
 *
 * Not wired into the Android `app` entrypoint on purpose: this is meant to
 * run as its own process (a plain `main`, a CI job, a scheduled retrain),
 * separate from anything that touches a device or live capital, matching
 * §7.4's "these run as separate containers/processes" split at the model
 * level. Invoke directly, e.g. from a Kotlin test runner or
 * `kotlinc -include-runtime` script pointed at this file.
 */
object ServerPretrainingRunner {

    data class RunConfig(
        val hiddenDim: Int = 48,
        val epochs: Int = 30,
        val episodesPerEpoch: Int = 64,
        val barsPerEpisode: Int = 240,
        val validationEpisodes: Int = 16,
        val learningRate: Double = 1e-3,
        val fitNormalizerUntilEpoch: Int = 5,
        val checkpointPath: String = "build/pretrain/latent_state_encoder.ckpt",
        val seed: Long = 1234L,
    )

    fun run(runConfig: RunConfig = RunConfig()) {
        val encoder = CompactLatentStateEncoder(LatentStateEncoderConfig(hiddenDim = runConfig.hiddenDim, seed = runConfig.seed))
        val trainer = EncoderPretrainer(encoder = encoder, learningRate = runConfig.learningRate, seed = runConfig.seed xor 0xABCDEFL)
        val source = SyntheticExperienceSource()

        // Held out once up front, generated with a distinct seed so it never overlaps a training draw.
        val validationEpisodes = source.generateCurriculumBatch(
            episodeCount = runConfig.validationEpisodes,
            barsPerEpisode = runConfig.barsPerEpisode,
            seed = runConfig.seed xor 0x5EED_5EEDL,
        )

        println("Server pretraining: hiddenDim=${runConfig.hiddenDim} inputDim=${encoder.config.inputDim} " +
            "epochs=${runConfig.epochs} episodesPerEpoch=${runConfig.episodesPerEpoch} barsPerEpisode=${runConfig.barsPerEpisode}")

        for (epoch in 1..runConfig.epochs) {
            val fitNormalizer = epoch <= runConfig.fitNormalizerUntilEpoch
            val trainEpisodes = source.generateCurriculumBatch(
                episodeCount = runConfig.episodesPerEpoch,
                barsPerEpisode = runConfig.barsPerEpisode,
                seed = runConfig.seed + epoch,
            )
            val trainMetrics = trainer.runEpoch(trainEpisodes, fitNormalizer = fitNormalizer)
            val valMetrics = trainer.evaluate(validationEpisodes)

            println(
                "epoch %2d/%d | train regimeLoss=%.4f frameLoss=%.4f acc=%.3f | val regimeLoss=%.4f frameLoss=%.4f acc=%.3f%s"
                    .format(
                        epoch, runConfig.epochs,
                        trainMetrics.meanRegimeLoss, trainMetrics.meanFrameLoss, trainMetrics.regimeAccuracy,
                        valMetrics.meanRegimeLoss, valMetrics.meanFrameLoss, valMetrics.regimeAccuracy,
                        if (fitNormalizer) "  [fitting normalizer]" else "",
                    )
            )
        }

        val checkpointFile = File(runConfig.checkpointPath)
        EncoderCheckpoint.save(encoder, checkpointFile)
        println("Saved checkpoint to ${checkpointFile.absolutePath}")
    }
}

fun main() {
    ServerPretrainingRunner.run()
}
