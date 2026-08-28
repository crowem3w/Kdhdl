package org.example.syncora.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.random.Random

class ReadoutTrainerTest {

    // A deterministic "reservoir state history" fixture - same shape as
    // ReservoirEngineTest's featureSequence, just directly in x_t-space so
    // this test doesn't need a real ReservoirEngine wired up to exercise
    // ReadoutTrainer in isolation.
    private fun stateSequence(nHidden: Int, steps: Int, seed: Long): List<FloatArray> {
        val rng = Random(seed)
        return List(steps) { FloatArray(nHidden) { rng.nextFloat() * 2f - 1f } }
    }

    // ---- RLS convergence ---------------------------------------------

    @Test
    fun `RLS readout converges toward a known linear target`() {
        val nHidden = 20
        val trueWeights = FloatArray(nHidden + 1) { i -> if (i == nHidden) 0.3f else (i % 5 - 2) * 0.1f }

        val trainer = ReadoutTrainer(nHidden = nHidden, forgettingFactor = 0.999f)
        val states = stateSequence(nHidden, steps = 2000, seed = 1L)

        fun trueTarget(state: FloatArray): Float {
            var acc = trueWeights[nHidden] // bias
            for (i in state.indices) acc += trueWeights[i] * state[i]
            return acc
        }

        var earlyAbsError = 0f
        var lateAbsError = 0f
        for ((step, state) in states.withIndex()) {
            val target = trueTarget(state)
            val predicted = trainer.predict(state)[0]
            val absError = abs(target - predicted)
            if (step == 20) earlyAbsError = absError
            if (step == states.lastIndex) lateAbsError = absError
            trainer.update(state, floatArrayOf(target))
        }

        assertTrue("expected the readout to have learned something by step 20 (error=$earlyAbsError)", earlyAbsError < 5f)
        assertTrue(
            "expected the late-stage prediction error ($lateAbsError) to be much smaller than the early one ($earlyAbsError)",
            lateAbsError < earlyAbsError * 0.05f,
        )
        assertTrue("expected the readout to have converged closely to the true linear target (error=$lateAbsError)", lateAbsError < 0.05f)
    }

    @Test
    fun `predict before update matches predict after update only once the error is folded in`() {
        val nHidden = 10
        val trainer = ReadoutTrainer(nHidden = nHidden)
        val state = stateSequence(nHidden, steps = 1, seed = 2L)[0]

        val before = trainer.predict(state)[0]
        trainer.update(state, floatArrayOf(before + 1f))
        val after = trainer.predict(state)[0]

        assertTrue("expected the prediction to move toward the observed target after one update", after > before)
    }

    @Test
    fun `predict returns the trainer's own scratch buffer, not a fresh allocation`() {
        val trainer = ReadoutTrainer(nHidden = 5)
        val state = stateSequence(5, steps = 1, seed = 3L)[0]
        val out1 = trainer.predict(state)
        val out2 = trainer.predict(state)
        assertTrue("predict() should return a stable reference to internal scratch", out1 === out2)
    }

    // ---- stability / non-divergence ------------------------------------

    @Test
    fun `RLS remains stable over a long, noisy replay`() {
        val nHidden = 60
        val trainer = ReadoutTrainer(nHidden = nHidden, forgettingFactor = 0.99f)
        val rng = Random(4L)
        val states = stateSequence(nHidden, steps = 5000, seed = 5L)

        for (state in states) {
            val noisyTarget = (rng.nextFloat() * 2f - 1f) * 3f
            trainer.predict(state)
            trainer.update(state, floatArrayOf(noisyTarget))
            assertTrue("RLS diverged (non-finite W_out or P entry found)", trainer.isStable())
        }
    }

    @Test
    fun `forgetting factor of exactly 1 (growing window) also stays stable`() {
        val nHidden = 30
        val trainer = ReadoutTrainer(nHidden = nHidden, forgettingFactor = 1.0f)
        val states = stateSequence(nHidden, steps = 3000, seed = 6L)
        for (state in states) {
            trainer.update(state, floatArrayOf(0.5f))
            assertTrue(trainer.isStable())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `forgetting factor above 1 is rejected`() {
        ReadoutTrainer(nHidden = 10, forgettingFactor = 1.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `forgetting factor of 0 is rejected`() {
        ReadoutTrainer(nHidden = 10, forgettingFactor = 0f)
    }

    // ---- checkpoint round trip ------------------------------------------

    @Test
    fun `restoring a trainer from an in-memory snapshot reproduces bit-identical state`() {
        val nHidden = 15
        val original = ReadoutTrainer(nHidden = nHidden, forgettingFactor = 0.98f)
        for (state in stateSequence(nHidden, steps = 200, seed = 7L)) {
            original.update(state, floatArrayOf(state[0] * 0.5f))
        }

        val checkpoint = original.toCheckpoint()
        val restored = checkpoint.toTrainer()

        assertEquals(original.wOutSnapshot().toList(), restored.wOutSnapshot().toList())
        assertEquals(original.covarianceSnapshot().toList(), restored.covarianceSnapshot().toList())

        // And the restored trainer keeps behaving identically going forward.
        val nextState = stateSequence(nHidden, steps = 1, seed = 8L)[0]
        assertEquals(original.predict(nextState).toList(), restored.predict(nextState).toList())
    }

    @Test
    fun `FileReadoutCheckpointStore save-then-load round trip is bit-identical`() = runBlocking {
        val nHidden = 25
        val trainer = ReadoutTrainer(nHidden = nHidden, nOutputs = 2, forgettingFactor = 0.995f)
        for (state in stateSequence(nHidden, steps = 500, seed = 9L)) {
            trainer.update(state, floatArrayOf(state[0], -state[1]))
        }
        val saved = trainer.toCheckpoint()

        val tmpDir = Files.createTempDirectory("readout-checkpoint-test").toFile()
        val store = FileReadoutCheckpointStore(File(tmpDir, "checkpoint.json"))

        store.save(saved)
        val loaded = store.load()

        assertEquals(saved, loaded)
        assertEquals(saved.wOut.toList(), loaded!!.wOut.toList())
        assertEquals(saved.covariance.toList(), loaded.covariance.toList())

        tmpDir.deleteRecursively()
    }

    @Test
    fun `loading from a store with no prior checkpoint returns null`() = runBlocking {
        val tmpDir = Files.createTempDirectory("readout-checkpoint-test-empty").toFile()
        val store = FileReadoutCheckpointStore(File(tmpDir, "does-not-exist.json"))
        assertEquals(null, store.load())
        tmpDir.deleteRecursively()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `state of the wrong width is rejected`() {
        val trainer = ReadoutTrainer(nHidden = 10)
        trainer.predict(FloatArray(5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `target of the wrong width is rejected`() {
        val trainer = ReadoutTrainer(nHidden = 10, nOutputs = 1)
        trainer.update(FloatArray(10), FloatArray(2))
    }
}
