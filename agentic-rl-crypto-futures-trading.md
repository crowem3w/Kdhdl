# Agentic Reinforcement Learning for Crypto Futures Trading
### Design Documentation — Bitget BTCUSDT Perpetual (BTCUSDTP)

---

## 1. Overview

Financial markets are stochastic environments where prices are driven by many interacting, partially observable factors (investor behavior, macro conditions, news, liquidity shocks). Because the underlying data-generating process is **non-stationary** — its statistical properties drift over time — an RL agent trained to memorize fixed patterns will systematically fail as those patterns decay.

This document specifies an architecture and experience-acquisition strategy for an **agentic RL system** that trades Bitget's BTCUSDT perpetual futures contract, designed to **adapt to randomness and regime change** rather than fit to a static pattern. It also addresses the harder case of **pure online learning with no historical dataset available**.

---

## 2. Problem Framing

- Treat the market as a **non-stationary POMDP** (Partially Observable Markov Decision Process), not a stationary MDP.
- The true market state (regime, liquidity conditions, sentiment) is hidden and must be inferred from a rolling window of observations.
- The objective is not to learn *the* pattern, but to learn:
  1. A **fast-adaptation procedure** for when conditions shift, and
  2. **Calibrated uncertainty** so the agent knows when it doesn't know.

---

## 3. Core Architecture

```
Market data → Feature/latent encoder (Transformer/LSTM)
           → Regime/change-point detector (auxiliary head)
           → Context vector
           → Mixture-of-Experts policy (meta-RL, one expert cluster per regime type)
           → Distributional critic (IQN/QR) → risk-sensitive action
           → Ensemble uncertainty gate → position sizing / exploration
           → Online replay buffer with recency + regime-stratified sampling
```

### 3.1 Latent State Encoder
A recurrent or attention-based encoder (LSTM, Transformer, or state-space model) compresses a rolling observation window into a belief state, so the policy conditions on inferred context rather than raw features that "expire."

### 3.2 Meta-RL Policy
Instead of a single policy optimized for average performance, use meta-RL (e.g., RL², context-conditioned policies) trained across many simulated regimes (bull, bear, high/low volatility, crisis) so the agent learns **how to adapt quickly**, not a fixed strategy.

### 3.3 Distributional, Risk-Sensitive Value Learning
Use distributional RL (QR-DQN, IQN, distributional SAC) to model a full outcome distribution instead of a single expected value. This enables optimizing risk-adjusted objectives (e.g., CVaR, Sharpe-like reward) instead of blindly extrapolating a point estimate.

### 3.4 Regime / Change-Point Detection
An auxiliary module (Bayesian online change-point detection, HMM, volatility/structural-break detector) flags "the rules just changed," prompting increased exploration or reduced position size instead of confident extrapolation.

### 3.5 Ensemble Uncertainty Gate
An ensemble of policies/value functions (bootstrapped or Bayesian dropout) estimates epistemic uncertainty. High disagreement across the ensemble automatically reduces exposure — a principled hedge against unknown regimes.

### 3.6 Continual / Online Learning
Incremental retraining (online SGD, recency-weighted replay) with protection against catastrophic forgetting (elastic weight consolidation, or mixture-of-experts gated by the regime detector).

### 3.7 Reward Design
Reward = risk-adjusted return (differential Sharpe ratio or CVaR-penalized return) minus penalties for turnover and drawdown — optimizing for **consistency across shifting conditions**, not raw historical profit maximization.

---

## 4. Experience Acquisition Strategy

Real markets alone don't provide enough diverse, labeled regime-shift data quickly enough. A tiered sim-to-real pipeline is used to accumulate experience safely.

### 4.1 Tiered Pipeline

| Tier | Source | Purpose |
|------|--------|---------|
| 1 | Synthetic regime generator (jump-diffusion, regime-switching GARCH, Hawkes processes) | Cheap, infinite data; force rare/extreme events |
| 2 | Perturbed historical replay (time-warping, volatility scaling, block bootstrapping) | Realism without exact memorization |
| 3 | Live paper trading / shadow deployment | Real non-stationarity, zero capital risk |
| 4 | Small live capital | True execution effects (slippage, partial fills, adverse selection) |

### 4.2 Curriculum by Regime
Stratify the replay buffer by detected regime rather than chronological order. Oversample rare-but-critical regimes (liquidation cascades, exchange outages, stablecoin depegs).

### 4.3 Multi-Agent / Self-Play
Train against a population of agents (rule-based market makers, momentum bots, adversarial liquidation-hunters) so the agent experiences being counter-traded, not just observing a passive price series.

### 4.4 Off-Policy, Prioritized Replay
Use off-policy algorithms (SAC, IQN-based) with prioritized replay weighted by both TD-error and recency.

### 4.5 Crypto-Specific Experience Augmentation
- Cross-exchange arbitrage spread (Binance / OKX / Deribit / Bitget)
- Funding rate cycles (8h resets) as structured periodic features
- On-chain data (exchange netflows, whale transactions, liquidation heatmaps) as auxiliary leading indicators

### 4.6 Meta-Training Across Assets
Train the meta-policy jointly across correlated futures (BTC, ETH, SOL, etc.) to force generalizable fast-adaptation behavior, as a proxy for adapting across time-varying regimes within a single asset.

### 4.7 Suggested Sequencing
```
Synthetic regimes (broad coverage, cheap)
  → Perturbed historical replay (realism)
  → Multi-agent self-play (adversarial dynamics)
  → Paper trading shadow deployment (live non-stationarity, zero risk)
  → Small live capital (real execution effects)
```

---

## 5. Dataset Ingestion List (Bitget BTCUSDTP)

### 5.1 Core Market Microstructure (Bitget-native — highest priority)
- Tick-level trade data (price, size, side, timestamp) — WebSocket `trade` channel
- Order book snapshots + incremental updates (L2, ~20-50 depth) — `books` channel
- Mark price and index price feeds (liquidations trigger off mark price, not last price)
- Funding rate history + predicted next funding rate (8h cycle)
- Open interest (OI) time series
- Historical klines/candles (1m, 5m, 15m, 1h, 4h, 1d)
- Liquidation feed (public liquidation orders stream) — leading indicator for cascades

### 5.2 Cross-Exchange Reference Data
- BTC-USDT perpetual data from Binance, OKX, Bybit (same fields as above)
- Spot BTC-USDT price from major exchanges (Binance spot, Coinbase) as fair-value anchor

### 5.3 On-Chain Data
- Exchange netflow (BTC in/out of Bitget and major exchanges) — Glassnode, CryptoQuant, Nansen
- Whale wallet transaction alerts (Whale Alert API or on-chain indexers)
- Miner flow data (longer-horizon signals)
- Stablecoin supply changes (USDT/USDC mint-burn — depeg risk)

### 5.4 Derivatives-Specific Structural Data
- Aggregated open interest across exchanges (Coinglass)
- Long/short ratio (top trader positioning)
- Options-implied volatility and skew (Deribit BTC options)
- Basis (futures-spot spread) time series

### 5.5 Macro / News / Sentiment
- Crypto-specific news feed (CryptoPanic or scraped headlines), timestamp-aligned
- Social sentiment (LunarCrush, Santiment, Reddit activity)
- Macro calendar events (FOMC, CPI releases, ETF flow announcements)
- Regulatory news flags (SEC actions, exchange hacks/outages)

### 5.6 Bitget-Specific Operational Data
- Historical funding rate settlements (actual, not predicted)
- Insurance fund balance (systemic liquidation stress signal)
- Maintenance margin tiers / leverage brackets (for realistic liquidation modeling)
- API rate limits and historical downtime/incident logs (execution-risk modeling)

### 5.7 Derived / Computed Datasets
- Realized volatility at multiple windows (5m, 1h, 1d)
- Rolling correlation between BTCUSDTP and ETH/SOL perpetuals
- Historical regime labels (from the change-point detector, stored for supervised pretraining)

### 5.8 Priority Summary
```
Must-have (Tier 1):   Bitget trades, order book, mark price, funding rate, klines, liquidations
Strong add (Tier 2):  OI, long/short ratio, cross-exchange spot/futures, basis
Enrichment (Tier 3):  on-chain flows, options IV, sentiment, macro calendar
```

> **Note:** Exact endpoint names, rate limits, and historical data depth should be verified directly against Bitget's current API documentation, as these details are subject to change.

---

## 6. Pure Online Learning (No Historical Data)

When no historical dataset is available and the agent must learn continuously from live market interaction, the design shifts from a "train then deploy" paradigm to a **safety-first, self-bootstrapping online system**.

### 6.1 Key Constraint
Without pretraining, early actions are near-random. A raw policy must never touch live capital directly — it must operate inside a **safety cage**.

### 6.2 Safety Cage Components
- Hard position size caps, shrinking further under high uncertainty
- Hard stop-loss / max-drawdown circuit breaker, independent of the policy
- A rule-based fallback controller (flat/do-nothing) that the agent must "earn the right" to override as calibration improves

This reframes the problem as **constrained/safe online RL** (e.g., Lagrangian-constrained policy optimization, or action shielding).

### 6.3 Algorithm Choices
- **Online SAC or TD3** — sample-efficient, continuous action space (position sizing), small learning rates, short rollout buffers
- **Contextual bandit warm-up** before full sequential RL — discrete actions (long/short/flat/size-tier) converge faster with less data; graduate to full RL once stable positive-EV behavior is shown
- Avoid on-policy algorithms (e.g., vanilla PPO) — too sample-hungry for cold start in this noise regime

### 6.4 Bootstrapping Synthetic Experience Without Historical Data
- **Model-based warm-up (Dyna-style):** fit a simple stochastic model (GBM + jump process) to the *live* price stream as it arrives; use it to generate synthetic rollouts in parallel with live trading, so real transitions update the model and the model generates extra training data
- **Self-play against a rule-based/random counterpart** in a concurrent paper-trading shadow instance to accumulate more "reps" than the live clock alone provides

### 6.5 Adaptive Plasticity (Meta-Learned Learning Rate)
The agent's own learning/exploration rate adapts based on recent prediction error:
- High recent value-function error → increase learning rate / exploration temporarily
- Low, stable error → reduce learning rate, exploit more

This substitutes for the "prior" that pretraining would normally provide.

### 6.6 Ensemble-of-Small-Agents
Run several small, differently initialized agents in parallel with capped, independent allocations:
- Disagreement across agents → reduce aggregate position size (uncertainty proxy)
- Built-in live A/B testing of learning-rate/exploration schedules
- Blast-radius containment — one agent's failure doesn't sink the system

### 6.7 Reward Shaping for Cold Start
- Heavily risk-penalized reward from step one (differential Sharpe, drawdown penalty, funding-cost penalty)
- Reward floor tied to inaction — flat/no-trade is mildly negative or zero, not punished into forced signal generation

### 6.8 Staged Capital Ramp (Metric-Gated, Not Calendar-Gated)
Capital increases are gated on statistical criteria computed continuously:
- Minimum trades observed across ≥2-3 distinct volatility regimes
- Rolling Sharpe/Sortino above threshold with statistically significant sample size
- Ensemble agreement above threshold
- Maximum observed drawdown under a hard ceiling

### 6.9 Online Architecture
```
Live price/orderbook feed
   → Online model fitter (jump-diffusion params updated continuously)
   → Dyna-style: real transitions + model-generated synthetic transitions
   → Ensemble of online SAC/TD3 agents (small, diverse init)
   → Meta-controller: adjusts learning rate/exploration from recent TD-error
   → Safety cage: position caps, drawdown breaker, rule-based fallback
   → Capital allocator: scales real size based on ensemble agreement + rolling risk metrics
```

### 6.10 Mindset Shift
Without historical data, the objective is not to train a trading agent in isolation — it is to design a **system that safely discovers how to trade while being continuously monitored by its own embedded risk manager**. The RL policy is one component; the safety and meta-learning layers around it are what prevent catastrophic early losses.

---

## 7. Runtime & Infrastructure

The system described above cannot run as a one-shot script. It requires an **always-on runtime engine** because it involves continuous data ingestion, online learning, and real-time decision-making under strict latency and safety constraints.

### 7.1 Always-On Process Orchestration
- WebSocket connections to Bitget (and reference exchanges) must stay alive continuously, with auto-reconnect/backoff logic
- The agent, regime detector, and safety cage all run as long-lived processes, not batch jobs

### 7.2 Event-Driven Execution Loop
- Market ticks and order book updates arrive asynchronously — the engine needs an event loop or streaming framework that triggers inference on arrival, not a polling script
- Prototyping: asyncio-based Python event loop
- Production: a proper event bus (Kafka or Redis Streams) once running multiple agents and data sources concurrently

### 7.3 State Management Across Restarts
- Replay buffer, ensemble weights, online model parameters (jump-diffusion fit), and regime history must persist to disk/DB
- A crash or redeploy must not wipe learning progress — requires regular checkpointing, not just shutdown-time saves

### 7.4 Separation of Concerns (Concurrent Services)
```
Data ingestion service   → writes to a shared feature store / message bus
Regime detector service  → consumes features, publishes regime state
Agent inference service  → consumes state, outputs actions
Safety cage / risk layer → intercepts actions before execution
Execution service        → sends orders to Bitget API
Capital allocator        → monitors metrics, adjusts exposure gates
Monitoring/logging       → tracks drawdown, latency, PnL in real time
```
These run as separate containers/processes communicating over a message bus rather than one monolithic script, so a failure in one component (e.g., a slow sentiment feed) doesn't take down execution.

### 7.5 Low-Latency Execution Path
- The path from "signal generated" to "order sent" must be fast and decoupled from slower enrichment components (news scraping, on-chain data)
- The safety cage and execution service run on a tighter, prioritized loop, separate from heavier ML inference

### 7.6 Training/Inference Split
- Online learning (gradient updates) must not block the live inference path
- A separate asynchronous training loop updates weights; the live inference service periodically picks up new checkpoints rather than retraining every tick

### 7.7 Implementation Stack Options

| Stage | Stack |
|---|---|
| Prototype | Python asyncio, Celery/APScheduler for scheduling, SQLite/Redis for state |
| Production | Kubernetes-orchestrated microservices, Kafka/Redis Streams (event bus), InfluxDB/TimescaleDB (market time-series), PostgreSQL (trade/state persistence) |

---

## 8. Summary Table

| Component | Purpose |
|---|---|
| Latent state encoder | Infer hidden regime/context from rolling observations |
| Meta-RL policy | Learn to adapt fast, not memorize a pattern |
| Distributional critic | Risk-aware decision-making under uncertainty |
| Regime/change-point detector | Detect structural breaks in real time |
| Ensemble uncertainty gate | Auto-hedge when the agent is unsure |
| Tiered experience pipeline | Sim → perturbed replay → self-play → paper → live |
| Safety cage (online case) | Prevent catastrophic loss during cold-start learning |
| Metric-gated capital ramp | Scale real risk only when statistically justified |

---

## 9. Open Follow-Ups
- Detailed reward/objective function for futures (funding costs, liquidation risk, leverage constraints)
- Replay buffer sampling scheme (prioritization weights, regime stratification detail)
- Dyna-style model-based bootstrap mechanism (fitted stochastic model + synthetic rollout generation)
- Bitget API endpoint verification (current docs, rate limits, historical data depth)
- Runtime engine implementation detail (message bus schema, service deployment topology, checkpoint/failover strategy)

---

*Document generated from design discussion on RL-based agentic crypto futures trading (Bitget BTCUSDTP).*
