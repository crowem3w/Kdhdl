package org.example.syncora.perf

import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import java.util.ArrayDeque

data class PerformanceSnapshot(
    val fpsLabel: String,
    val fpsHealthy: Boolean,
    val latencyLabel: String,
    val latencyHealthy: Boolean,
    val cpuFrameLabel: String,
    val cpuFrameHealthy: Boolean,
)

class PerformanceMonitor(
    context: Context,
    private val onUpdate: (PerformanceSnapshot) -> Unit,
) {

    private companion object {
        const val FPS_WINDOW_NANOS = 1_000_000_000L
        const val DROPPED_WINDOW_NANOS = 3_000_000_000L
        const val FREEZE_THRESHOLD_NANOS = 700_000_000L
        const val FREEZE_HOLD_NANOS = 3_000_000_000L
        const val CPU_SAMPLE_CAPACITY = 30
        const val TOUCH_SAMPLE_CAPACITY = 10
        const val PUBLISH_INTERVAL_NANOS = 300_000_000L
        const val DEFAULT_REFRESH_RATE_HZ = 60f
        const val MIN_HEALTHY_FPS = 45
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

    private val frameTimestamps = ArrayDeque<Long>()
    private val droppedFrameTimestamps = ArrayDeque<Long>()

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

    fun start() {
        if (running) return
        running = true
        lastFrameTimeNanos = -1L
        lastPublishNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    fun notifyTouchEvent() {
        if (pendingTouchStartNanos < 0L) {
            pendingTouchStartNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

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
