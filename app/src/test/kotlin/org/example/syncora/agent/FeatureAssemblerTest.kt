package org.example.syncora.agent

import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.FundingSchedule
import org.example.syncora.bitget.Kline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FeatureAssemblerTest {

    private val epsilon = 1e-6f

    // ---- fixture helpers -----------------------------------------------
    //
    // Shaped like what TradingChartPipeline.klines / a KlineCacheStore dump
    // would hand back: Klines oldest-first, only the fields FeatureAssembler
    // actually reads (close) vary meaningfully - the rest are filled with
    // plausible placeholder values.

    private fun kline(startTime: Long, close: Double): Kline = Kline(
        startTime = startTime,
        open = close,
        high = close,
        low = close,
        close = close,
        baseVolume = 1.0,
        quoteVolume = close,
        usdtVolume = close,
    )

    private fun klineFixture(closes: List<Double>): List<Kline> =
        closes.mapIndexed { i, c -> kline(startTime = i * 60_000L, close = c) }

    private fun depthFixture(
        bids: List<Pair<Double, Double>>,
        asks: List<Pair<Double, Double>>,
        lastUpdateMs: Long = 0L,
    ): DepthSnapshot = DepthSnapshot(
        bids = bids.map { (price, size) -> DepthLevel(price, size) },
        asks = asks.map { (price, size) -> DepthLevel(price, size) },
        lastUpdateMs = lastUpdateMs,
        lastSeq = 1L,
    )

    // A longer, deterministic "replayed history" fixture - a simple
    // pseudo-random-looking but fully reproducible walk, standing in for a
    // KlineCacheStore dump of real bars.
    private fun longKlineFixture(bars: Int): List<Kline> {
        var price = 50_000.0
        var seed = 1234567L
        val closes = ArrayList<Double>(bars)
        repeat(bars) {
            // Deterministic linear-congruential step - same sequence every run/process.
            seed = (seed * 1103515245L + 12345L) and 0x7FFFFFFFL
            val drift = (seed % 2001 - 1000) / 100_000.0 // in [-0.01, 0.01]
            price *= (1.0 + drift)
            closes.add(price)
        }
        return klineFixture(closes)
    }

    // ---- hand-computed fixture cases ------------------------------------

    @Test
    fun `return is computed from last two closes`() {
        val assembler = FeatureAssembler()
        val klines = klineFixture(listOf(100.0, 102.0, 101.0))
        val depth = depthFixture(bids = listOf(100.0 to 1.0), asks = listOf(100.2 to 1.0))

        val out = assembler.assemble(klines, depth, nowMs = 0L)

        val expectedReturn = (101.0 - 102.0) / 102.0
        assertEquals(expectedReturn.toFloat(), out[FeatureAssembler.RETURN_INDEX], epsilon)
    }

    @Test
    fun `realized volatility matches hand-computed population stddev of returns`() {
        val assembler = FeatureAssembler(realizedVolWindow = 20)
        val klines = klineFixture(listOf(100.0, 102.0, 101.0))
        val depth = depthFixture(bids = listOf(100.0 to 1.0), asks = listOf(100.2 to 1.0))

        val out = assembler.assemble(klines, depth, nowMs = 0L)

        val r1 = (102.0 - 100.0) / 100.0
        val r2 = (101.0 - 102.0) / 102.0
        val mean = (r1 + r2) / 2.0
        val variance = ((r1 - mean) * (r1 - mean) + (r2 - mean) * (r2 - mean)) / 2.0
        val expectedVol = sqrt(variance)

        assertEquals(expectedVol.toFloat(), out[FeatureAssembler.REALIZED_VOL_INDEX], epsilon)
    }

    @Test
    fun `realized volatility only uses the trailing window`() {
        // window = 2: only the last two returns (from the last three closes) should count,
        // even though five bars (four returns) are available.
        val assembler = FeatureAssembler(realizedVolWindow = 2)
        val klines = klineFixture(listOf(100.0, 1000.0, 1.0, 100.0, 102.0, 101.0))
        val depth = depthFixture(bids = listOf(100.0 to 1.0), asks = listOf(100.2 to 1.0))

        val out = assembler.assemble(klines, depth, nowMs = 0L)

        val r1 = (102.0 - 100.0) / 100.0
        val r2 = (101.0 - 102.0) / 102.0
        val mean = (r1 + r2) / 2.0
        val variance = ((r1 - mean) * (r1 - mean) + (r2 - mean) * (r2 - mean)) / 2.0
        val expectedVol = sqrt(variance)

        assertEquals(expectedVol.toFloat(), out[FeatureAssembler.REALIZED_VOL_INDEX], epsilon)
    }

    @Test
    fun `spread is relative to mid price`() {
        val assembler = FeatureAssembler()
        val klines = klineFixture(listOf(100.0))
        val depth = depthFixture(bids = listOf(100.0 to 1.0), asks = listOf(100.2 to 1.0))

        val out = assembler.assemble(klines, depth, nowMs = 0L)

        val mid = 0.5 * (100.0 + 100.2)
        val expectedSpread = (100.2 - 100.0) / mid
        assertEquals(expectedSpread.toFloat(), out[FeatureAssembler.SPREAD_INDEX], epsilon)
    }

    @Test
    fun `spread is zero when one side of the book is empty`() {
        val assembler = FeatureAssembler()
        val klines = klineFixture(listOf(100.0))
        val depth = depthFixture(bids = emptyList(), asks = listOf(100.2 to 1.0))

        val out = assembler.assemble(klines, depth, nowMs = 0L)

        assertEquals(0.0f, out[FeatureAssembler.SPREAD_INDEX], epsilon)
    }

    @Test
    fun `order flow imbalance aggregates top N levels per side`() {
        val assembler = FeatureAssembler(depthLevelsForImbalance = 2)
        val klines = klineFixture(listOf(100.0))
        // Only the top 2 levels per side should count; the 3rd bid level (10.0) is ignored.
        val depth = depthFixture(
            bids = listOf(100.0 to 3.0, 99.9 to 1.0, 99.8 to 10.0),
            asks = listOf(100.1 to 1.0, 100.2 to 1.0, 100.3 to 10.0),
        )

        val out = assembler.assemble(klines, depth, nowMs = 0L)

        val bidSize = 3.0 + 1.0
        val askSize = 1.0 + 1.0
        val expected = (bidSize - askSize) / (bidSize + askSize)
        assertEquals(expected.toFloat(), out[FeatureAssembler.ORDER_FLOW_IMBALANCE_INDEX], epsilon)
    }

    @Test
    fun `relative basis passes through the funding rate provider`() {
        val assembler = FeatureAssembler(fundingRateProvider = { 0.00015 })
        val klines = klineFixture(listOf(100.0))
        val depth = depthFixture(bids = listOf(100.0 to 1.0), asks = listOf(100.2 to 1.0))

        val out = assembler.assemble(klines, depth, nowMs = 0L)

        assertEquals(0.00015f, out[FeatureAssembler.RELATIVE_BASIS_INDEX], epsilon)
    }

    @Test
    fun `relative basis defaults to zero when funding rate is unavailable`() {
        val assembler = FeatureAssembler() // default provider returns null
        val klines = klineFixture(listOf(100.0))
        val depth = depthFixture(bids = listOf(100.0 to 1.0), asks = listOf(100.2 to 1.0))

        val out = assembler.assemble(klines, depth, nowMs = 0L)

        assertEquals(0.0f, out[FeatureAssembler.RELATIVE_BASIS_INDEX], epsilon)
    }

    @Test
    fun `time to next funding is normalized against the 8h grid`() {
        val assembler = FeatureAssembler()
        val klines = klineFixture(listOf(100.0))
        val depth = depthFixture(bids = listOf(100.0 to 1.0), asks = listOf(100.2 to 1.0))

        // 2 hours before the 08:00 settlement -> 2h of 8h remaining -> 0.25.
        val settlement = FundingSchedule.INTERVAL_MS // the 08:00 mark, since epoch 0 is 00:00
        val nowMs = settlement - 2 * 60 * 60 * 1000L

        val out = assembler.assemble(klines, depth, nowMs)

        assertEquals(0.25f, out[FeatureAssembler.TIME_TO_FUNDING_INDEX], epsilon)
    }

    @Test
    fun `single bar produces zero return and zero volatility rather than throwing`() {
        val assembler = FeatureAssembler()
        val klines = klineFixture(listOf(100.0))
        val depth = depthFixture(bids = listOf(100.0 to 1.0), asks = listOf(100.2 to 1.0))

        val out = assembler.assemble(klines, depth, nowMs = 0L)

        assertEquals(0.0f, out[FeatureAssembler.RETURN_INDEX], epsilon)
        assertEquals(0.0f, out[FeatureAssembler.REALIZED_VOL_INDEX], epsilon)
    }

    // ---- determinism / replay check --------------------------------------

    @Test
    fun `identical input produces bit-identical output across repeated calls`() {
        val assembler = FeatureAssembler()
        val klines = longKlineFixture(200)
        val depth = depthFixture(
            bids = listOf(50_000.0 to 2.0, 49_999.0 to 1.5, 49_998.0 to 3.0),
            asks = listOf(50_001.0 to 1.0, 50_002.0 to 2.5, 50_003.0 to 1.0),
        )
        val nowMs = 12_345_678L

        val first = assembler.assemble(klines, depth, nowMs)
        repeat(50) { i ->
            val again = assembler.assemble(klines, depth, nowMs)
            assertTrue(
                "Call #$i diverged from the first call's output on identical input",
                first.contentEquals(again),
            )
        }
    }

    @Test
    fun `replaying the same growing history through two fresh instances is bit-identical`() {
        // Simulates replaying a KlineCacheStore dump bar-by-bar through the assembler,
        // once per instance, and checks the two independent replays never diverge -
        // i.e. there's no hidden state tying output to "which instance ran it before".
        val fullHistory = longKlineFixture(300)
        val depth = depthFixture(
            bids = listOf(50_000.0 to 2.0, 49_999.0 to 1.5),
            asks = listOf(50_001.0 to 1.0, 50_002.0 to 2.5),
        )

        val assemblerA = FeatureAssembler()
        val assemblerB = FeatureAssembler()

        for (barIndex in 1..fullHistory.size) {
            val windowSoFar = fullHistory.subList(0, barIndex)
            val nowMs = windowSoFar.last().startTime
            val outA = assemblerA.assemble(windowSoFar, depth, nowMs)
            val outB = assemblerB.assemble(windowSoFar, depth, nowMs)
            assertTrue("Divergence at bar $barIndex", outA.contentEquals(outB))
        }
    }

    // ---- allocation benchmark --------------------------------------------
    //
    // There's no JMH/async-profiler in this environment, so this is a
    // pragmatic JVM-heap heuristic rather than a precise allocation count:
    // warm up, force a GC, run a large number of calls (retaining only the
    // last result, so the JIT can't dead-code-eliminate the calls), force
    // another GC, and check that retained heap growth is consistent with
    // "a handful of small FloatArrays survived", not "thousands of call's
    // worth of garbage accumulated". This is a coarse signal only; the real
    // check for the target device is Phase 2's on-device benchmark.
    @Test
    fun `repeated assemble calls do not grow retained heap with call count`() {
        val assembler = FeatureAssembler()
        val history = longKlineFixture(500)
        val depth = depthFixture(
            bids = listOf(50_000.0 to 2.0, 49_999.0 to 1.5, 49_998.0 to 3.0),
            asks = listOf(50_001.0 to 1.0, 50_002.0 to 2.5, 50_003.0 to 1.0),
        )

        val runtime = Runtime.getRuntime()
        var sink: FloatArray = FloatArray(FeatureAssembler.FEATURE_WIDTH)

        fun usedHeapBytes(): Long {
            System.gc()
            Thread.sleep(20)
            return runtime.totalMemory() - runtime.freeMemory()
        }

        // Every call reuses the same full history list (as a real bar-close caller would
        // pass TradingChartPipeline.klines.value each time) so the loop itself introduces
        // no per-iteration allocation of its own - any growth measured below is attributable
        // to assemble(), not to the harness slicing a fresh list each time.
        val nowMs = history.last().startTime

        // Warm up the JIT before measuring, so steady-state behavior is what's checked.
        repeat(5_000) { sink = assembler.assemble(history, depth, nowMs) }

        val before = usedHeapBytes()
        val iterations = 200_000
        repeat(iterations) { sink = assembler.assemble(history, depth, nowMs) }
        val after = usedHeapBytes()

        // Budget: allow generous slack for JVM/GC bookkeeping noise, but a call-count-proportional
        // leak (e.g. an accidental cache keyed per call) would blow well past this on 200k iterations.
        val maxAllowedGrowthBytes = 2_000_000L
        assertTrue(
            "Retained heap grew by ${after - before} bytes over $iterations calls " +
                "(budget $maxAllowedGrowthBytes) - looks like more than the single returned FloatArray " +
                "per call is surviving",
            after - before < maxAllowedGrowthBytes,
        )
        assertTrue(sink.isNotEmpty()) // keep `sink` live so the loop isn't optimized away
    }
}
