package org.example.syncora.diagnostics

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.example.syncora.R
import org.example.syncora.agent.ConfigPerformance
import org.example.syncora.agent.ConfigSplitScore
import org.example.syncora.agent.CpcvPboValidationGate
import org.example.syncora.agent.ExperienceLogStore
import org.example.syncora.agent.GateDecision
import org.example.syncora.agent.PendingExperienceEntry
import org.example.syncora.ml.PpoHyperparameters
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * On-device verification screen for goals 2 and 3 (see project chat history / task 8 sanity
 * check), for the case where there's no separate computer to run `./gradlew test` or
 * `connectedAndroidTest` from - only the phone itself.
 *
 * Has its own LAUNCHER entry (see AndroidManifest.xml) so it's reachable directly from the home
 * screen/app drawer without touching MainActivity's UI.
 *
 * **Goal 3 (gate rejects overfit candidates):** calls the real [CpcvPboValidationGate] directly,
 * in-process, with a synthetic genuine-edge scenario (expect PASS) and a synthetic overfit
 * scenario (expect REJECT). Runs instantly, no restart needed.
 *
 * **Goal 2 (no data loss across kills):** calls the real [ExperienceLogStore] to write rows in a
 * background loop, then calls [Process.killProcess] on this app's own PID - a genuine, immediate
 * OS-level process death, the same signal an OEM background killer sends. There is no way to
 * report a result at that instant (the process is gone), so a flag is persisted to
 * [android.content.SharedPreferences] just before the kill; the *next* time this screen is
 * opened, it detects that flag, reopens [ExperienceLogStore] against the same on-disk database,
 * and reports whether every invariant still holds. Tapping "Run kill test" repeatedly across
 * separate app launches accumulates a running kill/restart survival count, the on-device
 * equivalent of the earlier multi-cycle harness.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var logView: TextView

    private companion object {
        const val PREFS_NAME = "syncora_diagnostics"
        const val KEY_KILL_TEST_PENDING = "kill_test_pending"
        const val KEY_PREV_RESOLVED_COUNT = "prev_resolved_count"
        const val KEY_PREV_TOTAL_COUNT = "prev_total_count"
        const val KEY_CYCLES_SURVIVED = "cycles_survived"
        const val EXPERIENCE_DB_NAME = "experience_log.db" // mirrors ExperienceLogStore's private DB_NAME
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        logView = findViewById(R.id.logTextView)
        findViewById<Button>(R.id.runGateCheckButton).setOnClickListener { runGateCheck() }
        findViewById<Button>(R.id.runKillTestButton).setOnClickListener { runKillTest() }

        reportPendingKillTestResultIfAny()
    }

    private fun log(line: String) {
        logView.text = "${logView.text}\n$line"
    }

    // ---- Goal 3: gate rejects an overfit candidate -------------------------------------------

    private fun genuineEdgeConfigs(splits: Int = 8, configs: Int = 6, seed: Int = 1): List<ConfigPerformance> {
        val rng = Random(seed)
        return (0 until configs).map { i ->
            val trueSkill = if (i == 0) 1.0 else rng.nextDouble(-0.1, 0.15)
            val scores = (0 until splits).map { s ->
                ConfigSplitScore(
                    splitIndex = s,
                    inSampleScore = trueSkill + rng.nextDouble(-0.1, 0.1),
                    outOfSampleScore = trueSkill + rng.nextDouble(-0.1, 0.1),
                )
            }
            ConfigPerformance(hyperparameters = PpoHyperparameters(learningRate = (i + 1) * 1e-4f), splitScores = scores)
        }
    }

    private fun overfitConfigs(splits: Int = 8, configs: Int = 6, seed: Int = 2): List<ConfigPerformance> {
        val rng = Random(seed)
        return (0 until configs).map { i ->
            val scores = (0 until splits).map { s ->
                if (i == 0) {
                    ConfigSplitScore(splitIndex = s, inSampleScore = 10.0 + rng.nextDouble(-0.01, 0.01), outOfSampleScore = rng.nextDouble(-1.0, 1.0))
                } else {
                    ConfigSplitScore(splitIndex = s, inSampleScore = rng.nextDouble(-1.0, 1.0), outOfSampleScore = rng.nextDouble(-1.0, 1.0))
                }
            }
            ConfigPerformance(hyperparameters = PpoHyperparameters(learningRate = (i + 1) * 1e-4f), splitScores = scores)
        }
    }

    private fun runGateCheck() {
        val gate = CpcvPboValidationGate(alpha = 0.10)
        log("\n--- Gate check (real CpcvPboValidationGate) ---")

        val genuineDecision = gate.decide(genuineEdgeConfigs())
        val genuineOk = genuineDecision is GateDecision.Pass
        log("Genuine-edge candidate: $genuineDecision")
        log(if (genuineOk) "  [OK] passed as expected" else "  [FAIL] expected PASS")

        val overfitDecision = gate.decide(overfitConfigs())
        val overfitOk = overfitDecision is GateDecision.Reject
        log("Overfit candidate:      $overfitDecision")
        log(if (overfitOk) "  [OK] rejected as expected" else "  [FAIL] expected REJECT")

        log(if (genuineOk && overfitOk) "RESULT: PASS" else "RESULT: FAIL")
    }

    // ---- Goal 2: no data loss across a real, on-device process kill --------------------------

    private fun runKillTest() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // Written synchronously (commit, not apply) because the process is about to die - an
        // async apply() could lose the flag write itself if the kill lands before it flushes.
        prefs.edit().putBoolean(KEY_KILL_TEST_PENDING, true).commit()

        log("\n--- Kill test starting: writing rows in the background, then killing this process. ---")
        log("Reopen the app (tap the icon again) to see the survival report.")

        thread {
            val store = ExperienceLogStore(applicationContext)
            val rng = Random(System.nanoTime())
            var prevId: Long? = null
            var tick = 0L
            val burstMs = rng.nextInt(150, 1200)
            val deadline = System.currentTimeMillis() + burstMs
            while (System.currentTimeMillis() < deadline) {
                val ts = tick * 300_000L
                val id = store.logDecision(
                    PendingExperienceEntry(
                        timestampMs = ts, symbol = "BTCUSDT",
                        state = doubleArrayOf(rng.nextDouble()), action = rng.nextDouble(-1.0, 1.0),
                        logProb = rng.nextDouble(-2.0, 0.0), valueEstimate = rng.nextDouble(-1.0, 1.0),
                    ),
                )
                prevId?.let { store.backfillDeltaV(it, doubleArrayOf(rng.nextDouble()), ts, rng.nextDouble(-0.5, 0.5)) }
                prevId = id
                tick++
            }
            // Real, immediate, OS-level process death - the same signal an OEM background
            // killer sends. Nothing after this line runs.
            Process.killProcess(Process.myPid())
        }
    }

    private fun reportPendingKillTestResultIfAny() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_KILL_TEST_PENDING, false)) return
        prefs.edit().putBoolean(KEY_KILL_TEST_PENDING, false).apply()

        log("\n--- Kill test result (app was just killed and relaunched) ---")

        val dbFile = getDatabasePath(EXPERIENCE_DB_NAME)
        if (!dbFile.exists()) {
            log("No database file found - the process was likely killed before any row was ever committed.")
            return
        }

        val integrityOk = checkIntegrity(dbFile.absolutePath)
        val store = ExperienceLogStore(applicationContext)
        val resolvedCount = store.resolvedRowsSince(0L).size
        val pendingCount = store.pendingCount()
        val totalCount = resolvedCount + pendingCount

        val prevResolved = prefs.getInt(KEY_PREV_RESOLVED_COUNT, 0)
        val prevTotal = prefs.getInt(KEY_PREV_TOTAL_COUNT, 0)
        val regressed = resolvedCount < prevResolved || totalCount < prevTotal
        val cyclesSurvived = prefs.getInt(KEY_CYCLES_SURVIVED, 0) + if (integrityOk && !regressed) 1 else 0

        prefs.edit()
            .putInt(KEY_PREV_RESOLVED_COUNT, resolvedCount)
            .putInt(KEY_PREV_TOTAL_COUNT, totalCount)
            .putInt(KEY_CYCLES_SURVIVED, cyclesSurvived)
            .apply()

        log("integrity_check: ${if (integrityOk) "ok" else "FAILED"}")
        log("resolved rows: $resolvedCount (previous cycle: $prevResolved)")
        log("total rows:    $totalCount (previous cycle: $prevTotal)")
        log(if (regressed) "  [FAIL] row counts regressed after restart - possible data loss" else "  [OK] counts did not regress across the kill")
        log("Cumulative kill/restart cycles survived (this device): $cyclesSurvived")
        log(if (integrityOk && !regressed) "RESULT: PASS" else "RESULT: FAIL")
    }

    private fun checkIntegrity(dbPath: String): Boolean {
        val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == "ok"
            }
        } finally {
            db.close()
        }
    }
}
