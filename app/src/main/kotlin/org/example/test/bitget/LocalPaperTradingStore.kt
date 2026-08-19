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
)

data class PaperTradingSnapshot(
    val account: PaperAccount,
    val walletBalance: Double,
    val positions: List<PersistedPaperPosition>,
    val pendingOrders: List<PendingLimitOrder> = emptyList(),
    val closedTrades: List<ClosedPaperTrade> = emptyList(),
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
                            },
                        )
                    }
                },
            )
            prefs.edit().putString(KEY_SNAPSHOT, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save local paper trading snapshot: ${e.message}")
        }
    }

    /** Wipes the local account entirely so the next [load] returns null. */
    fun clear() {
        prefs.edit().remove(KEY_SNAPSHOT).apply()
    }
}
