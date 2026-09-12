package org.example.test

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlin.math.roundToInt

class SketchActivity : AppCompatActivity() {

    private lateinit var canvas: SketchCanvasView





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
    private var componentsContentBuilt = false
    private var showingComponents = false





    private var defaultPanelHeight = 0




    private val panelBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeSketchPanel()
        }
    }

    private var nextId = 1L

    companion object {
        private const val TOP_BAR_AUTO_HIDE_DELAY_MS = 5_000L
        private const val TOP_BAR_FADE_MS = 150L
        private const val SCROLL_REVEAL_THRESHOLD_DP = 64f
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
        bottomSheetBehavior = BottomSheetBehavior.from(bottomPanel)
        onBackPressedDispatcher.addCallback(this, panelBackPressedCallback)

        canvas.listener = object : SketchCanvasView.Listener {
            override fun onLongPressEmptySpace(x: Float, y: Float) = openSketchPanel()

            override fun onPartLongPressed(part: SketchPart) {
                showPartOptionsDialog(
                    context = this@SketchActivity,
                    part = part,
                    onRename = { newLabel ->
                        part.label = newLabel
                        canvas.relayoutTextIfNeeded(part)
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
        setupTabs()
        setupQuickActions()
        setupAnimationRow()

        updateProperties(null)



        showTopBar()
    }

    override fun onResume() {
        super.onResume()


        showTopBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {


            showTopBar()
        } else {



            showTopBar(autoHideAfterDelay = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        topBarHideHandler.removeCallbacksAndMessages(null)
    }



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
        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
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
        // The panel now only ever opens via openSketchPanel() and closes via closeSketchPanel()
        // (back button/gesture) or by being swiped down, so we let the framework's own
        // swipe-to-dismiss gesture drive it instead of a manual drag handle.
        bottomSheetBehavior.isDraggable = true
        bottomPanel.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val rootHeight = (bottomPanel.parent as? View)?.height ?: 0
                if (panelContentContainer.top > 0 && rootHeight > 0) {
                    val minPanelHeight = panelContentContainer.top + bottomPanel.paddingBottom
                    defaultPanelHeight = (rootHeight * 0.5f).roundToInt()
                        .coerceIn(minPanelHeight, rootHeight)

                    bottomSheetBehavior.peekHeight = defaultPanelHeight
                    bottomPanel.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })

        // Keeps the back-press callback and the panel's own content in sync with its state,
        // regardless of whether it was hidden by the back button, a swipe-down, or code.
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheetView: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        panelBackPressedCallback.isEnabled = false
                        resetPanelContent()
                    }
                    BottomSheetBehavior.STATE_DRAGGING, BottomSheetBehavior.STATE_SETTLING -> Unit
                    else -> panelBackPressedCallback.isEnabled = true
                }
            }

            override fun onSlide(sheetView: View, slideOffset: Float) = Unit
        })

        // Hidden by default: the panel only appears once the user triggers "Add to sketch".
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    // Opens the shared panel - the only entry point is the "Add to sketch" action (long-press on
    // empty canvas). Goes straight to the Components browser, matching what "Add to sketch" used
    // to show as a standalone picker.
    private fun openSketchPanel() {
        setTabActive(tabComponents)
        showComponentsContent()
    }

    // Lets the user scroll down (swipe up) anywhere on the sketch screen to reveal the dark bottom
    // panel (peek height, default tools tab). Implemented at the screen/dispatch level (rather
    // than on a single view) so it works as a general "scroll down" gesture on the sketch screen; it
    // only observes touches and never consumes them, so normal canvas interactions (drawing,
    // dragging parts, long-press, and now dragging the page-height handle) are unaffected - the
    // isDraggingPageHandle check below additionally keeps it from firing while the user is
    // resizing the page, since that's also an upward drag. isPanningCanvas does the same job for
    // the canvas's own one-finger pan: that gesture only ever engages when the page is taller than
    // the viewport (see SketchCanvasView.maxPanOffsetY), so on a page that already fits on screen
    // this reveal gesture is untouched and still wins a swipe-up exactly as before.
    private var scrollGestureStartX = 0f
    private var scrollGestureStartY = 0f
    private var scrollGestureTriggered = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Let the canvas process the event first so we can tell whether it just grabbed the page
        // resize handle - if so, this stream is its drag, not a "scroll down to reveal panel"
        // gesture, so we skip our own tracking below entirely.
        val handled = super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrollGestureStartX = ev.rawX
                scrollGestureStartY = ev.rawY
                scrollGestureTriggered = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrollGestureTriggered &&
                    !canvas.isDraggingPageHandle &&
                    !canvas.isPanningCanvas &&
                    bottomSheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN
                ) {
                    val movedUp = scrollGestureStartY - ev.rawY
                    val movedSideways = kotlin.math.abs(ev.rawX - scrollGestureStartX)
                    val thresholdPx = SCROLL_REVEAL_THRESHOLD_DP * resources.displayMetrics.density
                    if (movedUp > thresholdPx && movedUp > movedSideways) {
                        scrollGestureTriggered = true
                        revealBottomPanel()
                    }
                }
            }
            else -> Unit
        }
        return handled
    }

    // Brings the dark bottom panel up from fully hidden to its peek height, showing the default
    // tools tab (Select) rather than jumping straight to Components like the long-press entry
    // point does.
    private fun revealBottomPanel() {
        resetPanelContent()
        if (defaultPanelHeight > 0) bottomSheetBehavior.setPeekHeight(defaultPanelHeight, false)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    // Closes the whole panel (as opposed to closeComponentsContent(), which just switches back to
    // the default tools tab while keeping the panel open). Used by the back button/gesture and
    // available to swipe-down-to-dismiss.
    private fun closeSketchPanel() {
        if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    // Resets the panel back to its default tab/content so the next time it's opened it starts
    // fresh, whichever way it was just closed.
    private fun resetPanelContent() {
        showingComponents = false
        componentsContentContainer.visibility = View.GONE
        defaultToolsContentScroll.visibility = View.VISIBLE
        setTabActive(tabSelect)
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



    private fun setupTabs() {
        setTabActive(tabSelect)

        tabShapes.setOnClickListener {
            openPartPickerFromTab(tabShapes, "Pages", listOf(PartKind.CARD, PartKind.IMAGE, PartKind.CHIP))
        }
        tabText.setOnClickListener {
            openTextInputModal()
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



    private fun openTextInputModal() {
        if (showingComponents) closeComponentsContent()
        setTabActive(tabText)


        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        showTextInputDialog(
            context = this,
            onConfirm = { text ->
                if (text.isNotBlank()) {
                    addPart(PartKind.TEXT, canvas.width / 2f, canvas.pageHeight / 2f, label = text)
                }
                restorePanelAfterTextModal()
            },
            onCancel = { restorePanelAfterTextModal() },
        )
    }

    private fun restorePanelAfterTextModal() {
        setTabActive(tabSelect)
        if (defaultPanelHeight > 0) bottomSheetBehavior.setPeekHeight(defaultPanelHeight, false)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun openPartPickerFromTab(tab: LinearLayout, title: String, kinds: List<PartKind>) {
        if (showingComponents) closeComponentsContent()
        setTabActive(tab)
        openPartPicker(
            title = title,
            kinds = kinds,
            x = canvas.width / 2f,
            y = canvas.pageHeight / 2f,
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

    private fun addPart(kind: PartKind, x: Float, y: Float, label: String = ""): SketchPart {
        val density = resources.displayMetrics.density
        val fullWidth = kind == PartKind.TOP_APP_BAR || kind == PartKind.NAV_BAR
        val w = if (fullWidth) canvas.width.toFloat() else kind.defaultW * density
        val h = kind.defaultH * density
        val px = if (fullWidth) 0f else (x - w / 2f).coerceIn(0f, (canvas.width - w).coerceAtLeast(0f))
        val py = when (kind) {
            PartKind.TOP_APP_BAR -> 0f
            PartKind.NAV_BAR -> (canvas.pageHeight - h).coerceAtLeast(0f)
            else -> (y - h / 2f).coerceIn(0f, (canvas.pageHeight - h).coerceAtLeast(0f))
        }
        val part = SketchPart(nextId++, kind, px, py, w, h, label, fontSize = 13f * density)
        canvas.addPart(part)
        return part
    }



    private fun generatePrompt() {
        val density = resources.displayMetrics.density
        val prompt = PromptGenerator.build(canvas.parts, canvas.width, canvas.pageHeight.roundToInt(), density)
        showPromptDialog(this, prompt)
    }


    private fun exportProject() {
        val density = resources.displayMetrics.density
        val zip = CodeGenerator.generateProjectZip(
            context = this,
            parts = canvas.parts,
            canvasWidthPx = canvas.width,
            canvasHeightPx = canvas.pageHeight.roundToInt(),
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