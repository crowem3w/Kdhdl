package org.example.syncora.chart

/**
 * A single sample of the RRL agent's performance over time, e.g. (step index, cumulative reward)
 * or (timestamp, equity). [x] and [y] are plain floats so the chart stays agnostic to whatever
 * metric the caller wants to visualize.
 */
data class AgentPerformancePoint(
    val x: Float,
    val y: Float,
)
