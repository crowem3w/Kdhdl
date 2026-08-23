package org.example.syncora.agent

import kotlin.random.Random

/** One combinatorial train/test partition of the block set - design doc §4 step 1: "chronological blocks, generate J = C(N,k) train/validation splits." */
data class CpcvSplit(
    val trainWindows: List<RolloutWindow>,
    val testWindows: List<RolloutWindow>,
)

/**
 * Combinatorial Purged Cross-Validation over [RolloutWindowBuilder]'s
 * assembled daily blocks (design doc §4, "retained essentially as-is" from
 * the original paper). Each [RolloutWindow] is already exactly the kind of
 * fixed-length, chronologically-bounded block CPCV expects - §3.6's
 * one-day, funding-cycle-aligned windows double as CPCV's blocks, so no
 * separate re-chunking step is needed here.
 *
 * **Purging/embargo.** A test block's immediate neighbors are excluded
 * from that split's training set entirely, not left in as ordinary
 * training data. [RolloutWindowBuilder]'s bootstrap value at each window
 * boundary is computed from the *next* window's first logged state, so
 * training on a block adjacent to a held-out test block would leak a
 * sliver of that test block's information back into training through the
 * bootstrap target. [embargoBlocks] controls how many neighboring blocks
 * on each side of a test block get purged this way.
 *
 * **Compute budget.** `C(N,k)` grows fast, and a full combinatorial sweep
 * over more than a handful of blocks isn't a reasonable ask of a phone CPU
 * on a daily schedule (each split gets a full PPO train+evaluate pass per
 * hyperparameter config - see [org.example.syncora.work.PolicyTrainingWorker]).
 * [maxSplits] caps how many splits actually get evaluated; if more
 * combinations exist than that, a reproducible random subset is used
 * instead of the full combinatorial set. This is a deliberate, documented
 * compute/statistical-rigor tradeoff - the same "explicit non-goal"
 * philosophy the design doc applies to background execution (§2.3) applies
 * here to on-device statistical compute.
 */
class CombinatorialPurgedCrossValidator(
    private val testBlocksPerSplit: Int = 2,
    private val embargoBlocks: Int = 1,
    private val maxSplits: Int = 8,
) {
    init {
        require(testBlocksPerSplit >= 1) { "testBlocksPerSplit must be at least 1, was $testBlocksPerSplit" }
        require(embargoBlocks >= 0) { "embargoBlocks must be non-negative, was $embargoBlocks" }
        require(maxSplits >= 1) { "maxSplits must be at least 1, was $maxSplits" }
    }

    /** Every [CpcvSplit] this configuration produces from [windows], oldest-block-first internally but in no particular split order. Empty if there aren't enough blocks to form even one split. */
    fun splits(windows: List<RolloutWindow>): List<CpcvSplit> {
        val ordered = windows.sortedBy { it.windowStartMs }
        val n = ordered.size
        if (n < testBlocksPerSplit + 1) return emptyList()

        val candidateSplits = combinations(n, testBlocksPerSplit).mapNotNull { testIdx ->
            val purged = purgedIndices(testIdx, n)
            val trainIdx = (0 until n).filterNot { it in testIdx || it in purged }
            if (trainIdx.isEmpty()) return@mapNotNull null
            CpcvSplit(trainWindows = trainIdx.map { ordered[it] }, testWindows = testIdx.map { ordered[it] })
        }

        return if (candidateSplits.size <= maxSplits) {
            candidateSplits
        } else {
            candidateSplits.shuffled(Random(SPLIT_SAMPLING_SEED)).take(maxSplits)
        }
    }

    private fun purgedIndices(testIdx: Set<Int>, n: Int): Set<Int> {
        val purged = mutableSetOf<Int>()
        for (idx in testIdx) {
            for (offset in 1..embargoBlocks) {
                (idx - offset).takeIf { it in 0 until n }?.let { purged += it }
                (idx + offset).takeIf { it in 0 until n }?.let { purged += it }
            }
        }
        return purged
    }

    private fun combinations(n: Int, k: Int): List<Set<Int>> {
        val result = mutableListOf<Set<Int>>()
        val current = mutableListOf<Int>()
        fun recurse(start: Int) {
            if (current.size == k) {
                result += current.toSet()
                return
            }
            for (i in start until n) {
                current += i
                recurse(i + 1)
                current.removeAt(current.lastIndex)
            }
        }
        recurse(0)
        return result
    }

    private companion object {
        // Fixed, not device-clock-derived: reproducible subsampling means
        // two runs given the same block set pick the same splits, which
        // makes the gate's PBO output comparable across runs/logs instead
        // of noisy from resampling alone.
        const val SPLIT_SAMPLING_SEED = 42L
    }
}
