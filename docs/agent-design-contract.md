# Agent Design Contract

**Status:** binding. Phases 1–8 of `ESN_RRL_Agent_Implementation_Plan.md` must not
contradict this document. If a later phase needs to deviate from it, this file is
updated first, in its own change, before the phase that needs the deviation.

**Scope:** this is not a rewrite of the app's design doc. It is the reconciliation
of that doc's §5 (risk guardrails) and §7 (funding handling) — as they are actually
implemented in `RiskSettingsStore`, `StopLossGuard`, `StopLossOrder`, and
`FundingSchedule`/`PaperTradingRepository.settleFunding` today — into the three
things the RRL agent must agree with before its first line of code is written: the
reward it optimises, who is responsible for risk, and which way funding sign goes.

---

## 1. Reward definition

The agent optimises the per-bar reward from Borrageiro, Firoozye & Barucca (2022),
eq. 8, extended with a funding term:

```
r_t = Δp_t · f_{t-1} − δ_t · |Δf_t| − κ_t · f_t
```

| Symbol | Meaning | Source |
|---|---|---|
| `Δp_t` | change in reference (mid) price, `0.5·(bid_t+ask_t) − 0.5·(bid_{t-1}+ask_{t-1})` | `TradingChartPipeline` / `DepthMatrix` |
| `f_{t-1}` | previous bar's position, bounded `[-1, 1]`, `+1` = max long, `-1` = max short | `PolicyEngine` (Phase 5) |
| `δ_t` | execution cost for a price taker, `0.5·(ask_t − bid_t)` | `DepthMatrix.snapshot()` |
| `Δf_t` | change in position this bar, `f_t − f_{t-1}` | `PolicyEngine` |
| `κ_t` | funding cost this bar, defined in §3 below | `FundingSchedule` + funding rate |
| `f_t` | current bar's position | `PolicyEngine` |

`Δp_t · f_{t-1}` is mark-to-market P&L on the position already held going into the
bar. `δ_t·|Δf_t|` is the spread-crossing cost, charged only when the position
actually changes. `κ_t·f_t` is the funding P&L for holding position `f_t` through
this bar; it applies on every bar (not just settlement bars) as an accrual, and its
sign convention is fixed in §3 so it agrees exactly with what
`PaperTradingRepository.settleFunding` actually charges the paper/live account.

This reward, not raw P&L and not a proxy target, is what `RewardEngine` (Phase 4)
computes and what `PolicyEngine` (Phase 5) is trained against via the differential
Sharpe ratio `dsr_t` — the utility signal, not the reward itself, is what the policy
performs gradient ascent on. `RewardEngine` must reproduce `r_t` above exactly
before the differential Sharpe layer is trusted; there is no separate "simplified"
reward used anywhere in Phases 1–8.

`δ_t` and exchange fees are two different costs and both apply: `δ_t` is the
modeled bid/ask spread cost from crossing the book, and exchange taker/maker fees
are a separate, additive term sourced the same way `PaperTradingRepository`
already sources them for paper fills. Phase 4 must not conflate the two or drop
either one.

---

## 2. Responsibility boundary: learned policy vs. exchange-side stop

This is a strict layering, not a shared responsibility:

- **The exchange-side stop (`RiskSettingsStore.stopLossPercent`, placed and
  maintained by `StopLossGuard` as a `StopLossOrder`) is the floor.** It is a
  dead-man's switch: it is placed once on Bitget's own book and is enforced by
  Bitget's matching engine, independent of whether this app's process, its
  foreground service, or the agent's policy are alive at all. It exists precisely
  because the app *will* die sometimes (OS kill, network loss, crash, phone off)
  and the position must still be bounded when that happens.
- **The RRL policy's own risk view (its learned position sizing, its aversion to
  churn via `δ_t|Δf_t|`, its funding-awareness via `κ_t f_t`) is a second,
  independent layer sitting *above* that floor, not a replacement for it.** The
  policy is free to flatten early, size down, or avoid a position entirely based
  on what it has learned. It is never permitted to be the sole line of defense —
  concretely:
  - The policy has no code path that disables, widens, cancels, or otherwise
    weakens `StopLossGuard`'s resting stop. Nothing in Phases 5–9 wires
    `PolicyEngine` output into `RiskSettingsStore` or into `StopLossGuard`'s
    guard loop.
  - A `StopLossGuard`-placed stop being present is not conditioned on the agent
    being enabled, healthy, or even running. Guardrail hardening (Phase 7) is
    additive on top of this exchange stop, not a substitute built to work around
    a missing one.
  - If the policy and the exchange stop disagree (e.g. the policy would hold
    through a move the stop would close), the exchange stop wins. The policy's
    job is to make that disagreement rare and, ideally, to exit before the stop
    is ever tested — not to override it.
- Phase 7's kill switch and hard position/notional caps are a *third*, orchestrator-level
  layer, independent of both of the above, per the same principle: a bug in one
  layer must not be able to remove the other two.

---

## 3. Funding sign convention

Single source of truth, matching what `PaperTradingRepository.settleFunding`
already charges real (paper) balances:

> **A positive funding rate is a cost to a long position and a benefit to a short
> position. A negative funding rate is a benefit to a long position and a cost to
> a short position.**

Concretely, for a position of signed size `f_t` (long positive, short negative)
and notional `N_t`, with the exchange's published `fundingRate` at settlement:

```
funding_amount_t = N_t · fundingRate_t · sign(f_t)
```

`funding_amount_t > 0` means the position **pays** (wallet/equity decreases);
`funding_amount_t < 0` means the position **receives** (wallet/equity increases).
This is exactly `PaperTradingRepository.settleFunding`'s
`amount = notional * rate * direction` with `direction = +1` for `LONG`, `-1` for
`SHORT`.

In the reward formula in §1, `κ_t` is defined so that `κ_t = fundingRate_t` and the
reward's `−κ_t·f_t` term reproduces this convention: for `f_t > 0` (long) and
`fundingRate_t > 0`, the term is negative (a cost), matching the paper's own
eq. 8 framing. No engine in Phases 1–8 may use a `κ_t` with the opposite sign, and
no engine may recompute funding P&L via a different formula than the one
`PaperTradingRepository` uses for the paper account — Phase 4's fixture tests
(including its funding-crossing case) must assert against this exact formula.

`FundingSchedule` settlement times (00:00 / 08:00 / 16:00 UTC, every 8h) are the
only times a *settled* funding amount is realized; the per-bar `κ_t·f_t` accrual
in §1 is an intra-period mark of the same quantity so the reward signal isn't a
zero–eight-hours-later step function, but the accrual must sum, over any full
funding interval, to the same settlement amount `settleFunding` posts — no drift
between the two is acceptable.

---

## 4. Non-negotiables carried forward from §5/§7 into every later phase

- The exchange stop (§2) is placed independently of anything the agent computes,
  and its existence is never made conditional on the agent's state.
- The funding sign convention (§3) is one formula, used identically by
  `RewardEngine`, any backtest/replay tooling, and the live paper-trading P&L —
  never three parallel implementations that could quietly disagree.
- The reward `r_t` (§1) is the only reward the policy is trained against. A
  "simplified" or "debug" reward is not permitted to leak into a trained
  checkpoint that later phases load.

Any future change to risk parameters, funding handling, or the reward definition
starts here, in this file, not in a phase's code.
