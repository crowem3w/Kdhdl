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

    private fun newEmitter(
        account: StubPaperTradingAccount,
        maxSize: Double = 1.0,
        leverage: Int = 5,
        maxNotionalUsdt: Double = 1_000_000.0, // generous - unrelated tests shouldn't hit this cap
        price: Double = 50_000.0, // matches StubPaperTradingAccount's own markPrice/entryPrice convention
    ): PositionOrderEmitter =
        PositionOrderEmitter(
            orderSink = account,
            currentPosition = account::snapshot,
            maxPositionSizeBaseCoin = maxSize,
            maxNotionalUsdt = maxNotionalUsdt,
            referencePrice = { price },
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

    // ---- Prompt 8a: hard position/notional caps (Phase 7 - `ESN_RRL_Agent_Task_Prompts.md`) ----
    // maxPositionSizeBaseCoin (cap #1, base-coin) is already exercised by every case above,
    // since f_t is a fraction of it by construction. These cases exercise the second,
    // independent ceiling: maxNotionalUsdt (cap #2, USDT), which can bind even when f_t and
    // the base-coin size are both well within maxPositionSizeBaseCoin - purely because
    // referencePrice moved. Boundary cases per Prompt 8a: exactly-at-cap, just-over-cap,
    // far-over-cap.

    @Test
    fun `notional cap exactly at the boundary passes through unclipped`() {
        val account = StubPaperTradingAccount()
        // f_t=1.0 * maxSize=1.0 = 1.0 base-coin @ price 50_000 = exactly 50_000 USDT notional.
        val emitter = newEmitter(account, maxSize = 1.0, price = 50_000.0, maxNotionalUsdt = 50_000.0)

        emitter.onTargetPosition(1.0f)
        assertEquals(1.0, account.snapshot()!!.total, 1e-6)
    }

    @Test
    fun `notional cap just over the boundary clips size down to the cap`() {
        val account = StubPaperTradingAccount()
        // Same as above, but price ticked up just enough that the un-clipped 1.0 base-coin
        // size would be 50_100 USDT notional - just over a 50_000 cap.
        val emitter = newEmitter(account, maxSize = 1.0, price = 50_100.0, maxNotionalUsdt = 50_000.0)

        emitter.onTargetPosition(1.0f)
        // 50_000/50_100 is non-terminating, so the value necessarily loses some precision
        // going through formatSize's %.8f round-trip; 1e-8 matches that real resolution
        // (same order as sizeEpsilonBaseCoin's default) rather than the tighter 1e-9 used
        // for the other, exactly-representable boundary cases in this file.
        assertEquals(50_000.0 / 50_100.0, account.snapshot()!!.total, 1e-8)
    }

    @Test
    fun `notional cap far over the boundary still clips down to exactly the cap, not partway`() {
        val account = StubPaperTradingAccount()
        // A price spike (e.g. 10x) with an otherwise well-behaved, in-range f_t: the
        // base-coin cap alone would let this through at maxSize; the notional cap must
        // still catch it and clip all the way down to what maxNotionalUsdt actually buys
        // at this price - "far over" is not treated any more leniently than "just over".
        val emitter = newEmitter(account, maxSize = 1.0, price = 500_000.0, maxNotionalUsdt = 50_000.0)

        emitter.onTargetPosition(1.0f)
        assertEquals(0.1, account.snapshot()!!.total, 1e-9) // 50_000 / 500_000
        assertEquals(0.1 * 500_000.0, account.snapshot()!!.total * 500_000.0, 1e-6) // resulting notional == the cap, not below it
    }

    @Test
    fun `whichever of the two caps is tighter wins`() {
        // Cap #1 (base-coin) tighter: a tiny maxPositionSizeBaseCoin binds well before the
        // generous notional cap would.
        val baseCoinBinds = StubPaperTradingAccount()
        newEmitter(baseCoinBinds, maxSize = 0.2, price = 50_000.0, maxNotionalUsdt = 1_000_000.0)
            .onTargetPosition(1.0f)
        assertEquals(0.2, baseCoinBinds.snapshot()!!.total, 1e-9)

        // Cap #2 (notional) tighter: a generous maxPositionSizeBaseCoin would allow far
        // more size than a modest USDT notional cap permits at this price.
        val notionalBinds = StubPaperTradingAccount()
        newEmitter(notionalBinds, maxSize = 10.0, price = 50_000.0, maxNotionalUsdt = 25_000.0)
            .onTargetPosition(1.0f)
        assertEquals(0.5, notionalBinds.snapshot()!!.total, 1e-9) // 25_000 / 50_000, not 10.0
    }

    @Test
    fun `an existing position is shrunk to the notional cap once price moves it over, even with an unchanged f_t`() {
        val account = StubPaperTradingAccount()
        var price = 50_000.0
        val emitter = PositionOrderEmitter(
            orderSink = account,
            currentPosition = account::snapshot,
            maxPositionSizeBaseCoin = 1.0,
            maxNotionalUsdt = 50_000.0,
            referencePrice = { price },
        )

        emitter.onTargetPosition(1.0f) // opens LONG 1.0 @ 50_000 = exactly at the notional cap
        assertEquals(1.0, account.snapshot()!!.total, 1e-9)

        price = 100_000.0 // price doubles between bar closes; f_t is unchanged
        account.calls.clear()
        emitter.onTargetPosition(1.0f) // same f_t, but now the notional cap re-evaluates against the new price

        // The existing 1.0-size position is now 100_000 USDT notional - over the 50_000 cap -
        // so this call must shrink it back down to what the cap allows at the new price,
        // purely because a hard cap is being re-checked every call, not because f_t moved.
        assertEquals(listOf("close", "open"), account.calls.map { it.type })
        assertEquals(0.5, account.snapshot()!!.total, 1e-9) // 50_000 / 100_000
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

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive maxNotionalUsdt is rejected at construction`() {
        newEmitter(StubPaperTradingAccount(), maxNotionalUsdt = 0.0)
    }

    @Test
    fun `a non-finite or non-positive referencePrice falls back to the base-coin cap alone, never throws`() {
        // Prompt 8a scope is finite-but-excessive f_t/price values; a malformed price feed
        // (NaN, zero, negative) is explicitly Prompt 8d's failure-mode territory. This test
        // only pins down that, until 8d hardens it further, this cap layer degrades to
        // "base-coin cap only" rather than crashing or silently allowing an uncapped order.
        val nanPriceAccount = StubPaperTradingAccount()
        PositionOrderEmitter(
            orderSink = nanPriceAccount,
            currentPosition = nanPriceAccount::snapshot,
            maxPositionSizeBaseCoin = 0.4,
            maxNotionalUsdt = 1.0, // deliberately tiny - would clip hard if it were applied
            referencePrice = { Double.NaN },
        ).onTargetPosition(1.0f)
        assertEquals(0.4, nanPriceAccount.snapshot()!!.total, 1e-9) // base-coin cap, notional cap skipped

        val zeroPriceAccount = StubPaperTradingAccount()
        PositionOrderEmitter(
            orderSink = zeroPriceAccount,
            currentPosition = zeroPriceAccount::snapshot,
            maxPositionSizeBaseCoin = 0.4,
            maxNotionalUsdt = 1.0,
            referencePrice = { 0.0 },
        ).onTargetPosition(1.0f)
        assertEquals(0.4, zeroPriceAccount.snapshot()!!.total, 1e-9)
    }
}
