package org.example.syncora.agent

import org.example.syncora.ml.PpoHyperparameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Runs the REAL [CpcvPboValidationGate] / [ConfigPerformance] classes (not a Python port) against
 * two synthetic scenarios: a candidate with a genuine, consistent OOS edge (must PASS), and a
 * candidate deliberately engineered to look best in-sample every split while its OOS score is
 * pure noise - the textbook definition of overfitting (must REJECT). This is the sanity check on
 * task 8's implementation, run against the actual compiled Kotlin, not a proxy.
 *
 * Pure JVM: [CpcvPboValidationGate] and [CombinatorialPurgedCrossValidator] have no Android
 * imports, so this runs via `./gradlew test` - no device, emulator, or Robolectric needed.
 */
class CpcvPboValidationGateTest {

    private val gate = CpcvPboValidationGate(alpha = 0.10)

    private fun genuineEdgeConfigs(splits: Int = 8, configs: Int = 6, seed: Int = 1): List<ConfigPerformance> {
        val rng = Random(seed)
        return (0 until configs).map { i ->
            val trueSkill = if (i == 0) 1.0 else rng.nextDouble(-0.1, 0.15)
            val scores = (0 until splits).map { s ->
                ConfigSplitScore(
                    splitIndex = s,
                    inSampleScore = trueSkill + rng.nextDouble(-0.1, 0.1),
                    outOfSampleScore = trueSkill + rng.nextDouble(-0.1, 0.1),
                )
            }
            ConfigPerformance(hyperparameters = PpoHyperparameters(learningRate = (i + 1) * 1e-4f), splitScores = scores)
        }
    }

    private fun overfitConfigs(splits: Int = 8, configs: Int = 6, seed: Int = 2): List<ConfigPerformance> {
        val rng = Random(seed)
        return (0 until configs).map { i ->
            val scores = (0 until splits).map { s ->
                if (i == 0) {
                    // Engineered to always win in-sample, while its OOS score is uncorrelated noise.
                    ConfigSplitScore(splitIndex = s, inSampleScore = 10.0 + rng.nextDouble(-0.01, 0.01), outOfSampleScore = rng.nextDouble(-1.0, 1.0))
                } else {
                    ConfigSplitScore(splitIndex = s, inSampleScore = rng.nextDouble(-1.0, 1.0), outOfSampleScore = rng.nextDouble(-1.0, 1.0))
                }
            }
            ConfigPerformance(hyperparameters = PpoHyperparameters(learningRate = (i + 1) * 1e-4f), splitScores = scores)
        }
    }

    @Test
    fun `genuine consistent edge passes the gate`() {
        repeat(10) { seed ->
            val decision = gate.decide(genuineEdgeConfigs(seed = seed + 1))
            assertTrue(
                "seed=${seed + 1}: expected PASS, got $decision",
                decision is GateDecision.Pass,
            )
        }
    }

    @Test
    fun `overfit candidate is rejected`() {
        repeat(10) { seed ->
            val decision = gate.decide(overfitConfigs(seed = seed + 2))
            assertTrue(
                "seed=${seed + 2}: expected REJECT, got $decision",
                decision is GateDecision.Reject,
            )
            val pbo = (decision as GateDecision.Reject).pboProbability
            assertTrue("seed=${seed + 2}: rejected but pboProbability was null", pbo != null)
            assertTrue("seed=${seed + 2}: pbo=$pbo should be >= alpha 0.10", pbo!! >= 0.10)
        }
    }

    @Test
    fun `too few configs or splits is rejected rather than passed by default`() {
        val singleConfig = genuineEdgeConfigs(configs = 1)
        assertTrue(gate.decide(singleConfig) is GateDecision.Reject)

        val singleSplit = genuineEdgeConfigs(splits = 1)
        assertTrue(gate.decide(singleSplit) is GateDecision.Reject)
    }

    @Test
    fun `passing decision carries the best mean-OOS config as the winner`() {
        val decision = gate.decide(genuineEdgeConfigs(seed = 1)) as GateDecision.Pass
        // Config 0 was constructed with the real edge (trueSkill=1.0 vs ~[-0.1, 0.15] for the rest).
        assertEquals(1e-4f, decision.winningHyperparameters.learningRate, 1e-9f)
    }
}
