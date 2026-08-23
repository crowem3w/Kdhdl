package org.example.syncora.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.example.syncora.bitget.FundingSchedule
import org.json.JSONArray

/** One row's resolution state - mirrors the `reward_status` column so callers never have to compare against a raw string. */
enum class RewardStatus {
    PENDING,
    RESOLVED,
    ;

    companion object {
        fun fromColumn(value: String): RewardStatus = when (value) {
            "resolved" -> RESOLVED
            else -> PENDING
        }
    }

    fun toColumn(): String = if (this == RESOLVED) "resolved" else "pending"
}

/** What [ExperienceLogStore.logDecision] writes at decision time - everything the policy/critic already know about `S_t`, before `r_t`/`S_{t+1}` exist. */
data class PendingExperienceEntry(
    val timestampMs: Long,
    val symbol: String,
    /** `S_t`, flattened - see [org.example.syncora.bitget.MdpStateSnapshot.toDoubleArray]. */
    val state: DoubleArray,
    /** The actual sampled action dispatched this tick (design doc §3.6: the noisy, on-policy action - not the policy mean - since that's what PPO's ratio needs [logProb] to correspond to). */
    val action: Double,
    /** `log π_θ(a_t|s_t)` under the exploration distribution the action was actually sampled from. */
    val logProb: Double,
    /** `V(s_t)`, the critic's value estimate at decision time - stored now so GAE (§3.6/§4) doesn't need to re-run the critic during the batch job. */
    val valueEstimate: Double,
)

/**
 * One fully-resolved row, shaped for [ExperienceLogStore.resolvedRowsSince] - what the §3.3
 * batch job actually consumes to build rollout windows and compute GAE advantages.
 */
data class ResolvedExperience(
    val id: Long,
    val timestampMs: Long,
    val symbol: String,
    val state: DoubleArray,
    val action: Double,
    val logProb: Double,
    val valueEstimate: Double,
    val nextState: DoubleArray,
    val nextTimestampMs: Long,
    val reward: Double,
)

/**
 * Append-only SQLite log of every decision-point transition, implementing the design doc's
 * §3.6 "two-phase logging": at the moment an action is taken, its reward isn't known yet - Δv_t
 * needs S_{t+1}, and the funding component only settles on Bitget's 8-hour schedule, not every
 * decision step. This store lets those two components resolve independently and only reports a
 * row as usable training data ([RewardStatus.RESOLVED]) once both have landed.
 *
 * Same on-device-storage philosophy as [org.example.syncora.bitget.LocalPaperTradingStore]
 * (private app storage, nothing ever leaves the device, `Context`-scoped constructor, quiet
 * failure-to-`Log.w` rather than throwing into a caller that's usually a hot decision-loop
 * path) - but backed by SQLite rather than a single `SharedPreferences` JSON blob, because this
 * log is unbounded and append-only by design (§3.6 explicitly calls it "an append-only SQLite
 * table"), where [LocalPaperTradingStore]'s snapshot is small, mutable, and fully rewritten on
 * every save.
 *
 * **Write paths (three, all independent of each other):**
 * 1. [logDecision] - called once per decision boundary, from [DecisionLoopScheduler] (or
 *    whatever ends up owning the exploration sampling), the instant `a_t` is dispatched.
 * 2. [backfillDeltaV] - called once per decision boundary too, but *retroactively* against the
 *    *previous* row: when `S_{t+1}` becomes available (i.e. right after step 1 runs for the
 *    *next* tick), the caller now has enough to compute `Δv_t - c_t - λ_var·σ_t² - λ_dd·DD_t`
 *    (everything in the design doc §3.5 reward except the funding term) for the row logged at
 *    `t`.
 * 3. [backfillFundingSettlement] - called whenever Bitget's 8-hour funding job fires (the same
 *    trigger [org.example.syncora.bitget.PaperTradingRepository] already reacts to via
 *    [FundingSchedule]), with the funding P&L realized against whatever position was open at
 *    settlement. Applied to every row whose decision window contains that settlement instant,
 *    which may be zero, one, or (rarely, if a decision window is unusually long) more than one
 *    row.
 *
 * **Why resolution needs an expected-event count, not just "has *a* funding backfill arrived".**
 * A row's window might span zero funding settlements (most rows, given decision cadences
 * shorter than 8h) or, rarely, more than one. Marking a row resolved the instant *any* funding
 * backfill touches it would under-count a window that spans two settlements; waiting
 * indefinitely for "the" settlement would leave a zero-settlement window pending forever. So
 * [backfillDeltaV] computes how many settlements *should* land in `[t_i, t_{i+1})` via
 * [FundingSchedule.settlementsBetween] the moment the window's end is known, and a row only
 * flips to [RewardStatus.RESOLVED] once `funding_events_applied >= expected_funding_events`
 * *and* the delta-v half has landed - genuinely both components, not just "something arrived on
 * both write paths".
 */
class ExperienceLogStore(context: Context) {
    private companion object {
        const val TAG = "ExperienceLogStore"

        const val DB_NAME = "experience_log.db"
        const val DB_VERSION = 1

        const val TABLE = "experience_log"

        const val COL_ID = "id"
        const val COL_SYMBOL = "symbol"
        const val COL_TIMESTAMP_MS = "timestamp_ms"
        const val COL_STATE_JSON = "state_json"
        const val COL_ACTION = "action"
        const val COL_LOG_PROB = "log_prob"
        const val COL_VALUE_ESTIMATE = "value_estimate"

        const val COL_NEXT_STATE_JSON = "next_state_json"
        const val COL_NEXT_TIMESTAMP_MS = "next_timestamp_ms"
        const val COL_MARKET_REWARD_COMPONENT = "market_reward_component"
        const val COL_DELTA_V_RESOLVED = "delta_v_resolved"

        const val COL_EXPECTED_FUNDING_EVENTS = "expected_funding_events"
        const val COL_FUNDING_EVENTS_APPLIED = "funding_events_applied"
        const val COL_FUNDING_COMPONENT = "funding_component"
        const val COL_FUNDING_RESOLVED = "funding_resolved"

        const val COL_REWARD = "reward"
        const val COL_REWARD_STATUS = "reward_status"

        const val STATUS_PENDING = "pending"
        const val STATUS_RESOLVED = "resolved"

        private fun DoubleArray.toJson(): String = JSONArray(this.toList()).toString()

        private fun String.toDoubleArray(): DoubleArray {
            val arr = JSONArray(this)
            return DoubleArray(arr.length()) { i -> arr.getDouble(i) }
        }
    }

    private inner class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_SYMBOL TEXT NOT NULL,
                    $COL_TIMESTAMP_MS INTEGER NOT NULL,
                    $COL_STATE_JSON TEXT NOT NULL,
                    $COL_ACTION REAL NOT NULL,
                    $COL_LOG_PROB REAL NOT NULL,
                    $COL_VALUE_ESTIMATE REAL NOT NULL,
                    $COL_NEXT_STATE_JSON TEXT,
                    $COL_NEXT_TIMESTAMP_MS INTEGER,
                    $COL_MARKET_REWARD_COMPONENT REAL,
                    $COL_DELTA_V_RESOLVED INTEGER NOT NULL DEFAULT 0,
                    $COL_EXPECTED_FUNDING_EVENTS INTEGER,
                    $COL_FUNDING_EVENTS_APPLIED INTEGER NOT NULL DEFAULT 0,
                    $COL_FUNDING_COMPONENT REAL NOT NULL DEFAULT 0.0,
                    $COL_FUNDING_RESOLVED INTEGER NOT NULL DEFAULT 0,
                    $COL_REWARD REAL,
                    $COL_REWARD_STATUS TEXT NOT NULL DEFAULT '$STATUS_PENDING'
                )
                """.trimIndent(),
            )
            // reward_status is the batch job's primary filter (§3.3 step 1: "pull all
            // reward_status = resolved rows"); the funding job's primary filter is
            // "still-open windows I haven't fully applied this settlement to yet".
            db.execSQL("CREATE INDEX idx_experience_log_reward_status ON $TABLE ($COL_REWARD_STATUS, $COL_TIMESTAMP_MS)")
            db.execSQL("CREATE INDEX idx_experience_log_funding_pending ON $TABLE ($COL_FUNDING_RESOLVED, $COL_TIMESTAMP_MS)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No prior schema version exists yet; nothing to migrate. Future bumps should
            // ALTER TABLE rather than drop, since this table is append-only logged trading
            // experience, not disposable cache.
        }
    }

    private val dbHelper = DbHelper(context.applicationContext)

    /**
     * Appends the row for a just-taken decision, with `reward_status = pending` and every
     * backfill column left `NULL`/default. Returns the row id, which the caller must hold onto
     * (in-memory is fine - see [DecisionLoopScheduler]) to pass back into [backfillDeltaV] once
     * the next decision boundary resolves this one's `S_{t+1}`.
     */
    fun logDecision(entry: PendingExperienceEntry): Long {
        val values = ContentValues().apply {
            put(COL_SYMBOL, entry.symbol)
            put(COL_TIMESTAMP_MS, entry.timestampMs)
            put(COL_STATE_JSON, entry.state.toJson())
            put(COL_ACTION, entry.action)
            put(COL_LOG_PROB, entry.logProb)
            put(COL_VALUE_ESTIMATE, entry.valueEstimate)
            put(COL_REWARD_STATUS, STATUS_PENDING)
        }
        return try {
            dbHelper.writableDatabase.insertOrThrow(TABLE, null, values)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log decision at ${entry.timestampMs}: ${e.message}")
            -1L
        }
    }

    /**
     * Back-fills the non-funding half of the reward - `Δv_t - c_t - λ_var·σ_t² - λ_dd·DD_t`
     * (design doc §3.5, everything except `Φ_t^funding`) - plus `S_{t+1}`, once the next
     * decision boundary makes both computable. Also computes how many funding settlements this
     * row's window `[timestamp_ms, nextTimestampMs)` should contain (via
     * [FundingSchedule.settlementsBetween]); a window with zero expected settlements resolves
     * immediately (funding contributes `0.0`), matching "flips to resolved once both components
     * are known" for the common case where a decision window doesn't straddle an 8-hour boundary
     * at all.
     *
     * Safe to call at most meaningfully once per row - a second call on an already-delta-v-
     * resolved row is a no-op (`WHERE delta_v_resolved = 0` guards the update), since a
     * decision's `S_{t+1}` shouldn't retroactively change.
     */
    fun backfillDeltaV(rowId: Long, nextState: DoubleArray, nextTimestampMs: Long, marketRewardComponent: Double): Boolean {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val row = queryRow(db, rowId) ?: run {
                Log.w(TAG, "backfillDeltaV: no row $rowId")
                return false
            }
            if (row.deltaVResolved) {
                Log.w(TAG, "backfillDeltaV: row $rowId already delta-v-resolved, ignoring")
                return false
            }

            val expectedFundingEvents = FundingSchedule.settlementsBetween(row.timestampMs, nextTimestampMs).size

            val values = ContentValues().apply {
                put(COL_NEXT_STATE_JSON, nextState.toJson())
                put(COL_NEXT_TIMESTAMP_MS, nextTimestampMs)
                put(COL_MARKET_REWARD_COMPONENT, marketRewardComponent)
                put(COL_DELTA_V_RESOLVED, 1)
                put(COL_EXPECTED_FUNDING_EVENTS, expectedFundingEvents)
                if (expectedFundingEvents == 0) {
                    // Nothing to wait on: the funding half is trivially "known" to be zero.
                    put(COL_FUNDING_RESOLVED, 1)
                }
            }
            val updated = db.update(TABLE, values, "$COL_ID = ? AND $COL_DELTA_V_RESOLVED = 0", arrayOf(rowId.toString()))
            if (updated == 0) return false

            finalizeIfBothResolved(db, rowId)
            db.setTransactionSuccessful()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to backfill delta-v for row $rowId: ${e.message}")
            return false
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Applied whenever Bitget's 8-hour funding settlement fires, with the funding P&L
     * ([fundingComponent], same sign convention as [org.example.syncora.bitget.FundingPayment.amount]
     * - positive is a cost) realized at [settledAt]. Touches every row whose decision window
     * contains this instant: started at-or-before [settledAt], and either hasn't delta-v-
     * resolved yet (window still open) or resolved at-or-after [settledAt] (window closed after
     * this settlement landed, so the settlement fell inside it). Rows whose window closed before
     * [settledAt], or that have already fully applied [FundingSchedule.settlementsBetween]-many
     * settlements, are left untouched.
     *
     * A single settlement can (rarely) apply to more than one row if decision windows are long
     * enough to overlap two settlements' worth of history; [funding_events_applied] is
     * incremented per row so [finalizeIfBothResolved] can tell "this row's expected funding
     * events have now all arrived" apart from "at least one arrived".
     *
     * @return how many rows this settlement was applied to, for logging/telemetry.
     */
    fun backfillFundingSettlement(settledAt: Long, fundingComponent: Double): Int {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val candidateIds = mutableListOf<Long>()
            db.rawQuery(
                """
                SELECT $COL_ID FROM $TABLE
                WHERE $COL_FUNDING_RESOLVED = 0
                  AND $COL_TIMESTAMP_MS <= ?
                  AND ($COL_DELTA_V_RESOLVED = 0 OR $COL_NEXT_TIMESTAMP_MS >= ?)
                """.trimIndent(),
                arrayOf(settledAt.toString(), settledAt.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) candidateIds += cursor.getLong(0)
            }

            for (rowId in candidateIds) {
                db.execSQL(
                    """
                    UPDATE $TABLE
                    SET $COL_FUNDING_COMPONENT = $COL_FUNDING_COMPONENT + ?,
                        $COL_FUNDING_EVENTS_APPLIED = $COL_FUNDING_EVENTS_APPLIED + 1
                    WHERE $COL_ID = ?
                    """.trimIndent(),
                    arrayOf(fundingComponent, rowId),
                )
                finalizeIfBothResolved(db, rowId)
            }

            db.setTransactionSuccessful()
            return candidateIds.size
        } catch (e: Exception) {
            Log.w(TAG, "Failed to backfill funding settlement at $settledAt: ${e.message}")
            return 0
        } finally {
            db.endTransaction()
        }
    }

    /**
     * If both halves are in - `delta_v_resolved` and `funding_events_applied >=
     * expected_funding_events` - computes the combined `reward = market_reward_component -
     * funding_component` (design doc §3.5 sign convention: funding is a cost term, subtracted)
     * and flips `reward_status` to `resolved`. Called from inside the same transaction as
     * whichever backfill call just moved a row closer to done, so a row's status is never stale
     * between the two write paths.
     */
    private fun finalizeIfBothResolved(db: SQLiteDatabase, rowId: Long) {
        val row = queryRow(db, rowId) ?: return
        if (row.status == RewardStatus.RESOLVED) return
        val expected = row.expectedFundingEvents
        val fundingDone = expected != null && row.fundingEventsApplied >= expected
        if (row.deltaVResolved && fundingDone && row.marketRewardComponent != null) {
            val reward = row.marketRewardComponent - row.fundingComponent
            val values = ContentValues().apply {
                put(COL_REWARD, reward)
                put(COL_FUNDING_RESOLVED, 1)
                put(COL_REWARD_STATUS, STATUS_RESOLVED)
            }
            db.update(TABLE, values, "$COL_ID = ?", arrayOf(rowId.toString()))
        }
    }

    /**
     * Every fully-[RewardStatus.RESOLVED] row logged at or after [sinceMs], oldest first - what
     * the §3.3 batch job pulls to assemble rollout windows and compute GAE advantages. [sinceMs]
     * is normally "the timestamp of the last successful promotion", so a failed/discarded
     * candidate (§3.6 step 5: "keep the logged experience - it rolls forward into the next
     * scheduled attempt") simply gets re-pulled next time without needing its own bookkeeping.
     */
    fun resolvedRowsSince(sinceMs: Long): List<ResolvedExperience> {
        val out = mutableListOf<ResolvedExperience>()
        dbHelper.readableDatabase.rawQuery(
            """
            SELECT $COL_ID, $COL_TIMESTAMP_MS, $COL_SYMBOL, $COL_STATE_JSON, $COL_ACTION, $COL_LOG_PROB,
                   $COL_VALUE_ESTIMATE, $COL_NEXT_STATE_JSON, $COL_NEXT_TIMESTAMP_MS, $COL_REWARD
            FROM $TABLE
            WHERE $COL_REWARD_STATUS = ? AND $COL_TIMESTAMP_MS >= ?
            ORDER BY $COL_TIMESTAMP_MS ASC
            """.trimIndent(),
            arrayOf(STATUS_RESOLVED, sinceMs.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += ResolvedExperience(
                    id = cursor.getLong(0),
                    timestampMs = cursor.getLong(1),
                    symbol = cursor.getString(2),
                    state = cursor.getString(3).toDoubleArray(),
                    action = cursor.getDouble(4),
                    logProb = cursor.getDouble(5),
                    valueEstimate = cursor.getDouble(6),
                    nextState = cursor.getString(7).toDoubleArray(),
                    nextTimestampMs = cursor.getLong(8),
                    reward = cursor.getDouble(9),
                )
            }
        }
        return out
    }

    /** Count of rows still waiting on one or both halves - cheap health-check for UI/telemetry ("N transitions awaiting reward resolution"). */
    fun pendingCount(): Int {
        dbHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_REWARD_STATUS = ?",
            arrayOf(STATUS_PENDING),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * Deletes resolved rows older than [beforeMs]. Not called automatically anywhere - the log
     * is append-only by design (§3.6) and it's up to whatever drives the §3.3 batch job to prune
     * after a *successful* promotion has actually consumed a range via [resolvedRowsSince], the
     * same way [PolicyModelStore]-promoted models replace rather than accumulate. Pending rows
     * are never touched here regardless of age, since deleting one would silently drop
     * experience whose reward hasn't resolved yet rather than just deferring it.
     */
    fun deleteResolvedBefore(beforeMs: Long): Int =
        dbHelper.writableDatabase.delete(
            TABLE,
            "$COL_REWARD_STATUS = ? AND $COL_TIMESTAMP_MS < ?",
            arrayOf(STATUS_RESOLVED, beforeMs.toString()),
        )

    /** Wipes the entire log. Intended for the same kind of "reset local account" flow [LocalPaperTradingStore.clear] serves - not for routine use. */
    fun clear() {
        dbHelper.writableDatabase.delete(TABLE, null, null)
    }

    private data class RowSnapshot(
        val timestampMs: Long,
        val deltaVResolved: Boolean,
        val marketRewardComponent: Double?,
        val expectedFundingEvents: Int?,
        val fundingEventsApplied: Int,
        val fundingComponent: Double,
        val status: RewardStatus,
    )

    private fun queryRow(db: SQLiteDatabase, rowId: Long): RowSnapshot? {
        db.rawQuery(
            """
            SELECT $COL_TIMESTAMP_MS, $COL_DELTA_V_RESOLVED, $COL_MARKET_REWARD_COMPONENT,
                   $COL_EXPECTED_FUNDING_EVENTS, $COL_FUNDING_EVENTS_APPLIED, $COL_FUNDING_COMPONENT, $COL_REWARD_STATUS
            FROM $TABLE WHERE $COL_ID = ?
            """.trimIndent(),
            arrayOf(rowId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return RowSnapshot(
                timestampMs = cursor.getLong(0),
                deltaVResolved = cursor.getInt(1) != 0,
                marketRewardComponent = if (cursor.isNull(2)) null else cursor.getDouble(2),
                expectedFundingEvents = if (cursor.isNull(3)) null else cursor.getInt(3),
                fundingEventsApplied = cursor.getInt(4),
                fundingComponent = cursor.getDouble(5),
                status = RewardStatus.fromColumn(cursor.getString(6)),
            )
        }
    }
}
