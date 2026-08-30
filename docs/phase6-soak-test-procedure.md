# Phase 6 soak test procedure (Prompt 7g)

**Status:** operational runbook, not part of `docs/agent-design-contract.md`'s binding
contract. This documents how to actually run the multi-week, on-device soak Prompt
7g calls for, using the reusable driver (`AgentSoakHarness`) and cross-check tooling
(`AgentSoakCrossCheck`) added in this phase - see
`app/src/test/kotlin/org/example/syncora/agent/AgentSoakTest.kt` for the fast,
CI-verified stand-in that exercises the identical wiring in seconds instead of weeks.

## Why a real run still matters even though `AgentSoakTest` passes

`AgentSoakTest` proves the wiring is correct against synthetic bars replayed in
milliseconds. It cannot prove the things that only show up over real wall-clock
time on real hardware: the Android process actually getting backgrounded/killed by
the OS under memory pressure, `TradingChartPipeline`'s live WebSocket reconnecting
after a real network drop, disk-write latency on the MT6765G target device, or a
funding rate that actually flips sign on Bitget's own schedule rather than a
fixture's. Prompt 7g's exit criterion is about the real thing; the fast test is a
regression guard for the parts of it that *are* deterministic, not a substitute for
it.

## Running it

1. Build a debug entry point that constructs `AgentSoakHarness` against the
   production wiring: the real `FeatureAssembler`, `ReservoirWeights` (loaded from
   whatever fixed seed/config the app ships), a real `RewardEngine` factory, the
   real `FileAgentCheckpointStore(context, checkpointKey)`, and a real
   `PositionOrderEmitter` wired to `PaperTradingRepository`/`LocalPaperTradingStore`
   via a small `PaperOrderSink` adapter (see
   `PositionOrderEmitter`'s own "Sizing convention" doc for what that adapter needs
   to expose).
2. Drive it from `AgentOrchestrator.LiveBarCloseSubscriber` against the app's real
   `TradingChartPipeline.klines`/`DepthMatrix.snapshot()`, not a canned `bars` list -
   `AgentSoakHarness.run`'s `beforeBar`/`afterBar` hooks exist so this can happen
   without changing the harness itself.
3. Leave the app running, normally, for several weeks (multiple 8h funding
   cycles - `FundingSchedule.INTERVAL_MS`). Let the device sleep, background the
   app, and restart it the way a real user would - do not artificially keep it
   foregrounded. Every such cycle is exactly what `AgentLiveSession.stop`/
   `Companion.start` (Prompts 7d/7e) exist to survive.
4. Monitor, throughout the run, for exactly the three things Prompt 7g names:
   - **Crashes** - any unhandled exception/ANR in the agent stack. Android's own
     crash reporting is the source of truth here; nothing in this run should ever
     reach `AgentSoakHarness.SoakCrashEvent`'s territory in production, since that
     path exists only to make a *test* surface a crash without aborting the run.
   - **Missed bar ticks** - cross-reference `AgentStatusLogPanel`'s visible log
     (Prompt 7f) against `TradingChartPipeline`'s own kline history for the same
     window; every closed bar should have exactly one decision-log row.
   - **Checkpoint corruption** - on every cold start, confirm (via a debug log line
     or the status panel) whether `AgentCheckpointStore.restoreOrFreshOrchestrator`
     restored a checkpoint or fell back fresh. A fallback mid-soak (as opposed to
     the very first run) means the checkpoint didn't round-trip and needs
     investigating - it should never happen if Prompts 7d/7e's own tests pass.

## Hand cross-check, after the run

Pull the decision log (Prompt 7f's stream, or the underlying
`AgentOrchestrator.DecisionLog`s if a debug build persisted them) and a
`LocalPaperTradingStore` dump from a few points across the soak, spread across
different weeks and at least one point shortly after each background/restart the
device actually went through. For each sampled point, use
`AgentSoakCrossCheck.crossCheck` exactly as `AgentSoakTest` does:

- `expectedPosition`/`recordedPosition` should agree closely (sub-satoshi, modulo
  `PositionOrderEmitter.formatSize`'s 8-decimal rounding).
- `expectedFundingCaptured`/`recordedFundingCaptured` should agree within a small
  fraction of a funding interval's accrual - see `RewardEngine`'s own doc for why
  continuous accrual and discrete settlement are expected to reconcile.
- `expectedNetPnlSinceWindowStart`/`recordedNetPnlSinceWindowStart` should agree
  within the modeled spread/fee's own rounding, *except* immediately after a
  restart - see `AgentSoakTest`'s own comment on why `AgentCheckpoint` not
  persisting `PolicyEngine`'s feedback history makes a restart's very first bar's
  reward unreliable for this specific comparison, even though that bar's
  *position* (and everything from the second post-restart bar onward) remains
  fully checkable. Anchor each post-restart comparison window's baseline the same
  way `AgentSoakTest` does: from a ledger-only snapshot taken right after that
  first bar, not from the run's very start.

Phase 6 is complete only once a real run like this has gone the full multi-week
window with zero crashes, zero missed ticks, and zero checkpoint corruption, and
every sampled cross-check point matches within the tolerances above.
