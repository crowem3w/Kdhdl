package org.example.syncora.resilience

import android.content.Context

/**
 * Disk-backed checkpoint the harness uses to hand state across ordered test
 * phases that must run as *separate* instrumentation invocations - see
 * `package-info.kt` gap #5 and [PipelineResilienceTest]'s class kdoc for
 * why: standard Android instrumentation always shares its process with the
 * app under test, so an ungraceful kill (`am force-stop`) can only ever be
 * simulated *between* two separately launched `am instrument` invocations,
 * never from inside one continuous test method without killing that
 * method's own JVM along with the app it's testing.
 *
 * Plain `SharedPreferences` (not [ExperienceLogStore]'s SQLite database, not
 * an in-memory field) precisely because it has to survive the same
 * `am force-stop` the harness deliberately inflicts on the process between
 * phases - the whole point is that this state, unlike a test class's normal
 * `@Before`-initialized fields, outlives the kill.
 */
class ResiliencePhaseState(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class Phase { NOT_STARTED, A_DONE, B_DONE, C_DONE }

    /** Which phase most recently completed successfully. */
    var phase: Phase
        get() = Phase.valueOf(prefs.getString(KEY_PHASE, Phase.NOT_STARTED.name)!!)
        set(value) = prefs.edit().putString(KEY_PHASE, value.name).apply()

    /** [SimulatedClock]'s value as of the end of the most recently completed phase. */
    var clockMillis: Long
        get() = prefs.getLong(KEY_CLOCK, -1L)
        set(value) = prefs.edit().putLong(KEY_CLOCK, value).apply()

    /**
     * `android.os.Process.myPid()` as of the end of the most recently
     * completed phase - the mechanism [PipelineResilienceTest] uses to
     * hard-assert a real kill actually happened between phases, rather than
     * silently passing if someone runs the whole test class in one
     * instrumentation session (which never kills anything).
     */
    var processPid: Int
        get() = prefs.getInt(KEY_PID, -1)
        set(value) = prefs.edit().putInt(KEY_PID, value).apply()

    /** Wipes the checkpoint so the next `phaseA` run starts a genuinely fresh scenario. */
    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "resilience_test_phase_state"
        const val KEY_PHASE = "phase"
        const val KEY_CLOCK = "clock_millis"
        const val KEY_PID = "process_pid"
    }
}
