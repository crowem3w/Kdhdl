package org.example.syncora.agent

import org.example.syncora.ml.PpoHyperparameters
import kotlin.math.ln

/** One hyperparameter config's score on one CPCV split - design doc §4's OOS performance matrix `M` is exactly the collection of these across configs × splits. */
data class ConfigSplitScore(
    val splitIndex: Int,
    /** Score on the split's *training* windows - used only to pick each split's in-sample "winner" below, never itself the promotion signal (that would just be re-measuring overfitting on the training set). */
    val inSampleScore: Double,
    /** Score on the split's held-out windows - what actually gets ranked into `ω_c`. */
    val outOfSampleScore: Double,
)

data class ConfigPerformance(
    val hyperparameters: PpoHyperparameters,
    val splitScores: List<ConfigSplitScore>,
) {
    val meanOutOfSampleScore: Double get() = splitScores.map { it.outOfSampleScore }.average()
}

sealed class GateDecision {
    /** Design doc §4 step 4: "Deploy only if p < 0.10." [winningHyperparameters] is whichever swept config had the best mean out-of-sample score - the caller is expected to retrain it on the full window set before actually promoting (see [org.example.syncora.work.PolicyTrainingWorker]). */
    data class Pass(
        val winningHyperparameters: PpoHyperparameters,
        val pboProbability: Double,
        val splitsEvaluated: Int,
    ) : GateDecision()

    /** "Otherwise, discard the candidate and keep the currently-live policy" (design doc §4 step 4). */
    data class Reject(
        val reason: String,
        val pboProbability: Double? = null,
    ) : GateDecision()
}

/** Design doc §4's automatic promotion gate, expressed over [ConfigPerformance]s rather than raw model files or `.tflite` bytes, so it stays free of any TFLite/[org.example.syncora.ml.PpoTrainer] dependency - it only ever ranks numbers. */
interface ValidationGate {
    fun decide(configs: List<ConfigPerformance>): GateDecision
}

/**
 * Combinatorial Purged Cross-Validation + Probability of Backtest
 * Overfitting, exactly as design doc §4 specifies: "Sweep H hyperparameter
 * configurations, build the OOS performance matrix M. Compute relative OOS
 * rank ω_c, logit-transform to λ_c, integrate the resulting distribution
 * over the negative domain to get p. Deploy only if p < 0.10."
 *
 * For each CPCV split: the config with the best *in-sample* score is that
 * split's "IS winner" - the one a naive, single-split selection process
 * would have picked. [decide] then looks up that same config's
 * *out-of-sample* rank among all `H` configs on that split (`ω_c`) and
 * logit-transforms it into `λ_c`. If the IS winner tends to land in the
 * upper half of the OOS ranking across splits, `λ` is usually positive and
 * PBO stays low: whatever wins in-sample generalizes. If the IS winner is
 * no better than a coin flip out-of-sample, `λ` clusters at/below zero and
 * PBO rises toward (or past) [alpha] - the literal signature of a
 * selection process that's fitting split-specific noise rather than a real
 * edge. PBO itself is estimated as the empirical fraction of `λ_c <= 0`
 * across splits (design doc's "integrate ... over the negative domain"),
 * which is the standard discrete approximation when there are too few
 * splits to fit a smooth density.
 */
class CpcvPboValidationGate(private val alpha: Double = 0.10) : ValidationGate {

    override fun decide(configs: List<ConfigPerformance>): GateDecision {
        require(configs.isNotEmpty()) { "Need at least one hyperparameter configuration to evaluate" }

        val splitIndices = configs.first().splitScores.map { it.splitIndex }.toSet()
        require(configs.all { it.splitScores.map { s -> s.splitIndex }.toSet() == splitIndices }) {
            "Every hyperparameter configuration must be scored on the same set of CPCV splits"
        }

        // The design doc's whole premise is a *sweep*: a single config or a
        // single split can't distinguish a real edge from split-specific
        // noise, so there's nothing meaningful to reject or pass yet.
        if (configs.size < 2 || splitIndices.size < 2) {
            return GateDecision.Reject(
                "Not enough configurations (${configs.size}) or CPCV splits (${splitIndices.size}) yet to " +
                    "estimate an overfitting probability - accumulating more experience before the next attempt",
            )
        }

        val h = configs.size
        val lambdas = splitIndices.map { splitIndex ->
            val scoresThisSplit = configs.map { cfg -> cfg.splitScores.first { it.splitIndex == splitIndex } }
            val isWinner = scoresThisSplit.maxBy { it.inSampleScore }
            // Rank among all H configs' OOS scores this split, 1 (worst) .. H (best); ties count inclusively.
            val oosRank = scoresThisSplit.count { it.outOfSampleScore <= isWinner.outOfSampleScore }
            val omega = (oosRank.toDouble() / (h + 1)).coerceIn(MIN_OMEGA, 1.0 - MIN_OMEGA)
            ln(omega / (1.0 - omega))
        }

        val pbo = lambdas.count { it <= 0.0 }.toDouble() / lambdas.size
        val winner = configs.maxBy { it.meanOutOfSampleScore }

        return if (pbo < alpha) {
            GateDecision.Pass(winningHyperparameters = winner.hyperparameters, pboProbability = pbo, splitsEvaluated = lambdas.size)
        } else {
            GateDecision.Reject(
                "PBO probability $pbo is >= alpha $alpha across ${lambdas.size} CPCV splits - the best-looking " +
                    "configuration in-sample doesn't reliably generalize out-of-sample; discarding this candidate " +
                    "and keeping the currently-live policy (design doc §4 step 4)",
                pboProbability = pbo,
            )
        }
    }

    private companion object {
        const val MIN_OMEGA = 1e-6
    }
}
