package org.example.syncora.perf

import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import java.util.ArrayDeque

/**
 * Snapshot of the three rendering-health rows shown in the chart HUD.
 * `healthy == true` maps to the "Smooth" (green) state; `false` maps to one
 * of the degraded (red) states — Laggy/Sluggish, Janky/Choppy, or
 * Unstable/Unresponsive, depending on which signal tripped.
 */
data class PerformanceSnapshot(
    val fpsLabel: String,
    val fpsHealthy: Boolean,
    val latencyLabel: String,
    val latencyHealthy: Boolean,
    val cpuFrameLabel: String,
    val cpuFrameHealthy: Boolean,
)

/**
 * Always-on rendering-health monitor for the chart canvas.
 *
 * Three independent signals are tracked, each corresponding to one of the
 * degraded conditions the HUD is meant to surface:
 *
 *  - **FPS / dropped frames** — derived from Choreographer vsync deltas.
 *    A frame is considered "dropped" once its delta exceeds 1.5x the
 *    display's expected frame interval (the standard definition of a
 *    missed frame), which is what shows up as Janky/Choppy: dropped
 *    frames, micro-stutters, erratic frame pacing. A single very long
 *    delta (>700ms) is treated as a freeze and flags Unstable/Unresponsive
 *    for a few seconds afterward.
 *
 *  - **CPU frame time** — wall-clock duration of
 *    [CandlestickChartView.onDraw], i.e. how much main-thread work each
 *    frame actually costs. Eating too much of the frame budget or an
 *    outright stall reads as Laggy/Sluggish (slow processing) or
 *    Unstable/Unresponsive (hangs under load).
 *
 *  - **Input / touch latency** — elapsed time between a touch being
 *    delivered to the chart and the next completed draw pass that
 *    responds to it. A slow round trip reads as Laggy/Sluggish: high
 *    input latency, delayed touch response.
 *
 * Smooth (green) is the default; any signal crossing its threshold flips
 * just that row to red.
 */
class PerformanceMonitor(
    context: Context,
    private val onUpdate: (PerformanceSnapshot) -> Unit,
) {

    private companion object {
        const val FPS_WINDOW_NANOS = 1_000_000_000L // 1s sliding window for FPS
        const val DROPPED_WINDOW_NANOS = 3_000_000_000L // 3s sliding window for dropped-frame count
        const val FREEZE_THRESHOLD_NANOS = 700_000_000L // single-frame stall considered a freeze
        const val FREEZE_HOLD_NANOS = 3_000_000_000L // how long a freeze keeps the row red
        const val CPU_SAMPLE_CAPACITY = 30
        const val TOUCH_SAMPLE_CAPACITY = 10
        const val PUBLISH_INTERVAL_NANOS = 300_000_000L // throttle UI updates to ~3/s
        const val DEFAULT_REFRESH_RATE_HZ = 60f
        const val MIN_HEALTHY_FPS = 45 // FPS row reads green at/above this, red below it
    }

    private val choreographer = Choreographer.getInstance()

    private val expectedFrameIntervalNanos: Long = run {
        val refreshRate = context.display?.refreshRate?.takeIf { it > 0f } ?: DEFAULT_REFRESH_RATE_HZ
        (1_000_000_000.0 / refreshRate).toLong()
    }

    private var running = false
    private var lastFrameTimeNanos = -1L
    private var lastPublishNanos = 0L
    private var freezeUntilNanos = 0L

    // Frame-pacing state (all timestamps in Choreographer's nanoTime timebase).
    private val frameTimestamps = ArrayDeque<Long>()
    private val droppedFrameTimestamps = ArrayDeque<Long>()

    // CPU + touch state (all timestamps in SystemClock.elapsedRealtimeNanos timebase).
    private val cpuFrameSamplesNanos = ArrayDeque<Long>()
    private val touchLatencySamplesNanos = ArrayDeque<Long>()
    private var pendingTouchStartNanos = -1L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return

            if (lastFrameTimeNanos > 0L) {
                val deltaNanos = frameTimeNanos - lastFrameTimeNanos
                frameTimestamps.addLast(frameTimeNanos)
                trimOlderThan(frameTimestamps, frameTimeNanos, FPS_WINDOW_NANOS)

                if (deltaNanos >= FREEZE_THRESHOLD_NANOS) {
                    freezeUntilNanos = frameTimeNanos + FREEZE_HOLD_NANOS
                } else if (deltaNanos > (expectedFrameIntervalNanos * 1.5).toLong()) {
                    droppedFrameTimestamps.addLast(frameTimeNanos)
                }
                trimOlderThan(droppedFrameTimestamps, frameTimeNanos, DROPPED_WINDOW_NANOS)
            }
            lastFrameTimeNanos = frameTimeNanos

            if (frameTimeNanos - lastPublishNanos >= PUBLISH_INTERVAL_NANOS) {
                lastPublishNanos = frameTimeNanos
                publish(frameTimeNanos)
            }

            choreographer.postFrameCallback(this)
        }
    }

    /** Begins the Choreographer frame-callback loop. Safe to call repeatedly. */
    fun start() {
        if (running) return
        running = true
        lastFrameTimeNanos = -1L
        lastPublishNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    /** Stops the frame-callback loop; call from onPause/onStop to avoid leaking work. */
    fun stop() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    /** Call from the chart's onTouchEvent for every event, before it's otherwise handled. */
    fun notifyTouchEvent() {
        if (pendingTouchStartNanos < 0L) {
            pendingTouchStartNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

    /** Call once per onDraw with the wall-clock duration of that draw pass, in nanoseconds. */
    fun notifyDrawCompleted(drawDurationNanos: Long) {
        cpuFrameSamplesNanos.addLast(drawDurationNanos)
        while (cpuFrameSamplesNanos.size > CPU_SAMPLE_CAPACITY) cpuFrameSamplesNanos.removeFirst()

        if (pendingTouchStartNanos >= 0L) {
            val latencyNanos = SystemClock.elapsedRealtimeNanos() - pendingTouchStartNanos
            touchLatencySamplesNanos.addLast(latencyNanos)
            while (touchLatencySamplesNanos.size > TOUCH_SAMPLE_CAPACITY) touchLatencySamplesNanos.removeFirst()
            pendingTouchStartNanos = -1L
        }
    }

    private fun trimOlderThan(deque: ArrayDeque<Long>, nowNanos: Long, windowNanos: Long) {
        while (deque.isNotEmpty() && nowNanos - deque.first() > windowNanos) {
            deque.removeFirst()
        }
    }

    private fun publish(nowNanos: Long) {
        val currentFps = frameTimestamps.size
        val droppedCount = droppedFrameTimestamps.size
        val isFrozen = nowNanos < freezeUntilNanos
        val fpsHealthy = !isFrozen && droppedCount == 0 && currentFps >= MIN_HEALTHY_FPS

        val frameBudgetNanos = expectedFrameIntervalNanos
        val avgCpuNanos = cpuFrameSamplesNanos.average(default = 0L)
        val lastCpuNanos = cpuFrameSamplesNanos.lastOrNull() ?: 0L
        val cpuHealthy = cpuFrameSamplesNanos.isEmpty() ||
            (avgCpuNanos <= frameBudgetNanos * 0.7 && lastCpuNanos < 100_000_000L)
        val avgCpuMs = avgCpuNanos / 1_000_000.0

        val avgLatencyNanos = touchLatencySamplesNanos.average(default = 0L)
        val latencyHealthy = touchLatencySamplesNanos.isEmpty() || avgLatencyNanos <= 100_000_000L
        val avgLatencyMs = avgLatencyNanos / 1_000_000.0

        onUpdate(
            PerformanceSnapshot(
                fpsLabel = "F %dfps".format(currentFps),
                fpsHealthy = fpsHealthy,
                latencyLabel = if (touchLatencySamplesNanos.isEmpty()) {
                    "T —ms"
                } else {
                    "T %.0fms".format(avgLatencyMs)
                },
                latencyHealthy = latencyHealthy,
                cpuFrameLabel = if (cpuFrameSamplesNanos.isEmpty()) {
                    "C —ms"
                } else {
                    "C %.1fms".format(avgCpuMs)
                },
                cpuFrameHealthy = cpuHealthy,
            ),
        )
    }

    private fun ArrayDeque<Long>.average(default: Long): Double {
        if (isEmpty()) return default.toDouble()
        var sum = 0.0
        for (v in this) sum += v
        return sum / size
    }
}
