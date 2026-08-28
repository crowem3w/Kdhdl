package org.example.syncora.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.KlineBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 7a's whole exit criterion (`ESN_RRL_Agent_Task_Prompts.md`):
 * [AgentOrchestrator.LiveBarCloseSubscriber]'s event-driven subscription
 * must be demonstrably 1:1 with incoming bar-close events under a
 * simulated live stream - no duplicate events, no skipped bars, and a
 * clean handoff from backtest mode (no double-processing a bar already
 * replayed, no dropping the first genuinely new live tick).
 *
 * The Phase 1-5 inference chain, order emission, checkpointing, and UI are
 * all explicitly out of scope here (Prompts 7b-7g) - this file only
 * exercises the detector itself.
 */
class LiveBarCloseSubscriberTest {

    private val barIntervalMs = 60_000L

    /** Deterministic fixture bars - same LCG-walk approach used elsewhere in Phase 5/6's tests. */
    private fun fixtureKlines(bars: Int, seed: Long): List<Kline> {
        var price = 50_000.0
        var lcgSeed = seed
        val out = ArrayList<Kline>(bars)
        repeat(bars) { i ->
            lcgSeed = (lcgSeed * 1103515245L + 12345L) and 0x7FFFFFFFL
            val drift = (lcgSeed % 2001 - 1000) / 100_000.0 // in [-0.01, 0.01]
            price *= (1.0 + drift)
            out.add(
                Kline(
                    startTime = i * barIntervalMs,
                    open = price,
                    high = price * 1.0005,
                    low = price * 0.9995,
                    close = price,
                    baseVolume = 1.0,
                    quoteVolume = price,
                    usdtVolume = price,
                ),
            )
        }
        return out
    }

    private fun fixedDepth(): DepthSnapshot = DepthSnapshot(
        bids = listOf(DepthLevel(49_999.0, 2.0)),
        asks = listOf(DepthLevel(50_001.0, 2.0)),
        lastUpdateMs = 0L,
        lastSeq = 1L,
    )

    /**
     * Replays [bars] through a real [KlineBuffer] the way
     * `TradingChartPipeline` would - several in-place ticks per bar
     * (converging toward the bar's final values) before the next bar
     * appends - capturing the *full snapshot* after every single tick,
     * exactly what `TradingChartPipeline.klines`'s `StateFlow` emits.
     */
    private fun simulateLiveSnapshots(bars: List<Kline>, ticksPerBar: Int = 3): List<List<Kline>> {
        val buffer = KlineBuffer(capacity = bars.size + 1)
        val snapshots = ArrayList<List<Kline>>(bars.size * ticksPerBar)
        for (bar in bars) {
            for (tick in 0 until ticksPerBar) {
                val fraction = (tick + 1).toDouble() / ticksPerBar
                val partialClose = bar.open + (bar.close - bar.open) * fraction
                val tickKline = if (tick == ticksPerBar - 1) {
                    bar // final tick for this bar must land on the bar's real, final values
                } else {
                    bar.copy(close = partialClose, high = maxOf(bar.open, partialClose), low = minOf(bar.open, partialClose))
                }
                snapshots.add(buffer.applyUpdates(listOf(tickKline)))
            }
        }
        return snapshots
    }

    /** Feeds [snapshots] through [subscriber] as a [kotlinx.coroutines.flow.Flow], with real (virtual-time) gaps between emissions. */
    private suspend fun AgentOrchestrator.LiveBarCloseSubscriber.collectAll(
        snapshots: List<List<Kline>>,
        emitted: MutableList<AgentOrchestrator.LiveBarClose>,
    ) {
        val stream = flow {
            for (s in snapshots) {
                delay(37L) // a real (if virtual, under runTest) timing gap between ticks
                emit(s)
            }
        }
        collect(klines = stream, depthAt = ::fixedDepth) { close -> emitted.add(close) }
    }

    @Test
    fun `fires exactly once per bar-close, in order, with no duplicates or skips`() = runTest {
        val bars = fixtureKlines(bars = 40, seed = 111L)
        val snapshots = simulateLiveSnapshots(bars, ticksPerBar = 4)

        val subscriber = AgentOrchestrator.LiveBarCloseSubscriber()
        val emitted = mutableListOf<AgentOrchestrator.LiveBarClose>()
        subscriber.collectAll(snapshots, emitted)

        // Every bar except the last one (still "forming" when the simulated
        // stream ends) must close exactly once.
        val expectedClosedStartTimes = bars.dropLast(1).map { it.startTime }
        val actualClosedStartTimes = emitted.map { it.kline.startTime }

        assertEquals("expected exactly one close event per closed bar", expectedClosedStartTimes.size, emitted.size)
        assertEquals("closed bars must fire in chronological order with no duplicates or gaps", expectedClosedStartTimes, actualClosedStartTimes)
        assertEquals("no duplicate startTimes should ever be emitted", actualClosedStartTimes.toSet().size, actualClosedStartTimes.size)

        // barIndex is a dense 0..n-1 sequence in emission order.
        assertEquals((0 until emitted.size).toList(), emitted.map { it.barIndex })

        // Each event's klinesSoFar should end exactly on the bar that closed.
        emitted.forEachIndexed { i, close ->
            assertEquals(bars[i], close.klinesSoFar.last())
            assertEquals(i + 1, close.klinesSoFar.size)
            assertEquals(bars[i].startTime, close.kline.startTime)
        }

        assertEquals(bars[bars.size - 2].startTime, subscriber.lastEmittedBarStartTime)
    }

    @Test
    fun `hands off from backtest mode without double-processing or dropping the first live tick`() = runTest {
        val bars = fixtureKlines(bars = 30, seed = 222L)
        val backtestCutoff = 10 // bars[0..backtestCutoff] were already fully processed offline

        // The live pipeline cold-starts with a REST snapshot that already
        // contains everything up through the backtest cutoff (overlapping
        // history is normal - TradingChartPipeline re-primes from the
        // exchange independently of what the backtest already replayed).
        val primingBatch = bars.subList(0, backtestCutoff + 1)
        val primingBuffer = KlineBuffer(capacity = bars.size + 1)
        val primingSnapshot = primingBuffer.applyUpdates(primingBatch)

        val remainingSnapshots = simulateLiveSnapshots(bars.subList(backtestCutoff + 1, bars.size), ticksPerBar = 3)
            .map { tail ->
                // Full-buffer semantics: every later snapshot still carries the fully-closed primed prefix in front.
                primingBatch + tail
            }

        val allSnapshots = listOf(primingSnapshot) + remainingSnapshots

        val subscriber = AgentOrchestrator.LiveBarCloseSubscriber(resumeAfterStartTime = bars[backtestCutoff].startTime)
        val emitted = mutableListOf<AgentOrchestrator.LiveBarClose>()
        subscriber.collectAll(allSnapshots, emitted)

        val actualClosedStartTimes = emitted.map { it.kline.startTime }

        // Bars 0..backtestCutoff must NOT be re-emitted (already processed by the backtest).
        val alreadyProcessed = bars.subList(0, backtestCutoff + 1).map { it.startTime }.toSet()
        assertTrue(
            "no bar already handled by the backtest should be re-emitted as a live close",
            actualClosedStartTimes.none { it in alreadyProcessed },
        )

        // Every bar strictly after the cutoff, up to (but not including) the
        // still-forming final bar, must close exactly once - including the
        // very first new one (backtestCutoff + 1), which must not be dropped.
        val expectedNewCloses = bars.subList(backtestCutoff + 1, bars.size - 1).map { it.startTime }
        assertEquals(expectedNewCloses, actualClosedStartTimes)
        assertEquals(bars[backtestCutoff + 1].startTime, actualClosedStartTimes.first())
    }

    @Test
    fun `no bar close is lost even when intermediate snapshots are conflated away`() = runTest {
        // StateFlow only ever hands a slow collector the *latest* value, so
        // intermediate emissions can vanish entirely. Detection must not
        // depend on seeing every emission - only on eventually seeing each
        // bar's final, closed state before it scrolls out of the buffer.
        val bars = fixtureKlines(bars = 25, seed = 333L)
        val fullSnapshots = simulateLiveSnapshots(bars, ticksPerBar = 5)
        val conflated = fullSnapshots.filterIndexed { i, _ -> i % 3 == 0 } // drop 2 out of every 3 emissions

        val subscriber = AgentOrchestrator.LiveBarCloseSubscriber()
        val emitted = mutableListOf<AgentOrchestrator.LiveBarClose>()
        subscriber.collectAll(conflated, emitted)

        val expectedClosedStartTimes = bars.dropLast(1).map { it.startTime }
        val actualClosedStartTimes = emitted.map { it.kline.startTime }
        assertEquals(expectedClosedStartTimes, actualClosedStartTimes)
    }

    @Test
    fun `a fresh subscriber with no snapshots yet has no last-emitted bar`() {
        val subscriber = AgentOrchestrator.LiveBarCloseSubscriber()
        assertNull(subscriber.lastEmittedBarStartTime)
    }
}
