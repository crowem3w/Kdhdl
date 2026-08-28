package org.example.syncora.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** Direct save/load coverage for [FileAgentCheckpointStore], independent of [AgentLiveSession]'s lifecycle wiring. */
class AgentCheckpointStoreTest {

    private fun randomCheckpoint(seed: Long): AgentCheckpoint {
        val rng = Random(seed)
        val nHidden = 40
        return AgentCheckpoint(
            savedAtMs = 1_700_000_000_000L,
            reservoirState = FloatArray(nHidden) { rng.nextFloat() * 2f - 1f },
            readout = ReadoutCheckpoint(
                nHidden = nHidden,
                nOutputs = 1,
                includeBias = true,
                forgettingFactor = 0.995f,
                wOut = FloatArray(nHidden + 1) { rng.nextFloat() * 2f - 1f },
                covariance = FloatArray((nHidden + 1) * (nHidden + 1)) { rng.nextFloat() },
            ),
            policyWeights = FloatArray(nHidden + 1 + 5 + 1) { rng.nextFloat() * 2f - 1f },
            policyNHidden = nHidden,
            policyNBack = 5,
        )
    }

    @Test
    fun `save-then-load round trip is bit-identical`() = runBlocking<Unit> {
        val checkpoint = randomCheckpoint(seed = 11L)
        val tmpDir = Files.createTempDirectory("agent-checkpoint-store-test").toFile()
        val store = FileAgentCheckpointStore(File(tmpDir, "checkpoint.json"))

        store.save(checkpoint)
        val loaded = store.load()

        assertEquals(checkpoint, loaded)
        assertEquals(checkpoint.reservoirState.toList(), loaded!!.reservoirState.toList())
        assertEquals(checkpoint.readout.wOut.toList(), loaded.readout.wOut.toList())
        assertEquals(checkpoint.readout.covariance.toList(), loaded.readout.covariance.toList())
        assertEquals(checkpoint.policyWeights.toList(), loaded.policyWeights.toList())

        tmpDir.deleteRecursively()
    }

    @Test
    fun `loading from a store with no prior checkpoint returns null`() = runBlocking<Unit> {
        val tmpDir = Files.createTempDirectory("agent-checkpoint-store-empty-test").toFile()
        val store = FileAgentCheckpointStore(File(tmpDir, "does-not-exist.json"))
        assertNull(store.load())
        tmpDir.deleteRecursively()
    }

    @Test
    fun `loading a corrupt checkpoint file returns null rather than throwing`() = runBlocking<Unit> {
        val tmpDir = Files.createTempDirectory("agent-checkpoint-store-corrupt-test").toFile()
        val file = File(tmpDir, "checkpoint.json")
        file.writeText("{ not valid json ")
        val store = FileAgentCheckpointStore(file)
        assertNull(store.load())
        tmpDir.deleteRecursively()
    }

    @Test
    fun `a second save overwrites the first`() = runBlocking<Unit> {
        val tmpDir = Files.createTempDirectory("agent-checkpoint-store-overwrite-test").toFile()
        val store = FileAgentCheckpointStore(File(tmpDir, "checkpoint.json"))

        store.save(randomCheckpoint(seed = 1L))
        val second = randomCheckpoint(seed = 2L)
        store.save(second)

        assertEquals(second, store.load())
        tmpDir.deleteRecursively()
    }
}
