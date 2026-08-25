package org.example.syncora.resilience

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/**
 * Forces process death without going through `onDestroy()` - the realistic
 * OS/OEM-killer failure mode Phase 0 established as a certainty, not a
 * graceful shutdown path.
 *
 * The design doc's original sketch used `am kill` - deliberately *not*
 * used here. `am kill` only terminates processes Android considers safe
 * cache victims, and explicitly skips anything holding a foreground
 * service; since [org.example.syncora.service.MarketDataForegroundService]
 * is running the entire time this harness is active, `am kill` against
 * this app is a silent no-op, not a kill. `am force-stop` is the shell
 * primitive that actually guarantees termination regardless of foreground
 * status (the same mechanism behind Settings > Force Stop) - still no
 * `onDestroy()` call, still the realistic "OS decided this process is
 * gone" shape the test needs, just reliable against a foreground service.
 *
 * **Not called from [PipelineResilienceTest] itself.** Standard Android
 * instrumentation shares its process with the app under test, so calling
 * [killProcessUngracefully] from inside a running test method kills that
 * method's own JVM along with the app, mid-assertion - see
 * `package-info.kt` gap #5 and [PipelineResilienceTest]'s class kdoc.
 * `scripts/run_pipeline_resilience_test.sh` performs the equivalent
 * `adb shell am force-stop` + relaunch from the host, between separately
 * launched phase invocations, instead. This class is kept for smaller,
 * single-method instrumented tests that don't need the multi-phase
 * checkpoint dance - e.g. "app survives one kill and reconnects" without
 * the full multi-day scenario - where killing the test's own process is an
 * acceptable, expected way for that one test method to end.
 */
class ProcessKillSimulator(private val device: UiDevice, private val packageName: String) {

    fun killProcessUngracefully() {
        device.executeShellCommand("am force-stop $packageName")
    }

    fun relaunchApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("No launch intent found for $packageName")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
