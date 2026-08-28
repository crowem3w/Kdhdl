package org.example.syncora.agent

import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PositionSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 7c's whole exit criterion (`ESN_RRL_Agent_Task_Prompts.md`): a
 * scripted sequence of target positions must produce exactly the expected
 * paper orders - no extraneous ones, none missing - for the three named
 * cases (no-change, flip-direction, flatten-to-zero), against a stub
 * standing in for the app's existing [PaperOrderSink]/`LocalPaperTradingStore`
 * order path (see [PositionOrderEmitter]'s class doc).
 */
class PositionOrderEmitterTest {

    /**
     * A tiny in-memory stand-in for [PaperOrderSink] +
     * `LocalPaperTradingStore`/`PaperTradingRepository`'s current-position
     * view combined: it both receives orders (recording each one for
     * assertions) and tracks the resulting net position the same way the
     * real repository would (opening adds to a side, closing removes it),
     * so [PositionOrderEmitter.onTargetPosition] can be called repeatedly
     * against a coherent, evolving account state - exactly the "stub
     * PaperTradePanel/LocalPaperTradingStore" the prompt calls for.
     */
    private class StubPaperTradingAccount : PaperOrderSink {
        data class Call(val type: String, val side: PositionSide, val size: Double, val leverage: Int)

        val calls = mutableListOf<Call>()
        private var position: PaperPosition? = null

        override fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int) {
            val size = sizeInBaseCoin.toDouble()
            calls.add(Call("open", side, size, leverage))
            val existing = position
            position = if (existing != null && existing.side == side) {
                existing.copy(total = existing.total + size, leverage = leverage)
            } else {
                PaperPosition(
                    symbol = "BTCUSDT",
                    side = side,
                    total = size,
                    available = size,
                    entryPrice = 50_000.0,
                    markPrice = 50_000.0,
                    leverage = leverage,
                    marginSize = size * 50_000.0 / leverage,
                    unrealizedPnl = 0.0,
                )
            }
        }

        override fun closePosition(position: PaperPosition) {
            calls.add(Call("close", position.side, this.position?.total ?: 0.0, this.position?.leverage ?: 0))
            this.position = null
        }

        fun snapshot(): PaperPosition? = position
    }

    private fun newEmitter(account: StubPaperTradingAccount, maxSize: Double = 1.0, leverage: Int = 5): PositionOrderEmitter =
        PositionOrderEmitter(
            orderSink = account,
            currentPosition = account::snapshot,
            maxPositionSizeBaseCoin = maxSize,
            leverage = leverage,
        )

    // ---- Case 1: no-change ----

    @Test
    fun `no-change case emits nothing for a repeated identical target`() {
        val account = StubPaperTradingAccount()
        val emitter = newEmitter(account)

        emitter.onTargetPosition(0.4f)
        assertEquals(listOf("open"), account.calls.map { it.type })
        assertEquals(PositionSide.LONG, account.snapshot()?.side)
        assertEquals(0.4, account.snapshot()!!.total, 1e-6)

        account.calls.clear()
        emitter.onTargetPosition(0.4f) // identical f_t again
        assertTrue("expected no calls for a repeated identical target, got ${account.calls}", account.calls.isEmpty())

        // A freshly flat start with a ~zero target should also be a no-op (never opens a zero-size position).
        val flatAccount = StubPaperTradingAccount()
        val flatEmitter = newEmitter(flatAccount)
        flatEmitter.onTargetPosition(0f)
        assertTrue(flatAccount.calls.isEmpty())
    }

    // ---- Case 2: flip-direction ----

    @Test
    fun `flip-direction case closes the old side and opens the new one, never merged`() {
        val account = StubPaperTradingAccount()
        val emitter = newEmitter(account)

        emitter.onTargetPosition(0.6f) // open LONG
        account.calls.clear()

        emitter.onTargetPosition(-0.3f) // flip to SHORT
        assertEquals(
            listOf("close" to PositionSide.LONG, "open" to PositionSide.SHORT),
            account.calls.map { it.type to it.side },
        )
        assertEquals(PositionSide.SHORT, account.snapshot()?.side)
        assertEquals(0.3, account.snapshot()!!.total, 1e-6)
    }

    // ---- Case 3: flatten-to-zero ----

    @Test
    fun `flatten-to-zero case only closes, never re-opens`() {
        val account = StubPaperTradingAccount()
        val emitter = newEmitter(account)

        emitter.onTargetPosition(-0.7f) // open SHORT
        account.calls.clear()

        emitter.onTargetPosition(0f) // flatten
        assertEquals(listOf("close"), account.calls.map { it.type })
        assertEquals(null, account.snapshot())
    }

    // ---- Supporting cases: genuine same-side size changes are neither dropped nor coalesced incorrectly ----

    @Test
    fun `same-side size increase adds on just the delta, not the full new size`() {
        val account = StubPaperTradingAccount()
        val emitter = newEmitter(account)

        emitter.onTargetPosition(0.2f) // open LONG 0.2
        account.calls.clear()

        emitter.onTargetPosition(0.5f) // grow to LONG 0.5
        assertEquals(listOf("open"), account.calls.map { it.type })
        assertEquals(0.3, account.calls.single().size, 1e-6) // delta only, not 0.5
        assertEquals(0.5, account.snapshot()!!.total, 1e-6)
    }

    @Test
    fun `same-side size decrease closes and reopens at the smaller size`() {
        val account = StubPaperTradingAccount()
        val emitter = newEmitter(account)

        emitter.onTargetPosition(0.8f) // open LONG 0.8
        account.calls.clear()

        emitter.onTargetPosition(0.3f) // shrink to LONG 0.3
        assertEquals(listOf("close", "open"), account.calls.map { it.type })
        assertEquals(PositionSide.LONG, account.calls[1].side)
        assertEquals(0.3, account.calls[1].size, 1e-6)
        assertEquals(0.3, account.snapshot()!!.total, 1e-6)
    }

    @Test
    fun `a sequence of genuinely different targets never drops or coalesces a change`() {
        val account = StubPaperTradingAccount()
        val emitter = newEmitter(account, maxSize = 2.0)

        emitter.onTargetPosition(0.5f) // open LONG 1.0
        emitter.onTargetPosition(0.5f) // unchanged - no-op
        emitter.onTargetPosition(0.9f) // grow LONG to 1.8
        emitter.onTargetPosition(0f) // flatten
        emitter.onTargetPosition(-0.25f) // open SHORT 0.5

        assertEquals(
            listOf("open", "open", "close", "open"),
            account.calls.map { it.type },
        )
        assertEquals(PositionSide.SHORT, account.snapshot()?.side)
        assertEquals(0.5, account.snapshot()!!.total, 1e-6)
    }

    // ---- Input validation ----

    @Test(expected = IllegalArgumentException::class)
    fun `targetPosition outside -1 to 1 range is rejected`() {
        newEmitter(StubPaperTradingAccount()).onTargetPosition(1.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive maxPositionSizeBaseCoin is rejected at construction`() {
        newEmitter(StubPaperTradingAccount(), maxSize = 0.0)
    }
}
