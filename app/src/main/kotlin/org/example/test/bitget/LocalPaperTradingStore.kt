package org.example.test.bitget

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** One open position as persisted to disk - a plain snapshot, not the live [PaperPosition] (which also carries mark price/uPnL derived at render time). */
data class PersistedPaperPosition(
    val side: PositionSide,
    val total: Double,
    val entryPrice: Double,
    val leverage: Int,
    val marginSize: Double,
    // See PaperPosition.feesPaidSoFar - accumulated entry/add-on fees only.
    val feesPaidSoFar: Double = 0.0,
    // See PaperPosition.fundingPaidSoFar - net funding paid/received while
    // this position has been open.
    val fundingPaidSoFar: Double = 0.0,
)

data class PaperTradingSnapshot(
    val account: PaperAccount,
    val walletBalance: Double,
    val positions: List<PersistedPaperPosition>,
    val pendingOrders: List<PendingLimitOrder> = emptyList(),
    val closedTrades: List<ClosedPaperTrade> = emptyList(),
    // Most-recent-first funding settlement history (design doc §7) - see
    // PaperTradingRepository.fundingPayments.
    val fundingPayments: List<FundingPayment> = emptyList(),
    // The most recent funding timestamp this account has already settled
    // against - null for an account that predates this feature or has
    // never lived through a settlement yet. Lets the funding job catch up
    // correctly on restart instead of re-charging (or skipping) a
    // settlement (see FundingSchedule.settlementsBetween).
    val lastFundingSettledAt: Long? = null,
)

/**
 * Persists the entire local paper trading account - the account record,
 * its cash balance, and every open position - to this app's private
 * on-device storage. There is exactly one paper trading account per
 * install; nothing here is ever sent anywhere.
 */
class LocalPaperTradingStore(context: Context) {
    private companion object {
        const val TAG = "LocalPaperTradingStore"
        const val PREFS_NAME = "local_paper_trading"
        const val KEY_SNAPSHOT = "snapshot_json"

        // Trade history is capped so a long-lived local account doesn't
        // grow its SharedPreferences blob without bound. This comfortably
        // covers the "last 30 days" window the account screen reports on.
        const val MAX_CLOSED_TRADES = 500

        // Latency simulation settings (see LatencyConfig) - kept under
        // their own keys rather than inside the account snapshot JSON,
        // since this is a simulation preference, not account data: it
        // should survive resetAccount()/clear() and should be readable
        // even before any account has ever been created.
        const val KEY_LATENCY_ENABLED = "latency_enabled"
        const val KEY_LATENCY_BASE_DELAY_MS = "latency_base_delay_ms"
        const val KEY_LATENCY_JITTER_MS = "latency_jitter_ms"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PaperTradingSnapshot? {
        val json = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            val root = JSONObject(json)
            val accountJson = root.getJSONObject("account")
            val account = PaperAccount(
                id = accountJson.getString("id"),
                createdAt = accountJson.getLong("createdAt"),
                lastDepositAt = if (accountJson.has("lastDepositAt") && !accountJson.isNull("lastDepositAt")) {
                    accountJson.getLong("lastDepositAt")
                } else {
                    null
                },
            )
            val positionsJson = root.optJSONArray("positions") ?: JSONArray()
            val positions = buildList {
                for (i in 0 until positionsJson.length()) {
                    val p = positionsJson.getJSONObject(i)
                    add(
                        PersistedPaperPosition(
                            side = PositionSide.valueOf(p.getString("side")),
                            total = p.getDouble("total"),
                            entryPrice = p.getDouble("entryPrice"),
                            leverage = p.getInt("leverage"),
                            marginSize = p.getDouble("marginSize"),
                            feesPaidSoFar = p.optDouble("feesPaidSoFar", 0.0),
                            fundingPaidSoFar = p.optDouble("fundingPaidSoFar", 0.0),
                        ),
                    )
                }
            }
            val pendingOrdersJson = root.optJSONArray("pendingOrders") ?: JSONArray()
            val pendingOrders = buildList {
                for (i in 0 until pendingOrdersJson.length()) {
                    val o = pendingOrdersJson.getJSONObject(i)
                    add(
                        PendingLimitOrder(
                            id = o.getString("id"),
                            side = PositionSide.valueOf(o.getString("side")),
                            sizeInBaseCoin = o.getDouble("sizeInBaseCoin"),
                            leverage = o.getInt("leverage"),
                            limitPrice = o.getDouble("limitPrice"),
                            marginReserved = o.getDouble("marginReserved"),
                            createdAt = o.getLong("createdAt"),
                            queueAheadVolume = o.optDouble("queueAheadVolume", 0.0),
                        ),
                    )
                }
            }
            val closedTradesJson = root.optJSONArray("closedTrades") ?: JSONArray()
            val closedTrades = buildList {
                for (i in 0 until closedTradesJson.length()) {
                    val t = closedTradesJson.getJSONObject(i)
                    add(
                        ClosedPaperTrade(
                            symbol = t.optString("symbol", "BTCUSDT"),
                            side = PositionSide.valueOf(t.getString("side")),
                            size = t.getDouble("size"),
                            entryPrice = t.getDouble("entryPrice"),
                            exitPrice = t.getDouble("exitPrice"),
                            leverage = t.getInt("leverage"),
                            realizedPnl = t.getDouble("realizedPnl"),
                            closedAt = t.getLong("closedAt"),
                            totalFeesPaid = t.optDouble("totalFeesPaid", 0.0),
                            totalFundingPaid = t.optDouble("totalFundingPaid", 0.0),
                        ),
                    )
                }
            }
            val fundingPaymentsJson = root.optJSONArray("fundingPayments") ?: JSONArray()
            val fundingPayments = buildList {
                for (i in 0 until fundingPaymentsJson.length()) {
                    val f = fundingPaymentsJson.getJSONObject(i)
                    add(
                        FundingPayment(
                            symbol = f.optString("symbol", "BTCUSDT"),
                            side = PositionSide.valueOf(f.getString("side")),
                            fundingRate = f.getDouble("fundingRate"),
                            positionNotional = f.getDouble("positionNotional"),
                            amount = f.getDouble("amount"),
                            settledAt = f.getLong("settledAt"),
                        ),
                    )
                }
            }
            PaperTradingSnapshot(
                account = account,
                walletBalance = root.getDouble("walletBalance"),
                positions = positions,
                pendingOrders = pendingOrders,
                closedTrades = closedTrades,
                fundingPayments = fundingPayments,
                lastFundingSettledAt = if (root.has("lastFundingSettledAt") && !root.isNull("lastFundingSettledAt")) {
                    root.getLong("lastFundingSettledAt")
                } else {
                    null
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load local paper trading snapshot: ${e.message}")
            null
        }
    }

    fun save(snapshot: PaperTradingSnapshot) {
        try {
            val root = JSONObject()
            root.put(
                "account",
                JSONObject().apply {
                    put("id", snapshot.account.id)
                    put("createdAt", snapshot.account.createdAt)
                    put("lastDepositAt", snapshot.account.lastDepositAt)
                },
            )
            root.put("walletBalance", snapshot.walletBalance)
            root.put(
                "positions",
                JSONArray().apply {
                    for (p in snapshot.positions) {
                        put(
                            JSONObject().apply {
                                put("side", p.side.name)
                                put("total", p.total)
                                put("entryPrice", p.entryPrice)
                                put("leverage", p.leverage)
                                put("marginSize", p.marginSize)
                                put("feesPaidSoFar", p.feesPaidSoFar)
                                put("fundingPaidSoFar", p.fundingPaidSoFar)
                            },
                        )
                    }
                },
            )
            root.put(
                "pendingOrders",
                JSONArray().apply {
                    for (o in snapshot.pendingOrders) {
                        put(
                            JSONObject().apply {
                                put("id", o.id)
                                put("side", o.side.name)
                                put("sizeInBaseCoin", o.sizeInBaseCoin)
                                put("leverage", o.leverage)
                                put("limitPrice", o.limitPrice)
                                put("marginReserved", o.marginReserved)
                                put("createdAt", o.createdAt)
                                put("queueAheadVolume", o.queueAheadVolume)
                            },
                        )
                    }
                },
            )
            root.put(
                "closedTrades",
                JSONArray().apply {
                    for (t in snapshot.closedTrades.take(MAX_CLOSED_TRADES)) {
                        put(
                            JSONObject().apply {
                                put("symbol", t.symbol)
                                put("side", t.side.name)
                                put("size", t.size)
                                put("entryPrice", t.entryPrice)
                                put("exitPrice", t.exitPrice)
                                put("leverage", t.leverage)
                                put("realizedPnl", t.realizedPnl)
                                put("closedAt", t.closedAt)
                                put("totalFeesPaid", t.totalFeesPaid)
                                put("totalFundingPaid", t.totalFundingPaid)
                            },
                        )
                    }
                },
            )
            root.put(
                "fundingPayments",
                JSONArray().apply {
                    for (f in snapshot.fundingPayments.take(MAX_CLOSED_TRADES)) {
                        put(
                            JSONObject().apply {
                                put("symbol", f.symbol)
                                put("side", f.side.name)
                                put("fundingRate", f.fundingRate)
                                put("positionNotional", f.positionNotional)
                                put("amount", f.amount)
                                put("settledAt", f.settledAt)
                            },
                        )
                    }
                },
            )
            root.put("lastFundingSettledAt", snapshot.lastFundingSettledAt)
            prefs.edit().putString(KEY_SNAPSHOT, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save local paper trading snapshot: ${e.message}")
        }
    }

    /** Wipes the local account entirely so the next [load] returns null. Deliberately leaves the latency settings ([loadLatencyConfig]/[saveLatencyConfig]) untouched - resetting the practice account shouldn't also silently reset a trader's simulation preferences. */
    fun clear() {
        prefs.edit().remove(KEY_SNAPSHOT).apply()
    }

    /** Loads the persisted latency-simulation settings (see [LatencyConfig]). Falls back to [LatencyConfig.DEFAULT] the first time, or if nothing was ever saved. */
    fun loadLatencyConfig(): LatencyConfig {
        if (!prefs.contains(KEY_LATENCY_ENABLED)) return LatencyConfig.DEFAULT
        return LatencyConfig(
            enabled = prefs.getBoolean(KEY_LATENCY_ENABLED, LatencyConfig.DEFAULT.enabled),
            baseDelayMs = prefs.getLong(KEY_LATENCY_BASE_DELAY_MS, LatencyConfig.DEFAULT.baseDelayMs),
            jitterMs = prefs.getLong(KEY_LATENCY_JITTER_MS, LatencyConfig.DEFAULT.jitterMs),
        ).coerced()
    }

    /** Persists [config] so it survives app restarts - see [loadLatencyConfig]. */
    fun saveLatencyConfig(config: LatencyConfig) {
        val coerced = config.coerced()
        prefs.edit()
            .putBoolean(KEY_LATENCY_ENABLED, coerced.enabled)
            .putLong(KEY_LATENCY_BASE_DELAY_MS, coerced.baseDelayMs)
            .putLong(KEY_LATENCY_JITTER_MS, coerced.jitterMs)
            .apply()
    }
}
