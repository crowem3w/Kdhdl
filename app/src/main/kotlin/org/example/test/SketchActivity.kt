package org.example.test

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlin.math.roundToInt



















class SketchActivity : AppCompatActivity() {

    private lateinit var canvas: SketchCanvasView

    // Top bar auto-hide: hidden after TOP_BAR_AUTO_HIDE_DELAY_MS of the activity being focused,
    // so sketching gets the full screen. It's brought back only by pulling down the notification
    // shade / Quick Settings (which takes window focus away, see onWindowFocusChanged) or by
    // reopening this screen (onResume) - never by touches on the canvas.
    private lateinit var topBar: LinearLayout
    private val topBarHideHandler = Handler(Looper.getMainLooper())
    private val hideTopBarRunnable = Runnable { hideTopBar() }

    private lateinit var tabSelect: LinearLayout
    private lateinit var tabShapes: LinearLayout
    private lateinit var tabText: LinearLayout
    private lateinit var tabMedia: LinearLayout
    private lateinit var tabComponents: LinearLayout
    private lateinit var allTabs: List<LinearLayout>

    private lateinit var tvPosX: TextView
    private lateinit var tvPosY: TextView
    private lateinit var tvSizeW: TextView
    private lateinit var tvSizeH: TextView
    private lateinit var tvRotation: TextView

    private lateinit var bottomPanel: LinearLayout
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>
    private lateinit var mainToolsRow: LinearLayout
    private lateinit var panelContentContainer: FrameLayout
    private lateinit var defaultToolsContentScroll: NestedScrollView
    private lateinit var defaultToolsContent: LinearLayout
    private lateinit var componentsContentContainer: FrameLayout
    private lateinit var dragHandle: View
    private var componentsContentBuilt = false
    private var showingComponents = false

    // Panel resize bounds, computed once the panel has laid out (see setupBottomPanel).
    // - minPanelHeight: smallest height that still shows the handle + tabs row.
    // - maxPanelHeight: full-screen height (the panel's parent height).
    // - defaultPanelHeight: the panel's resting size when first shown/revealed. Uses the
    //   standard Material/Android "half-expanded" convention of 50% of screen height
    //   (com.google.android.material.bottomsheet.BottomSheetBehavior.DEFAULT_HALF_EXPANDED_RATIO)
    //   as the HCI-standard default length for a resizable bottom panel.
    private var minPanelHeight = 0
    private var maxPanelHeight = 0
    private var defaultPanelHeight = 0
    private val hideThreshold: Int get() = (minPanelHeight * 0.5f).roundToInt()
    private val fullscreenThreshold: Int get() = maxPanelHeight - (minPanelHeight * 0.5f).roundToInt()

    private var nextId = 1L

    companion object {
        private const val TOP_BAR_AUTO_HIDE_DELAY_MS = 5_000L
        private const val TOP_BAR_FADE_MS = 150L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sketch)

        canvas = findViewById(R.id.sketchCanvas)
        topBar = findViewById(R.id.topBar)

        tabSelect = findViewById(R.id.tabSelect)
        tabShapes = findViewById(R.id.tabShapes)
        tabText = findViewById(R.id.tabText)
        tabMedia = findViewById(R.id.tabMedia)
        tabComponents = findViewById(R.id.tabComponents)
        allTabs = listOf(tabSelect, tabShapes, tabText, tabMedia, tabComponents)

        tvPosX = findViewById(R.id.tvPosX)
        tvPosY = findViewById(R.id.tvPosY)
        tvSizeW = findViewById(R.id.tvSizeW)
        tvSizeH = findViewById(R.id.tvSizeH)
        tvRotation = findViewById(R.id.tvRotation)

        bottomPanel = findViewById(R.id.bottomPanel)
        mainToolsRow = findViewById(R.id.mainToolsRow)
        panelContentContainer = findViewById(R.id.panelContentContainer)
        defaultToolsContentScroll = findViewById(R.id.defaultToolsContentScroll)
        defaultToolsContent = findViewById(R.id.defaultToolsContent)
        componentsContentContainer = findViewById(R.id.componentsContentContainer)
        dragHandle = findViewById(R.id.dragHandle)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomPanel)

        canvas.listener = object : SketchCanvasView.Listener {
            override fun onLongPressEmptySpace(x: Float, y: Float) = openPartPicker(
                title = "Add to sketch",
                kinds = PartKind.values().toList(),
                x = x,
                y = y,
            )

            override fun onPartLongPressed(part: SketchPart) {
                showPartOptionsDialog(
                    context = this@SketchActivity,
                    part = part,
                    onRename = { newLabel ->
                        part.label = newLabel
                        canvas.invalidate()
                    },
                    onDelete = { canvas.removePart(part) },
                )
            }

            override fun onSelectionChanged(part: SketchPart?) = updateProperties(part)

            override fun onPartsChanged() = Unit
        }

        setupTopBar()
        setupBottomPanel()
        setupDragHandle()
        setupTabs()
        setupQuickActions()
        setupAnimationRow()
        setupHiddenReveal()

        updateProperties(null)

        // Top bar starts visible and begins its 5s countdown to hide as soon as the sketch
        // screen is first shown.
        showTopBar()
    }

    override fun onResume() {
        super.onResume()
        // Reopening the sketch screen (coming back from another activity, the recents list,
        // etc.) brings the top bar back and restarts the auto-hide countdown.
        showTopBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Window regained focus (e.g. Quick Settings / notification shade was closed):
            // show the bar and start counting back down to hidden.
            showTopBar()
        } else {
            // Window lost focus - most commonly because the notification shade / Quick
            // Settings was pulled down over the screen. Reveal the bar and hold it visible
            // (don't schedule the auto-hide) until focus returns.
            showTopBar(autoHideAfterDelay = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        topBarHideHandler.removeCallbacksAndMessages(null)
    }

    /** Shows the top bar (fading in if it was hidden) and, unless told not to, schedules it to
     *  auto-hide again after [TOP_BAR_AUTO_HIDE_DELAY_MS]. */
    private fun showTopBar(autoHideAfterDelay: Boolean = true) {
        topBarHideHandler.removeCallbacks(hideTopBarRunnable)
        if (topBar.visibility != View.VISIBLE || topBar.alpha < 1f) {
            topBar.animate().cancel()
            topBar.alpha = 0f
            topBar.visibility = View.VISIBLE
            topBar.animate().alpha(1f).setDuration(TOP_BAR_FADE_MS).start()
        }
        if (autoHideAfterDelay) {
            topBarHideHandler.postDelayed(hideTopBarRunnable, TOP_BAR_AUTO_HIDE_DELAY_MS)
        }
    }

    private fun hideTopBar() {
        if (topBar.visibility != View.VISIBLE) return
        topBar.animate().cancel()
        topBar.animate()
            .alpha(0f)
            .setDuration(TOP_BAR_FADE_MS)
            .withEndAction { topBar.visibility = View.GONE }
            .start()
    }

    private fun setupTopBar() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnUndo).setOnClickListener { notAvailableYet("Undo") }
        findViewById<View>(R.id.btnRedo).setOnClickListener { notAvailableYet("Redo") }
        findViewById<View>(R.id.btnPlay).setOnClickListener { notAvailableYet("Preview") }
        findViewById<View>(R.id.btnMore).setOnClickListener {
            showMoreMenu(
                context = this,
                hasParts = canvas.parts.isNotEmpty(),
                onGeneratePrompt = { generatePrompt() },
                onExportProject = { exportProject() },
                onClear = { canvas.clearAll() },
            )
        }
    }

    

    

    






    private fun setupBottomPanel() {
        bottomSheetBehavior.isDraggable = false // dragHandle drives resizing manually; see setupDragHandle
        bottomPanel.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val rootHeight = (bottomPanel.parent as? View)?.height ?: 0
                if (panelContentContainer.top > 0 && rootHeight > 0) {
                    // Smallest height that still shows the handle + tabs row, with no content
                    // area showing yet.
                    minPanelHeight = panelContentContainer.top + bottomPanel.paddingBottom
                    // Full screen, since bottomPanel's height is match_parent.
                    maxPanelHeight = rootHeight
                    // HCI-standard resting size: 50% of screen height (the same half-expanded
                    // ratio Material's own BottomSheetBehavior defaults to for resizable
                    // sheets), clamped so it never shows less than the tabs row or more than
                    // the full screen.
                    defaultPanelHeight = (rootHeight * 0.5f).roundToInt()
                        .coerceIn(minPanelHeight, maxPanelHeight)

                    bottomSheetBehavior.peekHeight = defaultPanelHeight
                    bottomPanel.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    

    /**
     * Lets the small pill handle at the top of the panel resize it freely: the panel height
     * tracks the finger 1:1 while dragging (no snapping mid-drag), matching whatever height the
     * user needs. Only on release, if the drag went past one of the extremes, does it
     * auto-complete into fully hidden or full screen; anywhere else it simply stays put at the
     * size the user left it.
     */
    private fun setupDragHandle() {
        var startRawY = 0f
        var startHeight = 0

        dragHandle.setOnTouchListener { _, event ->
            if (maxPanelHeight == 0) return@setOnTouchListener false // not laid out yet
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (showingComponents) closeComponentsContent()
                    startRawY = event.rawY
                    startHeight = currentPanelHeight()
                    // Normalize to COLLAPSED at the current visible height so peekHeight takes
                    // over the drag from here with no visual jump, regardless of which state
                    // (collapsed/expanded/hidden) the panel was resting in.
                    bottomSheetBehavior.setPeekHeight(startHeight, false)
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dragUpAmount = startRawY - event.rawY
                    val newHeight = (startHeight + dragUpAmount.roundToInt())
                        .coerceIn(0, maxPanelHeight)
                    bottomSheetBehavior.setPeekHeight(newHeight, false)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val finalHeight = bottomSheetBehavior.peekHeight
                    when {
                        finalHeight <= hideThreshold ->
                            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                        finalHeight >= fullscreenThreshold ->
                            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                        else -> {
                            // Resting mid-drag: keep the exact height the user chose, and make
                            // sure it doesn't end up below the usable minimum.
                            bottomSheetBehavior.setPeekHeight(
                                finalHeight.coerceAtLeast(minPanelHeight),
                                false,
                            )
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun currentPanelHeight(): Int = when (bottomSheetBehavior.state) {
        BottomSheetBehavior.STATE_EXPANDED -> maxPanelHeight
        BottomSheetBehavior.STATE_HIDDEN -> 0
        else -> bottomSheetBehavior.peekHeight
    }

    

    private fun showComponentsContent() {
        if (!componentsContentBuilt) {
            componentsContentContainer.addView(
                buildComponentsContent(context = this, onClose = { closeComponentsContent() })
            )
            componentsContentBuilt = true
        }
        showingComponents = true
        defaultToolsContentScroll.visibility = View.GONE
        componentsContentContainer.visibility = View.VISIBLE
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    

    private fun closeComponentsContent() {
        showingComponents = false
        componentsContentContainer.visibility = View.GONE
        defaultToolsContentScroll.visibility = View.VISIBLE
        setTabActive(tabSelect)
        if (defaultPanelHeight > 0) bottomSheetBehavior.setPeekHeight(defaultPanelHeight, false)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    

    private fun setupHiddenReveal() {
        val revealHandle = findViewById<View>(R.id.bottomSwipeHandle)
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                revealPanel()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val startY = e1?.y ?: return false
                val swipedUp = (startY - e2.y) > 24 && velocityY < 0
                if (swipedUp) revealPanel()
                return swipedUp
            }
        })
        revealHandle.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
    }

    private fun revealPanel() {
        if (showingComponents) closeComponentsContent()
        if (defaultPanelHeight > 0) bottomSheetBehavior.setPeekHeight(defaultPanelHeight, false)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    

    private fun setupTabs() {
        setTabActive(tabSelect)

        tabShapes.setOnClickListener {
            openPartPickerFromTab(tabShapes, "Shapes", listOf(PartKind.CARD, PartKind.IMAGE, PartKind.CHIP))
        }
        tabText.setOnClickListener {
            openPartPickerFromTab(tabText, "Text", listOf(PartKind.TEXT))
        }
        tabMedia.setOnClickListener {
            openPartPickerFromTab(tabMedia, "Upload", listOf(PartKind.IMAGE))
        }
        tabComponents.setOnClickListener {
            setTabActive(tabComponents)
            showComponentsContent()
        }
        tabSelect.setOnClickListener {
            if (showingComponents) closeComponentsContent() else setTabActive(tabSelect)
        }
    }

    private fun openPartPickerFromTab(tab: LinearLayout, title: String, kinds: List<PartKind>) {
        if (showingComponents) closeComponentsContent()
        setTabActive(tab)
        openPartPicker(
            title = title,
            kinds = kinds,
            x = canvas.width / 2f,
            y = canvas.height / 2f,
            onDismiss = { setTabActive(tabSelect) },
        )
    }

    
    private fun setTabActive(active: LinearLayout) {
        for (tab in allTabs) setTabVisualState(tab, tab === active)
    }

    private fun setTabVisualState(tab: LinearLayout, active: Boolean) {
        val pill = tab.getChildAt(0) as LinearLayout
        pill.setBackgroundResource(if (active) R.drawable.bg_tab_selected else 0)
        val color = if (active) Color.WHITE else Color.parseColor("#9A9AA5")
        when (val icon = pill.getChildAt(0)) {
            is ImageView -> icon.setColorFilter(color)
            is TextView -> icon.setTextColor(color)
        }
        (pill.getChildAt(1) as TextView).setTextColor(color)
    }

    

    private fun setupQuickActions() {
        val actions = listOf(
            R.id.actionFrame to "Frame",
            R.id.actionGroup to "Group",
            R.id.actionAlign to "Align",
            R.id.actionDistribute to "Distribute",
            R.id.actionLock to "Lock",
        )
        for ((id, label) in actions) {
            findViewById<View>(id).setOnClickListener { notAvailableYet(label) }
        }
        findViewById<View>(R.id.btnPropertiesMore).setOnClickListener { notAvailableYet("More properties") }
    }

    

    private fun setupAnimationRow() {
        val actions = listOf(
            R.id.actionAnimate to "Animate",
            R.id.actionStates to "States",
            R.id.actionInteractions to "Interactions",
            R.id.actionTimeline to "Timeline",
            R.id.actionMore to "More",
        )
        for ((id, label) in actions) {
            findViewById<View>(id).setOnClickListener { notAvailableYet(label) }
        }
    }

    private fun notAvailableYet(feature: String) {
        Toast.makeText(this, "$feature isn't available yet", Toast.LENGTH_SHORT).show()
    }

    

    

    private fun updateProperties(part: SketchPart?) {
        if (part == null) {
            tvPosX.text = "\u2013"
            tvPosY.text = "\u2013"
            tvSizeW.text = "\u2013"
            tvSizeH.text = "\u2013"
            tvRotation.text = "0\u00B0"
            return
        }
        val density = resources.displayMetrics.density
        tvPosX.text = (part.x / density).roundToInt().toString()
        tvPosY.text = (part.y / density).roundToInt().toString()
        tvSizeW.text = (part.w / density).roundToInt().toString()
        tvSizeH.text = (part.h / density).roundToInt().toString()
        tvRotation.text = "0\u00B0"
    }

    

    private fun openPartPicker(
        title: String,
        kinds: List<PartKind>,
        x: Float,
        y: Float,
        onDismiss: () -> Unit = {},
    ) {
        showPartPickerSheet(
            context = this,
            title = title,
            kinds = kinds,
            onPick = { kind -> addPart(kind, x, y) },
            onDismiss = onDismiss,
        )
    }

    private fun addPart(kind: PartKind, x: Float, y: Float) {
        val density = resources.displayMetrics.density
        val fullWidth = kind == PartKind.TOP_APP_BAR || kind == PartKind.NAV_BAR
        val w = if (fullWidth) canvas.width.toFloat() else kind.defaultW * density
        val h = kind.defaultH * density
        val px = if (fullWidth) 0f else (x - w / 2f).coerceIn(0f, (canvas.width - w).coerceAtLeast(0f))
        val py = when (kind) {
            PartKind.TOP_APP_BAR -> 0f
            PartKind.NAV_BAR -> (canvas.height - h).coerceAtLeast(0f)
            else -> (y - h / 2f).coerceIn(0f, (canvas.height - h).coerceAtLeast(0f))
        }
        canvas.addPart(SketchPart(nextId++, kind, px, py, w, h))
    }

    

    private fun generatePrompt() {
        val density = resources.displayMetrics.density
        val prompt = PromptGenerator.build(canvas.parts, canvas.width, canvas.height, density)
        showPromptDialog(this, prompt)
    }

    
    private fun exportProject() {
        val density = resources.displayMetrics.density
        val zip = CodeGenerator.generateProjectZip(
            context = this,
            parts = canvas.parts,
            canvasWidthPx = canvas.width,
            canvasHeightPx = canvas.height,
            density = density,
        )
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zip)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Toast.makeText(this, "Project generated \u2014 choose where to save it", Toast.LENGTH_SHORT).show()
        startActivity(Intent.createChooser(shareIntent, "Export generated project"))
    }
}