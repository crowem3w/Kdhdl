package org.example.syncora.agent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the REAL [ExperienceLogStore] class (not a Python port) against Robolectric's
 * shadowed `android.database.sqlite`, which under Robolectric 4.x is backed by a real, on-disk
 * SQLite file - not an in-memory fake - so the durability behavior under test is genuine SQLite
 * behavior, not a simulation of it.
 *
 * **Important caveat on what "kill" means here.** This runs on the JVM in the same process as
 * the test itself, so it cannot literally `SIGKILL` a separate OS process the way a real Android
 * app death does. The closest same-process approximation: open a second raw connection to the
 * exact same database file, start a transaction, make an uncommitted write, and abruptly close
 * that connection without ever calling `setTransactionSuccessful()` - which forces SQLite's
 * rollback-journal recovery path on next open, the same recovery mechanism a real process death
 * relies on. If you need the literal cross-process/OS-level guarantee, that has to be a
 * `connectedAndroidTest` running on a real device/emulator that gets its process actually killed
 * (e.g. via `adb shell am force-stop` or `kill -9` on its PID) and restarted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExperienceLogStoreKillRestartTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Mirrors ExperienceLogStore's private DB_NAME constant - kept in sync manually since the
    // real constant isn't exposed; if that constant ever changes, update this too.
    private val dbFileName = "experience_log.db"

    @Test
    fun `committed rows survive an abrupt same-process kill mid-transaction`() {
        val store = ExperienceLogStore(context)
        store.clear()

        // Commit one fully-resolved row through the real public API, exactly as
        // DecisionLoopScheduler would.
        val id1 = store.logDecision(
            PendingExperienceEntry(
                timestampMs = 0L, symbol = "BTCUSDT",
                state = doubleArrayOf(1.0), action = 0.1, logProb = -0.5, valueEstimate = 0.2,
            ),
        )
        store.backfillDeltaV(id1, nextState = doubleArrayOf(1.1), nextTimestampMs = 300_000L, marketRewardComponent = 0.05)
        assertEquals("sanity check: the committed row should be resolved before the kill", 1, store.resolvedRowsSince(0L).size)

        // Release the store's own connection before simulating the kill. Leaving it open would
        // mean three separate connections (store, rawDb, restarted) to the same file inside one
        // JVM process at once - not representative of the real single-owner-per-process scenario
        // this test is trying to approximate, and a needless source of same-process lock noise.
        store.close()

        // Simulate a kill mid-transaction: open the SAME underlying file with a raw connection,
        // start a transaction, make an uncommitted insert, and abandon it (close without
        // committing) - see class doc for why this is the same-process approximation.
        val dbPath = context.getDatabasePath(dbFileName).absolutePath
        val rawDb = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            rawDb.beginTransaction()
            val phantomRow = ContentValues().apply {
                put("symbol", "BTCUSDT")
                put("timestamp_ms", 999_000L)
                put("state_json", "[9.9]")
                put("action", 0.9)
                put("log_prob", -0.9)
                put("value_estimate", 0.9)
            }
            rawDb.insert("experience_log", null, phantomRow)
            // Deliberately no setTransactionSuccessful()/endTransaction() - the transaction is
            // left open when the connection below is torn down, same as a process dying mid-write.
        } finally {
            rawDb.close()
        }

        // Reopen a fresh ExperienceLogStore against the same file, as the app does after restart.
        val restarted = ExperienceLogStore(context)

        val resolvedAfterRestart = restarted.resolvedRowsSince(0L)
        assertEquals("the row committed BEFORE the kill must still be there and still resolved", 1, resolvedAfterRestart.size)
        assertEquals(id1, resolvedAfterRestart.first().id)

        assertEquals(
            "the uncommitted in-flight insert must not leak through as a phantom row after restart",
            0,
            restarted.pendingCount(),
        )
    }
}
