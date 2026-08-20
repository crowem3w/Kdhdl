package org.example.test.splash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.example.test.MainActivity
import org.example.test.SyncoraApplication
import org.example.test.R
import org.example.test.bitget.PipelineState
import org.example.test.onboarding.OnboardingActivity
import org.example.test.onboarding.OnboardingPreferences
import org.example.test.orb.OrbView

/**
 * Splash always shows first on every cold app open. It stays on screen — orb animating,
 * pagination dots filling in milestone by milestone — until the initial chart data has
 * actually arrived (or keeps retrying quietly if there's no internet), then hands off to
 * either Onboarding (first-ever open only) or straight to the home chart screen (every
 * subsequent open).
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var orbView: OrbView
    private lateinit var app: SyncoraApplication
    private lateinit var onboardingPreferences: OnboardingPreferences

    private var hasNavigated = false

    // Milestone progress indicator: 5 discrete dots, together covering 100% of the
    // loading process but not evenly weighted (see MILESTONE_WEIGHTS — the cumulative
    // checkpoints are 70%, 84.5%, 94.17%, 99%, 100%, so the first dot alone stands for
    // 70%). Dots to the left of the current one are permanently active (that milestone
    // is done); the current dot pulses (fades out and back in) to show work happening
    // within its slice; dots to the right haven't started yet. The per-dot timing is
    // only a pacing simulation, though — it's kept honest against what's actually
    // happening: if the network drops mid-slice, the active dot holds in place until
    // connectivity recovers, and if the homepage is actually ready before the simulated
    // timing finishes, remaining dots fast-forward to catch up instead of grinding out
    // a stale timer (see awaitSegmentRespectingNetworkAndReadiness).
    private lateinit var pageIndicatorDots: List<View>
    private var progressJob: Job? = null
    private val entryAnimators = arrayOfNulls<ValueAnimator>(PAGE_INDICATOR_DOT_COUNT)
    private val pulseAnimators = arrayOfNulls<ValueAnimator>(PAGE_INDICATOR_DOT_COUNT)
    private val dotColors = IntArray(PAGE_INDICATOR_DOT_COUNT) { INACTIVE_DOT_COLOR }
    private val argbEvaluator = ArgbEvaluator()

    private val latencyProbe = NetworkLatencyProbe()
    // Recomputed once per run from the measured round trip; read by startPulseLoop()
    // so every dot's pulse rate reflects the same network read.
    private var pulseHalfCycleMs = MAX_SEGMENT_DURATION_MS / 2

    // Instant (OS-level) connectivity signal used to freeze dot progression the moment
    // the connection drops — see awaitSegmentRespectingNetworkAndReadiness() for why this
    // has to be an immediate signal rather than something derived from a REST call timing
    // out.
    private lateinit var connectivityObserver: NetworkConnectivityObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        app = application as SyncoraApplication
        onboardingPreferences = OnboardingPreferences(this)
        connectivityObserver = NetworkConnectivityObserver(this)

        orbView = findViewById(R.id.orbView)
        orbView.setBackgroundColorHex("#000000")
        orbView.hue = 0f
        orbView.hoverIntensity = 0.1f
        orbView.rotateOnHover = true
        // Same treatment as onboarding: no explicit "hover" affordance to teach here
        // either, so the orb animates on its own.
        orbView.forceHoverState = true

        // Brand palette: bright near-white teal + brand teal in the outer glow, with a
        // near-black teal anchoring the shadow side, matching the onboarding orb exactly.
        orbView.setColor1Hex("#26C7C3")
        orbView.setColor2Hex("#BFF6F2")
        orbView.setColor3Hex("#02201F")
        orbView.saturation = 0.75f

        pageIndicatorDots = listOf(
            findViewById(R.id.pageIndicatorDot1),
            findViewById(R.id.pageIndicatorDot2),
            findViewById(R.id.pageIndicatorDot3),
            findViewById(R.id.pageIndicatorDot4),
            findViewById(R.id.pageIndicatorDot5),
        )
        // All 5 start pending (dim/small); the progress run drives them from here.
        pageIndicatorDots.forEachIndexed { index, dot -> snapDotPending(dot, index) }

        // Kick the shared connection off immediately (no-op if it's already running) so
        // it's warming up while the splash animation plays.
        app.ensureMarketDataStarted()
    }

    override fun onResume() {
        super.onResume()
        orbView.onResume()
        connectivityObserver.start()
        startMilestoneProgress()
    }

    override fun onPause() {
        cancelMilestoneProgress()
        connectivityObserver.stop()
        orbView.onPause()
        super.onPause()
    }

    /**
     * Drives the 5 dots through their milestones in order. The first 4 milestones each
     * take a slice of time sized to how the network is behaving right now — measured
     * once, up front, via [NetworkLatencyProbe] — so the animation itself communicates
     * connection quality: fast network, fast dots; slow network, slow dots, capped at
     * [MAX_SEGMENT_DURATION_MS] (1 second) per dot so it never stalls out completely.
     * The 5th and final milestone is different: it doesn't complete on a timer at all —
     * it waits for the chart pipeline to actually report its first live snapshot, so the
     * indicator never claims to be fully loaded before the app really is.
     *
     * The per-dot timing is a *pacing simulation*, not the source of truth — the source of
     * truth is always [app.pipeline.pipelineState]. Two things keep the two in sync, both
     * handled in [awaitSegmentRespectingNetworkAndReadiness]:
     *  - If there's no usable internet, the currently active dot's timer holds in place
     *    (still pulsing, not advancing) until connectivity returns — see
     *    [NetworkConnectivityObserver]. This applies from the very first dot, so opening
     *    the app with no connection at all holds indefinitely on dot 1 rather than
     *    sailing through it on a timer.
     *  - If the pipeline reports [PipelineState.LIVE] — the homepage is actually ready —
     *    before the simulated timing would have finished, every remaining dot fast-forwards
     *    through a short catch-up flash instead of grinding out its full slice, so the
     *    indicator never sits there faking a load that's already done.
     */
    private fun startMilestoneProgress() {
        cancelMilestoneProgress()
        hasNavigated = false
        pageIndicatorDots.forEachIndexed { index, dot -> snapDotPending(dot, index) }

        progressJob = lifecycleScope.launch {
            val roundTripMs = withTimeoutOrNull(MAX_SEGMENT_DURATION_MS) { latencyProbe.measureRoundTripMs() }
            val segmentDurationMs = (roundTripMs ?: MAX_SEGMENT_DURATION_MS)
                .coerceIn(MIN_SEGMENT_DURATION_MS, MAX_SEGMENT_DURATION_MS)
            pulseHalfCycleMs = segmentDurationMs / 2

            for (index in pageIndicatorDots.indices) {
                activateAndPulse(index)
                val isFinalMilestone = index == pageIndicatorDots.lastIndex
                if (isFinalMilestone) {
                    // Suspends here — no fixed duration — until the pipeline actually
                    // reports its first live snapshot. This is the ground truth the
                    // whole indicator is ultimately answering to.
                    app.pipeline.pipelineState.first { it == PipelineState.LIVE }
                } else {
                    awaitSegmentRespectingNetworkAndReadiness(weightedSegmentDurationMs(index, segmentDurationMs))
                }
                settleDotAsComplete(index)
            }
            navigateOnward()
        }
    }

    /** Which real-world condition should currently be steering a dot's countdown. */
    private enum class SegmentSignal {
        /** No usable internet right now — hold the dot in place, don't spend its time. */
        OFFLINE,

        /** The pipeline already reports LIVE — stop simulating, catch up fast. */
        READY,

        /** Normal case — nothing unusual, let the simulated timer run. */
        RUNNING,
    }

    /** Merges connectivity + real pipeline readiness into the single signal that matters. */
    private fun segmentSignal(): Flow<SegmentSignal> =
        combine(connectivityObserver.isConnected, app.pipeline.pipelineState) { connected, pipelineState ->
            when {
                pipelineState == PipelineState.LIVE -> SegmentSignal.READY
                !connected -> SegmentSignal.OFFLINE
                else -> SegmentSignal.RUNNING
            }
        }

    /**
     * Counts down [durationMs] for the current dot, but keeps that countdown honest against
     * what's actually happening rather than treating it as a fixed animation:
     *  - While offline, the countdown holds in place — no time is spent — until connectivity
     *    returns, so the indicator can never advance past the dot it was on when the
     *    connection dropped (or, if there was never a connection, never advance past dot 1).
     *  - The moment the pipeline reports real readiness ([PipelineState.LIVE]), the countdown
     *    stops simulating entirely and fast-forwards through a short, still-visible catch-up
     *    flash instead — because at that point the "loading" the timer was simulating has
     *    actually finished, and continuing to grind through a stale timer would make the
     *    indicator lie about the app's real state.
     *
     * Deliberately keyed off [NetworkConnectivityObserver] and [app.pipeline.pipelineState]
     * directly, rather than the pipeline's REST retry state: a failed request can take
     * several seconds to time out — far longer than a single dot's segment — so reacting to
     * that would routinely be too slow to matter. Both signals here update within
     * milliseconds of the real condition changing.
     */
    private suspend fun awaitSegmentRespectingNetworkAndReadiness(durationMs: Long) {
        var remainingMs = durationMs
        while (remainingMs > 0) {
            when (segmentSignal().first()) {
                SegmentSignal.READY -> {
                    // Real data is already here — don't keep faking progress, just give
                    // the dot a brief, visible beat before locking in as complete.
                    delay(minOf(remainingMs, ACCELERATED_CATCHUP_DURATION_MS))
                    return
                }
                SegmentSignal.OFFLINE -> {
                    // Hold here — none of the segment's remaining time is spent — until
                    // we're back online (or, in the rare case, until real data somehow
                    // shows up anyway).
                    segmentSignal().first { it != SegmentSignal.OFFLINE }
                }
                SegmentSignal.RUNNING -> {
                    val sliceStartedAt = System.currentTimeMillis()
                    val interrupted = withTimeoutOrNull(remainingMs) {
                        segmentSignal().first { it != SegmentSignal.RUNNING }
                    }
                    val elapsedMs = System.currentTimeMillis() - sliceStartedAt
                    remainingMs = (remainingMs - elapsedMs).coerceAtLeast(0)
                    if (interrupted == null) {
                        // The full remaining slice elapsed with nothing unusual happening
                        // — segment complete, on its normal simulated pace.
                        return
                    }
                    // Otherwise the signal changed (went offline, or became ready) with
                    // time still left; loop back around to handle whichever it now is.
                }
            }
        }
    }

    /**
     * Scales the base per-dot duration by how much of the whole 100% this dot
     * represents (see [MILESTONE_WEIGHTS]), relative to the original even 20% split —
     * so the 70%-weighted first dot runs 3.5x as long as the uniform baseline, while the
     * 1%-weighted 5th dot's computed duration is effectively unused since that milestone
     * waits on real pipeline state instead.
     */
    private fun weightedSegmentDurationMs(index: Int, baseSegmentDurationMs: Long): Long {
        val weight = MILESTONE_WEIGHTS[index]
        return (baseSegmentDurationMs * (weight / UNIFORM_MILESTONE_WEIGHT)).toLong()
    }

    private fun cancelMilestoneProgress() {
        progressJob?.cancel()
        progressJob = null
        entryAnimators.forEach { it?.cancel() }
        pulseAnimators.forEach { it?.cancel() }
    }

    /** Snaps a dot to its untouched, not-yet-reached look: dim, small, steady. */
    private fun snapDotPending(dot: View, index: Int) {
        entryAnimators[index]?.cancel()
        pulseAnimators[index]?.cancel()
        dot.alpha = 1f
        DrawableCompat.setTint(dot.background.mutate(), INACTIVE_DOT_COLOR)
        dot.scaleX = INACTIVE_DOT_SCALE
        dot.scaleY = INACTIVE_DOT_SCALE
        dotColors[index] = INACTIVE_DOT_COLOR
    }

    /**
     * Grows/tints a dot up into its active look, then starts its repeating
     * fade-out-and-back-in pulse to show that this milestone is currently in progress.
     */
    private fun activateAndPulse(index: Int) {
        val dot = pageIndicatorDots[index]
        entryAnimators[index]?.cancel()

        val fromColor = dotColors[index]
        val fromScale = dot.scaleX
        val entry = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DOT_ACTIVATE_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val color = argbEvaluator.evaluate(t, fromColor, ACTIVE_DOT_COLOR) as Int
                DrawableCompat.setTint(dot.background.mutate(), color)
                dot.scaleX = fromScale + (ACTIVE_DOT_SCALE - fromScale) * t
                dot.scaleY = dot.scaleX
                dotColors[index] = color
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    startPulseLoop(index)
                }
            })
        }
        entryAnimators[index] = entry
        entry.start()
    }

    /** Repeating fade-out/fade-in on the dot's alpha — "still working on this slice". */
    private fun startPulseLoop(index: Int) {
        val dot = pageIndicatorDots[index]
        pulseAnimators[index]?.cancel()
        val pulse = ValueAnimator.ofFloat(1f, PULSE_MIN_ALPHA).apply {
            duration = pulseHalfCycleMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim -> dot.alpha = anim.animatedValue as Float }
        }
        pulseAnimators[index] = pulse
        pulse.start()
    }

    /** Stops the pulse and locks the dot in as permanently active (fully opaque). */
    private fun settleDotAsComplete(index: Int) {
        pulseAnimators[index]?.cancel()
        pulseAnimators[index] = null
        val dot = pageIndicatorDots[index]
        dot.alpha = 1f
        DrawableCompat.setTint(dot.background.mutate(), ACTIVE_DOT_COLOR)
        dot.scaleX = ACTIVE_DOT_SCALE
        dot.scaleY = ACTIVE_DOT_SCALE
        dotColors[index] = ACTIVE_DOT_COLOR
    }

    private fun navigateOnward() {
        if (hasNavigated || isFinishing) return
        hasNavigated = true

        val destination = if (onboardingPreferences.hasCompletedOnboarding) {
            MainActivity::class.java
        } else {
            OnboardingActivity::class.java
        }
        startActivity(Intent(this, destination))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private companion object {
        const val PAGE_INDICATOR_DOT_COUNT = 5

        // How much of the whole 100% each dot represents, left to right — must sum to
        // 1f. These are the deltas between cumulative milestone checkpoints of 70%,
        // 84.5%, 94.17%, 99%, and 100%, so the first dot is weighted heavily (70%) and
        // each dot after it covers a progressively smaller slice, down to the 5th dot's
        // last 1%. Weighting scales each dot's on-screen duration relative to the
        // uniform baseline (see weightedSegmentDurationMs). The 5th dot's weight isn't
        // used for timing since that milestone is gated on real pipeline readiness
        // instead of a duration — it just stays active/pulsing until the pipeline
        // reports LIVE.
        val MILESTONE_WEIGHTS = floatArrayOf(0.70f, 0.145f, 0.0967f, 0.0483f, 0.01f)
        const val UNIFORM_MILESTONE_WEIGHT = 1f / PAGE_INDICATOR_DOT_COUNT

        // Per-dot duration is derived from a measured network round trip each run
        // (see startMilestoneProgress), clamped to this range, then scaled per-dot by
        // MILESTONE_WEIGHTS. The floor keeps the pulse legible even on a near-instant
        // local/loopback connection; the ceiling is the "maximum slow" case requested
        // — 1 second per dot at the uniform baseline weight.
        const val MIN_SEGMENT_DURATION_MS = 150L
        const val MAX_SEGMENT_DURATION_MS = 1_000L

        // How long a dot takes to grow/tint from pending to active before its pulse starts.
        const val DOT_ACTIVATE_DURATION_MS = 220L

        // When the pipeline turns out to already be LIVE partway through a dot's
        // simulated timing, that's how long the dot gets to visibly "catch up" before
        // settling as complete — long enough to still read as an animation beat, short
        // enough that a run of remaining dots sweeps to completion quickly rather than
        // continuing to fake a load that's already finished.
        const val ACCELERATED_CATCHUP_DURATION_MS = 120L

        const val PULSE_MIN_ALPHA = 0.3f

        const val ACTIVE_DOT_SCALE = 1f
        const val INACTIVE_DOT_SCALE = 0.72f

        val ACTIVE_DOT_COLOR = Color.parseColor("#17D7E8")
        val INACTIVE_DOT_COLOR = Color.parseColor("#33FFFFFF")
    }
}
