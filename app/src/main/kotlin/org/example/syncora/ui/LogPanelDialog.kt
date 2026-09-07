package org.example.syncora.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.syncora.R
import org.example.syncora.log.AppLog
import org.example.syncora.log.LogEntry
import org.example.syncora.log.LogLevel
import org.example.syncora.log.LogSource










class LogPanelDialog(context: Context) : Dialog(context, R.style.TradingModalTheme) {

    private companion object {
        const val CARD_CORNER_RADIUS_DP = 12
        const val GLOW_EXTRA_DP = 22
        const val CARD_HEIGHT_DP = 420
        const val BORDER_WIDTH_DP = 0.5f

        const val BACKDROP_BLUR_PERCENT = 0.85f
        const val MAX_BACKDROP_BLUR_DP = 100

        
        val GLASS_TOP_TINT = Color.parseColor("#2A2E3E")      
        val GLASS_BASE_TINT = Color.parseColor("#141519")     
        val GLASS_BOTTOM_TINT = Color.parseColor("#231B30")   

        const val GLASS_TOP_ALPHA = 0.55f
        const val GLASS_BASE_ALPHA = 0.72f
        const val GLASS_BOTTOM_ALPHA = 0.60f

        val BORDER_COLOR = Color.parseColor("#40FFFFFF")
        val GLOW_COLOR = Color.parseColor("#331C1A3D")
        val SCRIM_COLOR = Color.parseColor("#8A000000")

        val MUTED_COLOR = Color.parseColor("#8A8D98")
        val TIMESTAMP_COLOR = Color.parseColor("#5B5E68")

        
        val LEVEL_DEBUG = Color.parseColor("#6B6F7B")     
        val LEVEL_INFO = Color.parseColor("#5AC8FA")      
        val LEVEL_SUCCESS = Color.parseColor("#3DD68C")   
        val LEVEL_WARNING = Color.parseColor("#F5A623")   
        val LEVEL_ERROR = Color.parseColor("#FF5C5C")     

        
        
        val SOURCE_AGENT = Color.parseColor("#B98CFF")
        val SOURCE_TRADING = Color.parseColor("#5AC8FA")
        val SOURCE_ACCOUNT = Color.parseColor("#F5A623")
        val SOURCE_SYSTEM = Color.parseColor("#8A8D98")

        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    private val dialogScope = CoroutineScope(Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private lateinit var terminalOutput: TextView
    private lateinit var terminalScroll: ScrollView
    private lateinit var emptyStateText: TextView

    private fun dp(value: Number): Int = (value.toFloat() * context.resources.displayMetrics.density).toInt()

    private fun dpf(value: Number): Float = value.toFloat() * context.resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        applyWindowBlur()
    }

    override fun onStart() {
        super.onStart()
        collectJob?.cancel()
        collectJob = dialogScope.launch {
            AppLog.entries.collect { entries -> renderEntries(entries) }
        }
    }

    override fun onStop() {
        super.onStop()
        collectJob?.cancel()
        collectJob = null
    }

    private fun applyWindowBlur() {
        val win = window ?: return
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        win.setGravity(Gravity.CENTER)
        win.setDimAmount(0f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager?.isCrossWindowBlurEnabled == true) {
                win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val blurRadiusDp = (MAX_BACKDROP_BLUR_DP * BACKDROP_BLUR_PERCENT).toInt()
                win.attributes = win.attributes.apply { blurBehindRadius = dp(blurRadiusDp) }
            }
        }
    }

    private fun applyAlpha(color: Int, alpha: Float): Int = Color.argb(
        (255 * alpha).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun buildRootView(): View {
        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(SCRIM_COLOR)
            isClickable = true
            setOnClickListener { dismiss() }
        }

        val cornerRadiusPx = dpf(CARD_CORNER_RADIUS_DP)
        val glowCornerRadiusPx = dpf(CARD_CORNER_RADIUS_DP + GLOW_EXTRA_DP / 2)

        
        val glow = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = glowCornerRadiusPx
                setColor(GLOW_COLOR)
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CARD_HEIGHT_DP + GLOW_EXTRA_DP),
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dp(20) - dp(GLOW_EXTRA_DP / 2)
                marginEnd = dp(20) - dp(GLOW_EXTRA_DP / 2)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(dpf(18), dpf(18), Shader.TileMode.CLAMP))
            }
        }

        
        val card = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    applyAlpha(GLASS_TOP_TINT, GLASS_TOP_ALPHA),
                    applyAlpha(GLASS_BASE_TINT, GLASS_BASE_ALPHA),
                    applyAlpha(GLASS_BOTTOM_TINT, GLASS_BOTTOM_ALPHA),
                ),
            ).apply {
                cornerRadius = cornerRadiusPx
                setStroke(dp(BORDER_WIDTH_DP).coerceAtLeast(1), BORDER_COLOR)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
            elevation = dpf(20)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = Color.parseColor("#1A1030")
                outlineSpotShadowColor = Color.parseColor("#1A1030")
            }
            isClickable = true
            setOnClickListener {  }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CARD_HEIGHT_DP),
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dp(20)
                marginEnd = dp(20)
            }
        }

        card.addView(buildTerminalContent())

        root.addView(glow)
        root.addView(card)
        return root
    }

    private fun buildTerminalContent(): View {
        terminalOutput = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setLineSpacing(dpf(3), 1f)
            setTextColor(LEVEL_INFO)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        terminalScroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                terminalOutput,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        emptyStateText = TextView(context).apply {
            text = "No activity yet. Agent, trading, and account events will stream here."
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(MUTED_COLOR)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val content = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        content.addView(terminalScroll)
        content.addView(emptyStateText)
        return content
    }

    private fun renderEntries(entries: List<LogEntry>) {
        if (entries.isEmpty()) {
            emptyStateText.visibility = View.VISIBLE
            terminalScroll.visibility = View.GONE
            return
        }
        emptyStateText.visibility = View.GONE
        terminalScroll.visibility = View.VISIBLE

        val builder = SpannableStringBuilder()
        entries.forEachIndexed { index, entry ->
            appendLine(builder, entry)
            if (index != entries.lastIndex) builder.append("\n")
        }
        terminalOutput.text = builder

        terminalScroll.post { terminalScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendLine(builder: SpannableStringBuilder, entry: LogEntry) {
        val start = builder.length

        
        builder.append(TIME_FORMAT.format(entry.timestampMs))
        builder.append("  ")

        
        val tagStart = builder.length
        builder.append(sourceTag(entry.source))
        builder.setSpan(ForegroundColorSpan(sourceColor(entry.source)), tagStart, builder.length, 0)
        builder.setSpan(StyleSpan(Typeface.BOLD), tagStart, builder.length, 0)
        builder.append(" ")

        
        val msgStart = builder.length
        builder.append(entry.message)
        builder.setSpan(ForegroundColorSpan(levelColor(entry.level)), msgStart, builder.length, 0)

        
        builder.setSpan(ForegroundColorSpan(TIMESTAMP_COLOR), start, tagStart, 0)
    }

    private fun sourceTag(source: LogSource): String = when (source) {
        LogSource.AGENT -> "[AGENT]  "
        LogSource.TRADING -> "[TRADE]  "
        LogSource.ACCOUNT -> "[ACCOUNT]"
        LogSource.SYSTEM -> "[SYSTEM] "
    }

    private fun sourceColor(source: LogSource): Int = when (source) {
        LogSource.AGENT -> SOURCE_AGENT
        LogSource.TRADING -> SOURCE_TRADING
        LogSource.ACCOUNT -> SOURCE_ACCOUNT
        LogSource.SYSTEM -> SOURCE_SYSTEM
    }

    private fun levelColor(level: LogLevel): Int = when (level) {
        LogLevel.DEBUG -> LEVEL_DEBUG
        LogLevel.INFO -> LEVEL_INFO
        LogLevel.SUCCESS -> LEVEL_SUCCESS
        LogLevel.WARNING -> LEVEL_WARNING
        LogLevel.ERROR -> LEVEL_ERROR
    }
}