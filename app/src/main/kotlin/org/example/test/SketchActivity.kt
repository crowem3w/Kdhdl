package org.example.test

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.marginBottom
import androidx.core.view.WindowInsetsCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlin.math.abs
import kotlin.math.roundToInt

class SketchActivity : AppCompatActivity() {

    private lateinit var canvas: SketchCanvasView
    private lateinit var canvasArea: FrameLayout

    // Each screen page gets its own SketchCanvasView so their contents are fully independent.
    // Keyed by ScreenPage.id; `canvas` above always points at whichever one is currently visible.
    // All canvases live in canvasArea (index 0, below the top bar/name-tag editor/etc.) and share
    // canvasListener; only the active one is View.VISIBLE at a time - see switchToScreenPage().
    private val screenCanvases = mutableMapOf<Long, SketchCanvasView>()





    private lateinit var topBar: LinearLayout
    private val topBarHideHandler = Handler(Looper.getMainLooper())
    private val hideTopBarRunnable = Runnable { hideTopBar() }

    private lateinit var tabSelect: LinearLayout
    private lateinit var tabScreens: LinearLayout
    private lateinit var tabText: LinearLayout
    private lateinit var tabMedia: LinearLayout
    private lateinit var allTabs: List<LinearLayout>

    
    
    
    
    
    
    private lateinit var selectionActionsPanel: LinearLayout
    private lateinit var actionGroupToggle: LinearLayout
    private lateinit var actionGroupToggleLabel: TextView
    private lateinit var actionDuplicateSel: LinearLayout
    private lateinit var actionMoveSel: LinearLayout
    private lateinit var actionLockToggleSel: LinearLayout
    private lateinit var actionHideToggleSel: LinearLayout
    private lateinit var actionDeleteSel: LinearLayout

    
    
    
    
    
    private lateinit var bottomNavBar: BottomNavSheetBar

    
    
    
    
    private lateinit var nameTagEditor: EditText
    private var editingNamePart: SketchPart? = null

    
    
    
    
    
    private data class TabPillVisual(
        val pill: LinearLayout,
        val fill: android.graphics.drawable.Drawable,
        val icon: ImageView,
        val label: TextView,
    )

    private lateinit var pillVisuals: List<TabPillVisual>
    private val pillBaseElevationPx by lazy { dp(3f) }
    private val pillRaisedElevationPx by lazy { dp(16f) }
    
    
    
    
    private val navBarTopPaddingPx by lazy { bottomNavBar.paddingTop.toFloat() }
    
    private val pillFillColor = Color.WHITE
    private val pillGlowColor = Color.parseColor("#C6FF00")
    // How much larger the selected tab's rounded-square frame grows vs. its resting size.
    private val pillSelectedScaleBoost = 0.25f
    private val inactiveTextColor = Color.parseColor("#8A8A94")
    private val activeTextColor = Color.parseColor("#1A1B24")

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    
    
    
    
    // Selected-tab fill: a plain white rounded-square (no stroke/border, 0px) with a subtle
    // lime drop shadow projecting downward underneath it. Built as two translucent lime layers
    // of increasing size sitting behind a solid white top layer; each is inset more at the top
    // than the bottom so the visible lime peeks out mainly below the pill, reading as a soft
    // downward shadow rather than a symmetric halo. Overall drawable alpha is driven by
    // selection progress (see applySelectionProgress), so the shadow fades in/out with it.
    private fun buildPillFillDrawable(): LayerDrawable {
        fun shadowLayer(alpha: Int, cornerDp: Float) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp)
            setColor(pillGlowColor)
            this.alpha = alpha
        }
        val whiteCenter = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9f)
            setColor(pillFillColor)
            
            
        }
        val layers = arrayOf(
            shadowLayer(alpha = 30, cornerDp = 14f),
            shadowLayer(alpha = 55, cornerDp = 12.5f),
            whiteCenter,
        )
        return LayerDrawable(layers).apply {
            
            
            // Asymmetric insets: less at the bottom than the top so each lime layer extends
            // further below the white center than above it, giving a downward drop-shadow feel.
            setLayerInset(0, dp(1f).toInt(), dp(3f).toInt(), dp(1f).toInt(), dp(0f).toInt())
            setLayerInset(1, dp(2.5f).toInt(), dp(4f).toInt(), dp(2.5f).toInt(), dp(1f).toInt())
            setLayerInset(2, dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
            alpha = 0
        }
    }

    
    
    private lateinit var elementsPanel: FrameLayout
    private lateinit var elementsPanelBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var componentsContentContainer: FrameLayout
    private var componentsContentBuilt = false
    private var showingComponents = false

    // The Elements sidebar (Material rail docked on the left) owns category navigation; the
    // Elements bottom sheet above only shows the content for whichever category it selects.
    private lateinit var elementsSidebar: ElementsSidebarView
    private var componentsHandle: ComponentsContentHandle? = null
    // Category currently shown in elementsPanel (ALL_CATEGORY.id or a COMPONENT_CATEGORIES id).
    private var currentComponentsCategory: String? = null
    private val uiPrefs by lazy { getSharedPreferences("sketch_ui", MODE_PRIVATE) }

    
    
    
    
    private lateinit var screensPanel: FrameLayout
    private lateinit var screensPanelBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var screensContentContainer: FrameLayout
    private var screensContentBuilt = false
    private var showingScreens = false

    
    
    
    
    // Text panel: same two-tier peek/expanded bottom sheet as screensPanel (see setupTextPanel/
    // openTextPanel/closeTextPanel below). textContentContainer holds the "Add text" action row
    // built by buildTextPanelContent() (see TextPanel.kt) on first open - more content is
    // expected to be added below it later.
    private lateinit var textPanel: FrameLayout
    private lateinit var textPanelBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var textContentContainer: FrameLayout
    private var textContentBuilt = false
    private var showingText = false

    
    
    
    
    // Separate bottom sheet shown when a Button part on the Canvas is double-tapped or
    // long-pressed - see onPartDoubleTapped/onPartLongPressed in canvasListener below and
    // setupButtonObjectPanel() further down. buttonObjectContentContainer currently just hosts
    // the Design/Prototype toggle built by buildButtonObjectPanelContent() (see
    // ButtonObjectPanel.kt); more content is expected to be added below that later.
    private lateinit var buttonObjectPanel: FrameLayout
    private lateinit var buttonObjectPanelBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var buttonObjectContentContainer: FrameLayout
    private var showingButtonObjectPanel = false
    // Built once, then reused/re-synced on every open - see openButtonObjectPanel() below.
    private var buttonObjectPanelViews: ButtonObjectPanelViews? = null
    // The Button part the panel is currently open for, so the Design/Prototype toggle's
    // onModeChanged callback knows which part's objectPanelMode to persist the selection onto.
    private var buttonObjectPanelPart: SketchPart? = null
    // Swapped in/out of buttonObjectPanel's background by updateButtonObjectPanelCornerState()
    // below: rounded is the panel's normal look (24dp top corners, matching bg_bottom_panel.xml);
    // square is shown instead while ButtonObjectMode.CUSTOM (customButton) is selected.
    private lateinit var buttonObjectPanelBgRounded: GradientDrawable
    private lateinit var buttonObjectPanelBgSquare: GradientDrawable
    // Localized drop-shadow strip shown above the panel's top edge, under customButton, only
    // while CUSTOM is selected - see buildButtonObjectCornerShadowView() in ButtonObjectPanel.kt.
    private lateinit var buttonObjectCornerShadowView: View

    
    
    
    private var nextScreenPageId = 1L
    private val screenPages = mutableListOf(ScreenPage(id = 0L, type = ScreenPageType.HOME))
    private var selectedScreenPageId = 0L
    private lateinit var screenThumbnailsContainer: LinearLayout
    private lateinit var screensPanelContentViews: ScreensPanelContentViews

    
    
    
    // Floating page-thumbnails row (thumbnails + chevron + add-page tile), positioned outside
    // screensPanel so its backdrop is the sketch canvas rather than the panel's white sheet - see
    // screenThumbnailsRow in activity_sketch.xml and positionScreenThumbnailsRow() below.
    private lateinit var screenThumbnailsRow: LinearLayout

    
    
    
    
    
    private var bottomNavBarBaseMarginBottom = 0

    
    
    
    private var statusBarInsetTop = 0




    private val panelBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (selectionActionsPanel.visibility == View.VISIBLE) {
                dismissSelectionActionsPanel(clearSelection = true)
            } else if (buttonObjectPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                closeButtonObjectPanel()
            } else if (screensPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                closeScreensPanel()
            } else if (textPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                closeTextPanel()
            } else {
                closeElementsPanel()
            }
        }
    }

    private var nextId = 1L

    // Shared across every screen's SketchCanvasView (see screenCanvases) - it only ever acts on
    // whichever canvas is currently active via the `canvas` field, so one instance is enough.
    private val canvasListener = object : SketchCanvasView.Listener {
        override fun onLongPressEmptySpace() = openElementsPanel()

        override fun onDoubleTapEmptySpace() = openElementsPanel()

        override fun onTapEmptySpace() {
            closeElementsPanel()
            closeScreensPanel()
            closeButtonObjectPanel()
            closeTextPanel()
            toggleBottomNavBar()
        }

        override fun onPartLongPressed(part: SketchPart) {
            if (part.kind == PartKind.BUTTON) {
                // Buttons already on the Canvas get the dedicated empty panel (see
                // setupButtonObjectPanel()) instead of the generic rename/delete dialog below.
                openButtonObjectPanel(part)
                return
            }
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

        // Buttons already on the Canvas open the same dedicated panel on double-tap as on
        // long-press (see onPartLongPressed above); returning true tells SketchCanvasView not to
        // fall back to its default double-tap behavior (zoom reset to 100%) for this part. Any
        // other part kind returns false and keeps that default zoom-reset behavior untouched.
        override fun onPartDoubleTapped(part: SketchPart): Boolean {
            if (part.kind != PartKind.BUTTON) return false
            openButtonObjectPanel(part)
            return true
        }

        override fun onSelectionChanged(part: SketchPart?) {
            
            if (editingNamePart != null && part !== editingNamePart) commitNameTagEdit()
        }

        // Live-updates a Blank screen's thumbnail label (Blank -> Normal, see hasScreenContent)
        // the moment content is added to or removed from its canvas, not just on screen switch.
        override fun onPartsChanged() = refreshScreenThumbnails()

        override fun onNameTagTapped(part: SketchPart) = startNameTagEdit(part)

        override fun onMultiSelectionFinalized(parts: List<SketchPart>) = showSelectionActionsPanel()

        override fun onMultiSelectionCleared() = hideSelectionActionsPanel()

        // Pinch-zoom hit the 0.5x floor on the active screen -> show the screens carousel. Posted
        // (and de-duplicated) because this fires from inside the canvas's own onTouchEvent, and
        // entering the carousel dispatches a CANCEL back to that same canvas.
        override fun onMaxZoomOut() {
            if (overviewActive || overviewEnterPending) return
            overviewEnterPending = true
            canvasArea.post {
                overviewEnterPending = false
                enterOverview()
            }
        }
    }

    companion object {
        private const val TOP_BAR_AUTO_HIDE_DELAY_MS = 5_000L
        private const val TOP_BAR_FADE_MS = 150L
        private const val BOTTOM_NAV_BAR_ANIM_MS = 250L

        private const val PREF_SIDEBAR_SHOWN = "elements_sidebar_shown"
        private const val PREF_SIDEBAR_EXPANDED = "elements_sidebar_expanded"

        // elementsPanel's minimum/default height when no real keyboard height has been measured
        // yet (i.e. the keyboard isn't currently showing). Matches screensPanel's peekHeight.
        private const val ELEMENTS_PANEL_FALLBACK_PEEK_HEIGHT_DP = 280

        // Zoomed-out screens carousel (see enterOverview()).
        private const val OVERVIEW_GAP_DP = 16f            // space between neighbouring screens
        private const val OVERVIEW_SNAP_MS = 220L
        private const val OVERVIEW_FLING_PROJECTION_S = 0.12f
        private const val OVERVIEW_PINCH_OUT_THRESHOLD = 1.15f
        private const val OVERVIEW_TRANSITION_MS = 200L    // enter/leave animation
        private const val OVERVIEW_ENTER_SPREAD = 0.6f     // neighbours start this close to center
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        
        
        window.statusBarColor = 0xFF121212.toInt()
        window.navigationBarColor = 0xFF121212.toInt()
        setContentView(R.layout.activity_sketch)

        canvas = findViewById(R.id.sketchCanvas)
        topBar = findViewById(R.id.topBar)

        tabSelect = findViewById(R.id.tabSelect)
        tabScreens = findViewById(R.id.tabScreens)
        tabText = findViewById(R.id.tabText)
        tabMedia = findViewById(R.id.tabMedia)
        allTabs = listOf(tabScreens, tabText, tabSelect, tabMedia)

        selectionActionsPanel = findViewById(R.id.selectionActionsPanel)
        actionGroupToggle = findViewById(R.id.actionGroupToggle)
        actionGroupToggleLabel = findViewById(R.id.actionGroupToggleLabel)
        actionDuplicateSel = findViewById(R.id.actionDuplicateSel)
        actionMoveSel = findViewById(R.id.actionMoveSel)
        actionLockToggleSel = findViewById(R.id.actionLockToggleSel)
        actionHideToggleSel = findViewById(R.id.actionHideToggleSel)
        actionDeleteSel = findViewById(R.id.actionDeleteSel)

        bottomNavBar = findViewById(R.id.bottomNavBar)
        nameTagEditor = findViewById(R.id.nameTagEditor)
        setupNameTagEditor()
        elementsPanel = findViewById(R.id.elementsPanel)
        componentsContentContainer = findViewById(R.id.componentsContentContainer)
        elementsSidebar = findViewById(R.id.elementsSidebar)
        elementsPanelBehavior = BottomSheetBehavior.from(elementsPanel)
        screensPanel = findViewById(R.id.screensPanel)
        screensContentContainer = findViewById(R.id.screensContentContainer)
        screensPanelBehavior = BottomSheetBehavior.from(screensPanel)
        buttonObjectPanel = findViewById(R.id.buttonObjectPanel)
        buttonObjectContentContainer = findViewById(R.id.buttonObjectContentContainer)
        buttonObjectPanelBehavior = BottomSheetBehavior.from(buttonObjectPanel)
        textPanel = findViewById(R.id.textPanel)
        textContentContainer = findViewById(R.id.textContentContainer)
        textPanelBehavior = BottomSheetBehavior.from(textPanel)
        screenThumbnailsRow = findViewById(R.id.screenThumbnailsRow)
        onBackPressedDispatcher.addCallback(this, panelBackPressedCallback)
        // Registered after panelBackPressedCallback so it wins while the carousel is showing.
        onBackPressedDispatcher.addCallback(this, overviewBackPressedCallback)

        canvasArea = findViewById(R.id.canvasArea)
        screenCanvases[screenPages[0].id] = canvas

        canvas.listener = canvasListener

        // The initial Home screen's canvas is the static one declared in the layout, so unlike
        // canvases created later via getOrCreateCanvas() it doesn't get its starter template
        // applied there. Post it so it runs after the first layout pass, once canvas.width/
        // pageHeight are populated (see applyStarterTemplate).
        val initialCanvas = canvas
        initialCanvas.post {
            if (initialCanvas.parts.isEmpty()) {
                applyStarterTemplate(initialCanvas, screenPages[0].type)
            }
        }

        setupTopBar()
        setupBottomNavBar()
        setupElementsPanel()
        setupElementsSidebar()
        setupScreensPanel()
        setupButtonObjectPanel()
        setupTextPanel()
        setupTabs()
        setupSelectionActionsPanel()

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
        overviewSnapAnimator?.cancel()
        overviewTransitionAnimator?.removeAllListeners()
        overviewTransitionAnimator?.cancel()
        overviewVelocity?.recycle()
        overviewVelocity = null
    }



    private fun showTopBar(autoHideAfterDelay: Boolean = true) {
        topBarHideHandler.removeCallbacks(hideTopBarRunnable)
        // The zoomed-out screens carousel keeps every bar hidden (see enterOverview()).
        if (overviewActive) return
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
        findViewById<View>(R.id.btnSidebarToggle).setOnClickListener { toggleElementsSidebar() }
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







    
    
    
    
    
    
    private fun setupBottomNavBar() {
        val navBarLp = bottomNavBar.layoutParams as? CoordinatorLayout.LayoutParams
        bottomNavBarBaseMarginBottom = navBarLp?.bottomMargin ?: bottomNavBar.marginBottom
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavBar) { _, insets ->
            val navInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val lp = bottomNavBar.layoutParams as? CoordinatorLayout.LayoutParams
            if (lp != null) {
                lp.bottomMargin = bottomNavBarBaseMarginBottom + navInset
                bottomNavBar.layoutParams = lp
            }
            insets
        }

        
        
        
        
        
        bottomNavBar.viewTreeObserver.addOnGlobalLayoutListener { applyNavBarPanelMargins() }
    }

    
    
    
    
    private fun showBottomNavBar() {
        if (overviewActive) return
        if (bottomNavTarget == 1f) return
        animateBottomNavTo(1f)
    }

    // Slides the bar down and fades it out. Elements/Screens panels' bottom margins follow it down
    // frame by frame (see applyBottomNavProgress), so they glide into the freed space in sync.
    private fun hideBottomNavBar() {
        if (bottomNavTarget == 0f) return
        animateBottomNavTo(0f)
    }

    // The bar is driven by a single 0..1 "progress" (0 = hidden, 1 = fully shown) so the bar and
    // the panels that sit above it always move together.
    private var bottomNavProgress = 1f
    private var bottomNavTarget = 1f
    private var bottomNavAnimator: ValueAnimator? = null

    private fun animateBottomNavTo(target: Float) {
        bottomNavTarget = target
        bottomNavAnimator?.cancel()
        val start = bottomNavProgress
        if (target > 0f && bottomNavBar.visibility != View.VISIBLE) {
            applyBottomNavProgress(start)
            bottomNavBar.visibility = View.VISIBLE
        }
        bottomNavAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = (BOTTOM_NAV_BAR_ANIM_MS * abs(target - start)).toLong().coerceAtLeast(1L)
            interpolator = if (target > start) DecelerateInterpolator() else AccelerateInterpolator()
            addUpdateListener { applyBottomNavProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    if (target == 0f) bottomNavBar.visibility = View.GONE
                    bottomNavAnimator = null
                }
            })
            start()
        }
    }

    private fun applyBottomNavProgress(progress: Float) {
        bottomNavProgress = progress
        bottomNavBar.alpha = progress
        bottomNavBar.translationY = (1f - progress) * bottomNavBar.height
        applyNavBarPanelMargins()
    }

    // Elements/Screens panels reserve the bar's height (+ its bottom margin) at the bottom so they
    // sit above it; that reservation shrinks/grows with the bar's animation progress.
    private fun applyNavBarPanelMargins() {
        val margin = ((bottomNavBar.height + bottomNavBar.marginBottom) * bottomNavProgress).roundToInt()
        (elementsPanel.layoutParams as? CoordinatorLayout.LayoutParams)?.let { lp ->
            if (lp.bottomMargin != margin) {
                lp.bottomMargin = margin
                elementsPanel.layoutParams = lp
            }
        }
        (screensPanel.layoutParams as? CoordinatorLayout.LayoutParams)?.let { lp ->
            if (lp.bottomMargin != margin) {
                lp.bottomMargin = margin
                screensPanel.layoutParams = lp
            }
        }
        // The Elements sidebar stops just above the bar too (its own transparent shadow padding
        // supplies most of the visual gap, hence the -8dp), and grows into the freed space when
        // the bar hides.
        val sidebarMargin = (margin - (8 * resources.displayMetrics.density).roundToInt()).coerceAtLeast(0)
        (elementsSidebar.layoutParams as? CoordinatorLayout.LayoutParams)?.let { lp ->
            if (lp.bottomMargin != sidebarMargin) {
                lp.bottomMargin = sidebarMargin
                elementsSidebar.layoutParams = lp
            }
        }
    }

    // ---- Auto-hide while a panel is open --------------------------------------------------------
    // Every bottom sheet (Elements, Screens, Text, Button object) registers here when it opens and
    // unregisters when it closes. The bar hides while ANY panel is open and comes back once none is.
    // The sync is posted and coalesced, so swapping one panel for another in the same tap (e.g.
    // Screens -> Elements) doesn't make the bar flicker in and out.
    private val openPanels = mutableSetOf<View>()
    private var bottomNavSyncPosted = false

    private fun setPanelOpen(panel: View, open: Boolean) {
        val changed = if (open) openPanels.add(panel) else openPanels.remove(panel)
        if (!changed || bottomNavSyncPosted) return
        bottomNavSyncPosted = true
        canvasArea.post {
            bottomNavSyncPosted = false
            // The zoomed-out carousel manages the bar itself (see enterOverview/exitOverview).
            if (overviewActive || overviewTransitioning) return@post
            if (openPanels.isEmpty()) showBottomNavBar() else hideBottomNavBar()
        }
    }

    // Manual toggle (tap on empty canvas space) - unchanged in behavior, it just flips the bar's
    // current state. It now goes by the bar's target rather than its View visibility, since the
    // latter lags behind while the hide animation is still running.
    private fun toggleBottomNavBar() {
        if (bottomNavTarget == 1f) hideBottomNavBar() else showBottomNavBar()
    }

    
    
    
    
    
    private fun setupElementsPanel() {
        elementsPanelBehavior.isDraggable = true
        // Starting peek height/fallback; kept in sync with the real keyboard afterward by the
        // insets listener below. See updateElementsPanelPeekHeight.
        updateElementsPanelPeekHeight(imeInsetBottomPx = 0)

        ViewCompat.setOnApplyWindowInsetsListener(elementsPanel) { _, insets ->
            statusBarInsetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            updateElementsPanelPeekHeight(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            applyElementsPanelTopPadding(elementsPanelBehavior.state == BottomSheetBehavior.STATE_EXPANDED)
            insets
        }

        
        
        elementsPanelBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheetView: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        panelBackPressedCallback.isEnabled = screensPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN
                        setPanelOpen(elementsPanel, false)
                        resetPanelContent()
                    }
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        panelBackPressedCallback.isEnabled = true
                        applyElementsPanelTopPadding(expanded = true)
                    }
                    BottomSheetBehavior.STATE_DRAGGING, BottomSheetBehavior.STATE_SETTLING -> Unit
                    else -> {
                        panelBackPressedCallback.isEnabled = true
                        applyElementsPanelTopPadding(expanded = false)
                    }
                }
            }

            
            
            override fun onSlide(sheetView: View, slideOffset: Float) {
                val progress = slideOffset.coerceIn(0f, 1f)
                applyElementsPanelTopPadding(progressToStatusBarInset = progress)
            }
        })

        
        elementsPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    
    
    
    
    // Keeps elementsPanel's minimum/default (STATE_COLLAPSED) height equal to the real
    // on-screen keyboard height whenever the keyboard is actually showing, so content the
    // keyboard would otherwise cover (e.g. the components search field) stays reachable above
    // it. Falls back to ELEMENTS_PANEL_FALLBACK_PEEK_HEIGHT_DP - the same approximate value
    // screensPanel uses - whenever the keyboard isn't currently up. The panel's maximum height
    // stays full-screen via STATE_EXPANDED (layout_height="match_parent"), unaffected by this.
    private fun updateElementsPanelPeekHeight(imeInsetBottomPx: Int) {
        val fallbackPx = (ELEMENTS_PANEL_FALLBACK_PEEK_HEIGHT_DP * resources.displayMetrics.density).roundToInt()
        val peekHeightPx = if (imeInsetBottomPx > 0) imeInsetBottomPx else fallbackPx
        if (elementsPanelBehavior.peekHeight != peekHeightPx) {
            elementsPanelBehavior.peekHeight = peekHeightPx
        }
    }

    private fun applyElementsPanelTopPadding(expanded: Boolean? = null, progressToStatusBarInset: Float? = null) {
        // Content built inside componentsContentContainer (e.g. buildButtonCategoryPanel()'s
        // root) already carries its own small top padding (dp(8) for the Buttons panel). That
        // stacks on top of statusBarInsetTop below, pushing the label/(x) row farther from the
        // top than necessary at max height. Subtracting it here cancels the double-counting so
        // the row ends up exactly statusBarInsetTop from the true top edge - as close as
        // possible without sitting under the status bar - rather than statusBarInsetTop + 8dp.
        val buttonPanelRootTopPaddingPx = (8 * resources.displayMetrics.density).roundToInt()
        val extra = when {
            progressToStatusBarInset != null ->
                ((statusBarInsetTop - buttonPanelRootTopPaddingPx) * progressToStatusBarInset).roundToInt().coerceAtLeast(0)
            expanded == true -> (statusBarInsetTop - buttonPanelRootTopPaddingPx).coerceAtLeast(0)
            else -> 0
        }
        elementsPanel.setPadding(elementsPanel.paddingLeft, extra, elementsPanel.paddingRight, elementsPanel.paddingBottom)
    }

    
    
    
    private fun openElementsPanel() {
        if (showingScreens) closeScreensPanel()
        if (showingText) closeTextPanel()
        openComponentsCategory(ALL_CATEGORY.id)
    }

    
    
    private fun closeElementsPanel() {
        clearComponentsSelection()
        if (elementsPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            elementsPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        setPanelOpen(elementsPanel, false)
    }

    
    
    private fun resetPanelContent() {
        showingComponents = false
        clearComponentsSelection()
        
        
    }



    private fun showComponentsContent(expanded: Boolean = false) {
        if (!componentsContentBuilt) {
            val handle = buildComponentsContent(
                    context = this,
                    onClose = { closeComponentsContent() },
                    // Tapping the Button preview in the dedicated Buttons panel (see
                    // buildButtonCategoryPanel()) just maxes out the panel's height - it doesn't
                    // place anything on the canvas.
                    onButtonPanelExpandRequested = {
                        elementsPanelBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                    },
                    // The Buttons panel's "+ Add to Canvas" button - places a real Button part,
                    // styled to match whichever preview (frameless/framed) was selected (see
                    // ButtonStyle in SketchPart.kt and buildButtonCategoryPanel()'s
                    // selectedVariant) and carrying its chosen Align (see SketchPart.buttonAlign),
                    // at the active screen's canvas center, then closes the Elements panel - same
                    // pattern as the Screens panel's onPick.
                    onAddButtonToCanvasRequested = { style, align ->
                        addPart(PartKind.BUTTON, canvas.width / 2f, canvas.pageHeight / 2f, label = "Button").apply {
                            buttonStyle = style
                            buttonAlign = align
                        }
                        canvas.invalidate()
                        closeElementsPanel()
                    },
                    // If a placed FRAMED button is currently selected on the Canvas when the
                    // Buttons panel is (re)opened, edit that part instead of composing a fresh
                    // one - see showButtonCategoryPanel() in ComponentsPanel.kt. FRAMELESS/generic
                    // parts have no Align UI equivalent yet, so they're left out of edit mode.
                    getEditableSelectedButton = {
                        canvas.selectedPart?.takeIf { it.kind == PartKind.BUTTON && it.buttonStyle == ButtonStyle.FRAMED }
                    },
                    // Fires on every Align/Label/style change while editing an existing placed
                    // button (see onLiveChanged in buildButtonCategoryPanel()), so the Canvas
                    // reflects the change the instant it's made rather than only on "+ Add to
                    // Canvas" - this is the real-time feedback that was missing.
                    onButtonLiveEdited = { part, style, align, text ->
                        part.buttonStyle = style
                        part.buttonAlign = align
                        part.label = text
                        canvas.invalidate()
                    },
                    // The sheet's search field dims the sidebar rows that no longer match.
                    onSearchFilterChanged = { matching -> elementsSidebar.setSearchMatches(matching) },
                    // e.g. the Buttons panel's back arrow drops back to "All" - the sidebar follows.
                    onActiveCategoryChanged = { id ->
                        currentComponentsCategory = id
                        elementsSidebar.setSelectedCategory(id)
                    },
                )
            componentsContentContainer.addView(handle.view)
            componentsHandle = handle
            componentsContentBuilt = true
        }
        val wasShowing = showingComponents
        showingComponents = true
        setPanelOpen(elementsPanel, true)
        // Keep whatever height the person dragged the sheet to when just switching categories;
        // only pick the default (collapsed) height when the sheet is being opened from hidden.
        // (STATE_DRAGGING / STATE_SETTLING can't be assigned, so the else case leaves it alone.)
        if (expanded) {
            elementsPanelBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        } else if (!wasShowing || elementsPanelBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            elementsPanelBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    // The sidebar's highlight should disappear the moment the sheet is dismissed, not once its
    // slide-out animation finishes.
    private fun clearComponentsSelection() {
        currentComponentsCategory = null
        elementsSidebar.setSelectedCategory(null)
        elementsSidebar.setSearchMatches(null)
    }

    // Opens (or switches) the Elements sheet to [categoryId]'s content and highlights it in the sidebar.
    private fun openComponentsCategory(categoryId: String) {
        showComponentsContent()
        currentComponentsCategory = categoryId
        componentsHandle?.showCategory(categoryId)
        elementsSidebar.setSelectedCategory(categoryId)
    }

    // ---- Elements sidebar ------------------------------------------------------------------

    private fun setupElementsSidebar() {
        elementsSidebar.onCategoryClick = { id ->
            if (showingComponents && currentComponentsCategory == id) {
                // Tapping the active category again dismisses its sheet.
                closeComponentsContent()
            } else {
                if (showingScreens) closeScreensPanel()
                if (showingText) closeTextPanel()
                openComponentsCategory(id)
            }
        }
        elementsSidebar.onOccupiedWidthChanged = { px -> applySidebarInset(px) }
        elementsSidebar.onExpandedChanged = { expanded ->
            uiPrefs.edit().putBoolean(PREF_SIDEBAR_EXPANDED, expanded).apply()
        }
        elementsSidebar.setExpanded(uiPrefs.getBoolean(PREF_SIDEBAR_EXPANDED, false), animate = false)
        elementsSidebar.setShown(uiPrefs.getBoolean(PREF_SIDEBAR_SHOWN, true), animate = false)
    }

    private fun toggleElementsSidebar() {
        val show = !elementsSidebar.isShown
        elementsSidebar.setShown(show, animate = true)
        uiPrefs.edit().putBoolean(PREF_SIDEBAR_SHOWN, show).apply()
        // Nothing to navigate from once the sidebar is gone.
        if (!show && showingComponents) closeComponentsContent()
    }

    // The sidebar is docked over the left edge of the canvas, so every bottom sheet and the
    // multi-selection actions panel starts to its right instead of sliding underneath it.
    private fun applySidebarInset(insetPx: Int) {
        for (panel in listOf(elementsPanel, screensPanel, buttonObjectPanel, textPanel, screenThumbnailsRow)) {
            (panel.layoutParams as? CoordinatorLayout.LayoutParams)?.let { lp ->
                if (lp.marginStart != insetPx) {
                    lp.marginStart = insetPx
                    panel.layoutParams = lp
                }
            }
        }
        (selectionActionsPanel.layoutParams as? CoordinatorLayout.LayoutParams)?.let { lp ->
            val start = insetPx + (16 * resources.displayMetrics.density).roundToInt()
            if (lp.marginStart != start) {
                lp.marginStart = start
                selectionActionsPanel.layoutParams = lp
            }
        }
    }



    private fun closeComponentsContent() {
        showingComponents = false
        clearComponentsSelection()
        
        
        setPanelOpen(elementsPanel, false)
        elementsPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    
    
    
    
    
    
    private fun setupScreensPanel() {
        screensPanelBehavior.isDraggable = true

        ViewCompat.setOnApplyWindowInsetsListener(screensPanel) { _, insets ->
            statusBarInsetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            applyScreensPanelTopPadding(screensPanelBehavior.state == BottomSheetBehavior.STATE_EXPANDED)
            insets
        }

        
        
        screensPanelBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheetView: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        showingScreens = false
                        setPanelOpen(screensPanel, false)
                        panelBackPressedCallback.isEnabled = elementsPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN
                    }
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        panelBackPressedCallback.isEnabled = true
                        applyScreensPanelTopPadding(expanded = true)
                        // Normally already faded out via onSlide's progress by the time the sheet
                        // actually reaches this state; set directly too as a safety net for a
                        // programmatic state change that skips the drag/slide callbacks.
                        screenThumbnailsRow.alpha = 0f
                        screenThumbnailsRow.visibility = View.GONE
                    }
                    BottomSheetBehavior.STATE_DRAGGING, BottomSheetBehavior.STATE_SETTLING -> Unit
                    else -> {
                        panelBackPressedCallback.isEnabled = true
                        applyScreensPanelTopPadding(expanded = false)
                        if (showingScreens) {
                            screenThumbnailsRow.alpha = 1f
                            screenThumbnailsRow.visibility = View.VISIBLE
                        }
                    }
                }
                positionScreenThumbnailsRow()
            }

            override fun onSlide(sheetView: View, slideOffset: Float) {
                
                
                if (slideOffset > 0f) {
                    // 0 at collapsed, 1 at fully expanded (max height) - reused both for the
                    // status-bar top padding below and to fade the floating thumbnails row out
                    // as the sheet is dragged up toward max height, rather than having it just
                    // snap away the instant STATE_EXPANDED is reached.
                    val progress = slideOffset.coerceIn(0f, 1f)
                    applyScreensPanelTopPadding(progressToStatusBarInset = progress)
                    if (showingScreens) {
                        screenThumbnailsRow.alpha = 1f - progress
                        screenThumbnailsRow.visibility = if (progress >= 1f) View.GONE else View.VISIBLE
                    }
                } else if (showingScreens) {
                    // Below the collapsed anchor (dragging toward hidden) - not part of the
                    // max-height fade, so keep the row at full opacity.
                    screenThumbnailsRow.alpha = 1f
                    screenThumbnailsRow.visibility = View.VISIBLE
                }
                positionScreenThumbnailsRow()
            }
        })

        
        screensPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        
        
        
        screensPanel.viewTreeObserver.addOnGlobalLayoutListener { positionScreenThumbnailsRow() }
    }

    
    
    
    
    // Simplest of the three panel sheets: no IME-aware peek height (elementsPanel) or floating
    // thumbnails row to sync (screensPanel) - just the plain two-tier peek/expanded sheet declared
    // in activity_sketch.xml (app:behavior_peekHeight="280dp" = min = default resting height,
    // STATE_EXPANDED = max/fullscreen). Content is the Design/Prototype/customButton row from
    // ButtonObjectPanel.kt (built lazily - see openButtonObjectPanel() below) plus, set up here,
    // the two swappable panel backgrounds and the localized corner-shadow view that
    // customButton's selection toggles between (see updateButtonObjectPanelCornerState()).
    private fun setupButtonObjectPanel() {
        buttonObjectPanelBehavior.isDraggable = true

        // Rounded matches bg_bottom_panel.xml's own look (24dp top corners, #F3F4F6 fill) - built
        // as GradientDrawables rather than left as that static XML drawable so the corners can be
        // swapped to flat/square at runtime without needing a second static drawable resource.
        val panelFillColor = Color.parseColor("#F3F4F6")
        val panelCornerRadiusPx = dp(24f)
        buttonObjectPanelBgRounded = GradientDrawable().apply {
            setColor(panelFillColor)
            cornerRadii = floatArrayOf(
                panelCornerRadiusPx, panelCornerRadiusPx,
                panelCornerRadiusPx, panelCornerRadiusPx,
                0f, 0f,
                0f, 0f,
            )
        }
        buttonObjectPanelBgSquare = GradientDrawable().apply {
            setColor(panelFillColor)
            cornerRadii = FloatArray(8) { 0f }
        }
        buttonObjectPanel.background = buttonObjectPanelBgRounded

        // Added as a sibling of buttonObjectPanel (both direct children of the root
        // CoordinatorLayout) so it can float above the panel's top edge rather than being clipped
        // to the panel's own bounds - same relationship screenThumbnailsRow has to screensPanel.
        // Elevation kept below buttonObjectPanel's own (8dp, set in activity_sketch.xml) so the
        // panel draws over it where they meet, leaving only the blur peeking out above the edge.
        buttonObjectCornerShadowView = buildButtonObjectCornerShadowView(this).also { shadow ->
            shadow.elevation = dp(3f)
            (buttonObjectPanel.parent as ViewGroup).addView(shadow)
        }

        buttonObjectPanelBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheetView: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    showingButtonObjectPanel = false
                    setPanelOpen(buttonObjectPanel, false)
                    panelBackPressedCallback.isEnabled = elementsPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN ||
                        screensPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN
                } else if (newState != BottomSheetBehavior.STATE_DRAGGING && newState != BottomSheetBehavior.STATE_SETTLING) {
                    panelBackPressedCallback.isEnabled = true
                }
            }

            // The panel's top edge (buttonObjectPanel.top) moves as it's dragged - keep the
            // corner-shadow strip (when visible) tracking it rather than left behind mid-drag.
            override fun onSlide(sheetView: View, slideOffset: Float) = positionButtonObjectCornerShadow()
        })

        buttonObjectPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        buttonObjectPanel.viewTreeObserver.addOnGlobalLayoutListener { positionButtonObjectCornerShadow() }
    }

    // Reflects buttonObjectPanelPart's persisted objectPanelMode onto the panel chrome that isn't
    // owned by ButtonObjectPanel.kt's own view tree: flat/square top corners plus the localized
    // corner-shadow strip under customButton while CUSTOM is selected, back to the normal rounded
    // look otherwise. Called right after every mode change/sync (see onModeChanged below and
    // openButtonObjectPanel()).
    private fun updateButtonObjectPanelCornerState() {
        val squared = buttonObjectPanelPart?.objectPanelMode == ButtonObjectMode.CUSTOM
        buttonObjectPanel.background = if (squared) buttonObjectPanelBgSquare else buttonObjectPanelBgRounded
        buttonObjectCornerShadowView.visibility = if (squared) View.VISIBLE else View.GONE
        buttonObjectCornerShadowView.post { positionButtonObjectCornerShadow() }
    }

    // Lines the corner-shadow strip up with customButton: horizontally, directly under it
    // (walking up the view tree from customButton to buttonObjectPanel to accumulate the offset,
    // since the strip is a sibling of buttonObjectPanel rather than nested inside it); vertically,
    // flush against buttonObjectPanel's current top edge - same translation-based approach as
    // positionScreenThumbnailsRow() uses for screenThumbnailsRow above.
    private fun positionButtonObjectCornerShadow() {
        if (!::buttonObjectCornerShadowView.isInitialized || buttonObjectCornerShadowView.visibility != View.VISIBLE) return
        val customButtonView = buttonObjectPanelViews?.customButtonView ?: return
        var offsetX = 0f
        var v: View = customButtonView
        while (v !== buttonObjectPanel && v.parent is View) {
            offsetX += v.left
            v = v.parent as View
        }
        buttonObjectCornerShadowView.translationX = buttonObjectPanel.left + offsetX
        buttonObjectCornerShadowView.translationY = buttonObjectPanel.top.toFloat() - buttonObjectCornerShadowView.height
    }

    // Opens the Button object panel at its default (collapsed/peek) height - same resting height
    // as its minimum, per setupButtonObjectPanel() above. Builds the Design/Prototype/customButton
    // row into buttonObjectContentContainer on first open, then on every open (including
    // subsequent ones) syncs it - and the panel's corner state - to `part`'s own persisted
    // objectPanelMode, so reopening the panel on a different Button part, or the same one later,
    // always shows that part's last-selected mode rather than whatever was left selected from
    // editing a previous part.
    private fun openButtonObjectPanel(part: SketchPart) {
        if (showingComponents) closeComponentsContent()
        if (showingScreens) closeScreensPanel()
        if (showingText) closeTextPanel()
        buttonObjectPanelPart = part
        val views = buttonObjectPanelViews ?: buildButtonObjectPanelContent(
            context = this,
            initialMode = part.objectPanelMode,
            onModeChanged = { mode ->
                buttonObjectPanelPart?.objectPanelMode = mode
                updateButtonObjectPanelCornerState()
            },
        ).also {
            buttonObjectContentContainer.addView(it.root)
            buttonObjectPanelViews = it
        }
        views.setActiveMode(part.objectPanelMode)
        updateButtonObjectPanelCornerState()
        showingButtonObjectPanel = true
        setPanelOpen(buttonObjectPanel, true)
        buttonObjectPanelBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun closeButtonObjectPanel() {
        if (buttonObjectPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            buttonObjectPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        setPanelOpen(buttonObjectPanel, false)
    }

    
    
    
    
    
    private fun positionScreenThumbnailsRow() {
        if (!::screenThumbnailsRow.isInitialized || screenThumbnailsRow.visibility != View.VISIBLE) return
        val gapPx = dp(12f)
        val targetBottom = screensPanel.top - gapPx
        screenThumbnailsRow.translationY = targetBottom - screenThumbnailsRow.bottom.toFloat()
    }

    
    
    
    
    private fun applyScreensPanelTopPadding(expanded: Boolean? = null, progressToStatusBarInset: Float? = null) {
        val extra = when {
            progressToStatusBarInset != null -> (statusBarInsetTop * progressToStatusBarInset).roundToInt()
            expanded == true -> statusBarInsetTop
            else -> 0
        }
        screensPanel.setPadding(screensPanel.paddingLeft, extra, screensPanel.paddingRight, screensPanel.paddingBottom)
    }

    
    
    
    
    
    private fun openScreensPanel() {
        if (!screensContentBuilt) {
            val activePage = screenPages.first { it.id == selectedScreenPageId }
            screensPanelContentViews = buildScreensPanelContent(
                context = this,
                initialTitle = activePage.name,
                onPick = { kind ->
                    addPart(kind, canvas.width / 2f, canvas.pageHeight / 2f)
                    closeScreensPanel()
                },
                onClose = { closeScreensPanel() },
                onTitleRenamed = { newName -> renameSelectedScreenPage(newName) },
            )
            screensContentContainer.addView(screensPanelContentViews.root)

            val thumbnailsRowViews = buildScreenThumbnailsRow(
                context = this,
                pages = screenPages,
                selectedPageId = selectedScreenPageId,
                hasContent = { page -> hasScreenContent(page) },
                onSelectPage = { page -> selectScreenPage(page) },
                onAddPageClick = { showUsedTypeAwareScreenPicker() },
            )
            screenThumbnailsRow.addView(thumbnailsRowViews.root)
            screenThumbnailsContainer = thumbnailsRowViews.thumbnailsContainer
            screensContentBuilt = true
        }
        showingScreens = true
        panelBackPressedCallback.isEnabled = true
        
        
        screenThumbnailsRow.visibility = View.VISIBLE
        screenThumbnailsRow.alpha = 1f
        screenThumbnailsRow.post { positionScreenThumbnailsRow() }
        setPanelOpen(screensPanel, true)
        screensPanelBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    
    
    
    // Home/Onboarding/Splash stay capped at one screen each (usedTypes below); Blank is the one
    // exception - it's excluded from usedTypes so the picker never filters it out, and users can
    // add as many Blank screens as they like.
    private fun showUsedTypeAwareScreenPicker() {
        val usedTypes = screenPages.map { it.type }.filter { it != ScreenPageType.BLANK }.toSet()
        showScreenTypePicker(this, usedTypes) { type -> addScreenPage(type) }
    }

    // Home/Onboarding/Splash: one screen per type - showUsedTypeAwareScreenPicker() already
    // filters the type out of the picker once it exists, this check is just a defensive
    // backstop. Blank is exempt from that cap entirely, so it's excluded from the check.
    private fun addScreenPage(type: ScreenPageType) {
        if (type != ScreenPageType.BLANK && screenPages.any { it.type == type }) return
        val page = ScreenPage(id = nextScreenPageId++, type = type)
        screenPages.add(page)
        selectedScreenPageId = page.id
        switchToScreenPage(page)
        refreshScreenThumbnails()
        if (screensContentBuilt) screensPanelContentViews.setDisplayedTitle(page.name)
    }

    // A Blank screen's thumbnail label switches from "Blank" to "Normal" once it actually has
    // content (see hasContent in buildScreenThumbnailsRow/renderScreenThumbnails). Other types
    // keep their fixed label regardless, so this only needs to answer the question for Blank.
    private fun hasScreenContent(page: ScreenPage): Boolean =
        screenCanvases[page.id]?.parts?.isNotEmpty() == true

    
    private fun selectScreenPage(page: ScreenPage) {
        if (selectedScreenPageId == page.id) return
        selectedScreenPageId = page.id
        switchToScreenPage(page)
        refreshScreenThumbnails()
        if (screensContentBuilt) screensPanelContentViews.setDisplayedTitle(page.name)
    }

    // Renames the currently active screen (the one next to the back button in the Screens
    // panel header - see buildScreensPanelContent()'s title field). Only ScreenPage.name
    // changes; the page's type (and therefore its thumbnail label) is untouched.
    private fun renameSelectedScreenPage(newName: String) {
        val index = screenPages.indexOfFirst { it.id == selectedScreenPageId }
        if (index == -1) return
        screenPages[index] = screenPages[index].copy(name = newName)
    }

    // Swaps the visible SketchCanvasView to the one belonging to `page`, creating and seeding it
    // with its starter template on first visit (see getOrCreateCanvas/applyStarterTemplate).
    // Any in-progress name-tag edit or multi-selection on the outgoing canvas is settled first,
    // since both are read through the shared `canvas` field.
    private fun switchToScreenPage(page: ScreenPage) {
        val target = getOrCreateCanvas(page)
        if (target === canvas) return
        if (editingNamePart != null) commitNameTagEdit()
        if (selectionActionsPanel.visibility == View.VISIBLE) dismissSelectionActionsPanel(clearSelection = true)
        canvas.visibility = View.GONE
        target.visibility = View.VISIBLE
        canvas = target
    }

    // Creates and registers a page's SketchCanvasView the first time it's needed. New canvases
    // start hidden (GONE) and sit at the bottom of canvasArea (index 0), below the top bar,
    // name-tag editor, and other floating chrome - see activity_sketch.xml.
    private fun getOrCreateCanvas(page: ScreenPage): SketchCanvasView {
        screenCanvases[page.id]?.let { return it }
        val created = SketchCanvasView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            listener = canvasListener
        }
        canvasArea.addView(created, 0)
        screenCanvases[page.id] = created
        applyStarterTemplate(created, page.type)
        return created
    }

    // Seeds a freshly created screen with placeholder content appropriate to its type. Blank
    // screens are left empty. Since `created` may still be GONE/unlaid-out at this point (its
    // own width/pageHeight can be 0), sizing is borrowed from the currently active canvas -
    // every screen's canvas fills the same canvasArea, so their dimensions always match once
    // laid out - falling back to canvasArea's own measured size if that isn't available yet
    // either (e.g. the very first Home screen, seeded from onCreate before any layout pass).
    private fun applyStarterTemplate(created: SketchCanvasView, type: ScreenPageType) {
        val refWidth = canvas.width.takeIf { it > 0 } ?: canvasArea.width
        val refPageHeight = canvas.pageHeight.takeIf { it > 0f } ?: canvasArea.height.toFloat()
        created.ensurePageHeight(refPageHeight)
        val h = created.pageHeight

        fun place(kind: PartKind, x: Float, y: Float, label: String = "") =
            addPartTo(created, kind, x, y, label, canvasWidth = refWidth, pageHeight = h)

        when (type) {
            ScreenPageType.HOME -> {
                place(PartKind.TOP_APP_BAR, 0f, 0f, "Home")
                place(PartKind.CARD, refWidth / 2f, h * 0.42f)
                place(PartKind.NAV_BAR, 0f, 0f)
            }
            ScreenPageType.ONBOARDING -> {
                place(PartKind.IMAGE, refWidth / 2f, h * 0.32f)
                place(PartKind.TEXT, refWidth / 2f, h * 0.58f, "Welcome")
                place(PartKind.BUTTON, refWidth / 2f, h * 0.78f, "Get started")
            }
            ScreenPageType.SPLASH -> {
                place(PartKind.IMAGE, refWidth / 2f, h * 0.42f)
                place(PartKind.TEXT, refWidth / 2f, h * 0.58f, "App name")
            }
            ScreenPageType.BLANK -> Unit
        }
        // addPart() leaves the last-placed part selected; that would make the screen look
        // pre-selected the first time it's shown, so clear it back to a neutral empty-selection
        // state.
        created.clearSelection()
    }

    
    private fun refreshScreenThumbnails() {
        if (!screensContentBuilt) return
        renderScreenThumbnails(
            container = screenThumbnailsContainer,
            context = this,
            pages = screenPages,
            selectedPageId = selectedScreenPageId,
            hasContent = { page -> hasScreenContent(page) },
            onSelectPage = { page -> selectScreenPage(page) },
            onAddPageClick = { showUsedTypeAwareScreenPicker() },
        )
    }

    
    
    private fun closeScreensPanel() {
        if (screensPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            screensPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        showingScreens = false
        setPanelOpen(screensPanel, false)
        screenThumbnailsRow.visibility = View.GONE
    }

    
    
    
    
    
    
    // Text panel: same fuller wiring as screensPanel above (status-bar inset top-padding synced
    // while dragging/expanded, plus back-press/other-panel integration via showingText) rather
    // than buttonObjectPanel's simpler two-tier setup - just without a floating thumbnails row to
    // sync, since textContentContainer has no content yet (see the panel's declaration in
    // activity_sketch.xml).
    private fun setupTextPanel() {
        textPanelBehavior.isDraggable = true

        ViewCompat.setOnApplyWindowInsetsListener(textPanel) { _, insets ->
            statusBarInsetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            applyTextPanelTopPadding(textPanelBehavior.state == BottomSheetBehavior.STATE_EXPANDED)
            insets
        }

        textPanelBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheetView: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        showingText = false
                        setPanelOpen(textPanel, false)
                        panelBackPressedCallback.isEnabled = elementsPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN ||
                            screensPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN
                    }
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        panelBackPressedCallback.isEnabled = true
                        applyTextPanelTopPadding(expanded = true)
                    }
                    BottomSheetBehavior.STATE_DRAGGING, BottomSheetBehavior.STATE_SETTLING -> Unit
                    else -> {
                        panelBackPressedCallback.isEnabled = true
                        applyTextPanelTopPadding(expanded = false)
                    }
                }
            }

            override fun onSlide(sheetView: View, slideOffset: Float) {
                if (slideOffset > 0f) {
                    // 0 at collapsed, 1 at fully expanded (max height) - same status-bar top
                    // padding purpose as screensPanel's onSlide above.
                    applyTextPanelTopPadding(progressToStatusBarInset = slideOffset.coerceIn(0f, 1f))
                }
            }
        })

        textPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    
    
    
    private fun applyTextPanelTopPadding(expanded: Boolean? = null, progressToStatusBarInset: Float? = null) {
        val extra = when {
            progressToStatusBarInset != null -> (statusBarInsetTop * progressToStatusBarInset).roundToInt()
            expanded == true -> statusBarInsetTop
            else -> 0
        }
        textPanel.setPadding(textPanel.paddingLeft, extra, textPanel.paddingRight, textPanel.paddingBottom)
    }

    
    
    
    // Opens the Text panel at its default (collapsed/peek) height - same resting height as its
    // minimum, per setupTextPanel() above. Builds the "Add text" action row into
    // textContentContainer on first open (see buildTextPanelContent() in TextPanel.kt) - tapping
    // it places a Text part on the Canvas and closes the panel immediately, same "add + close"
    // behavior the old showTextInputDialog() flow ended with, just without its text-entry step.
    private fun openTextPanel() {
        if (!textContentBuilt) {
            textContentContainer.addView(
                buildTextPanelContent(
                    context = this,
                    onAddTextRequested = {
                        addPart(PartKind.TEXT, canvas.width / 2f, canvas.pageHeight / 2f, label = "Text")
                        canvas.invalidate()
                        closeTextPanel()
                    },
                )
            )
            textContentBuilt = true
        }
        showingText = true
        panelBackPressedCallback.isEnabled = true
        setPanelOpen(textPanel, true)
        textPanelBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun closeTextPanel() {
        if (textPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            textPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        showingText = false
        setPanelOpen(textPanel, false)
    }



    private fun setupTabs() {
        
        
        (bottomNavBar.parent as? ViewGroup)?.clipChildren = false
        bottomNavBar.clipChildren = false
        bottomNavBar.clipToPadding = false

        pillVisuals = allTabs.map { column ->
            
            
            column.clipChildren = false
            column.clipToPadding = false
            val pill = column.getChildAt(0) as LinearLayout
            val label = column.getChildAt(1) as TextView
            val fill = buildPillFillDrawable()
            pill.background = fill
            pill.elevation = pillBaseElevationPx
            
            
            pill.outlineProvider = null
            pill.clipToOutline = false
            TabPillVisual(
                pill = pill,
                fill = fill,
                icon = pill.getChildAt(0) as ImageView,
                label = label,
            )
        }
        bottomNavBar.onSelectionProgress = { oldIndex, newIndex, t -> applySelectionProgress(oldIndex, newIndex, t) }

        
        setTabActive(tabSelect, animate = false)
        
        
        
        bottomNavBar.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (pillVisuals.any { it.pill.height > 0 }) {
                    val startIndex = allTabs.indexOf(tabSelect)
                    applySelectionProgress(startIndex, startIndex, 1f)
                    bottomNavBar.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })

        tabScreens.setOnClickListener { selectShapesTab() }
        tabText.setOnClickListener { selectTextTab() }
        tabMedia.setOnClickListener { selectMediaTab() }
        tabSelect.setOnClickListener { selectSelectTab() }

        
        
        
        
        bottomNavBar.onTabDragMove = { index ->
            allTabs.getOrNull(index)?.let { setTabActive(it) }
        }

        
        
        bottomNavBar.onTabDragSelect = { index ->
            when (allTabs.getOrNull(index)) {
                tabScreens -> selectShapesTab()
                tabText -> selectTextTab()
                tabMedia -> selectMediaTab()
                tabSelect -> selectSelectTab()
                else -> {}
            }
        }
    }

    private fun selectShapesTab() {
        if (showingComponents) closeComponentsContent()
        if (showingText) closeTextPanel()
        setTabActive(tabScreens)
        openScreensPanel()
    }

    private fun selectTextTab() {
        if (showingComponents) closeComponentsContent()
        if (showingScreens) closeScreensPanel()
        setTabActive(tabText)
        openTextPanel()
    }

    private fun selectMediaTab() {
        openPartPickerFromTab(tabMedia, "Upload", listOf(PartKind.IMAGE))
    }

    private fun selectSelectTab() {
        
        
        if (showingComponents) closeComponentsContent()
        if (showingScreens) closeScreensPanel()
        if (showingText) closeTextPanel()
        setTabActive(tabSelect)
    }

    
    
    private fun openPartPickerFromTab(tab: LinearLayout, title: String, kinds: List<PartKind>) {
        if (showingComponents) closeComponentsContent()
        if (showingScreens) closeScreensPanel()
        if (showingText) closeTextPanel()
        setTabActive(tab)
        openPartPicker(
            title = title,
            kinds = kinds,
            x = canvas.width / 2f,
            y = canvas.pageHeight / 2f,
            onDismiss = { },
        )
    }


    
    
    
    private fun setTabActive(active: LinearLayout, animate: Boolean = true) {
        val index = allTabs.indexOf(active)
        if (index < 0) return
        bottomNavBar.setActiveTabIndex(index, animate)
    }

    
    
    
    
    
    
    private fun applySelectionProgress(oldIndex: Int, newIndex: Int, t: Float) {
        val evaluator = ArgbEvaluator()
        pillVisuals.forEachIndexed { i, visual ->
            val localT = when (i) {
                newIndex -> t
                oldIndex -> 1f - t
                else -> 0f
            }
            val clamped = localT.coerceIn(0f, 1f)
            visual.fill.alpha = (clamped * 255f).roundToInt().coerceIn(0, 255)
            
            
            
            
            
            val pillHeight = if (visual.pill.height > 0) {
                visual.pill.height.toFloat()
            } else {
                visual.pill.measuredHeight.toFloat()
            }
            val fullRisePx = -(navBarTopPaddingPx + pillHeight / 2f)
            visual.pill.translationY = fullRisePx * localT
            visual.pill.elevation = pillBaseElevationPx + (pillRaisedElevationPx - pillBaseElevationPx) * clamped
            // Selected pill grows 25% larger than its resting size; scaling the whole view
            // (rather than resizing padding) enlarges the rounded-square frame and its corner
            // radius together, proportionally, without disturbing sibling tab layout.
            val pillScale = 1f + pillSelectedScaleBoost * clamped
            visual.pill.scaleX = pillScale
            visual.pill.scaleY = pillScale
            val color = evaluator.evaluate(clamped, inactiveTextColor, activeTextColor) as Int
            visual.icon.setColorFilter(color)
            visual.label.setTextColor(color)
            // Base label margin was tightened by 1dp for the unselected look; restore the
            // original spacing as a tab becomes active so only unselected tabs look tighter.
            visual.label.translationY = dp(1f) * clamped
        }
    }



    
    
    
    

    private fun setupSelectionActionsPanel() {
        actionGroupToggle.setOnClickListener {
            canvas.toggleGroupSelection()
            dismissSelectionActionsPanel(clearSelection = true)
        }
        actionDuplicateSel.setOnClickListener {
            canvas.duplicateSelection()
            dismissSelectionActionsPanel(clearSelection = true)
        }
        actionMoveSel.setOnClickListener {
            
            
            dismissSelectionActionsPanel(clearSelection = false)
        }
        actionLockToggleSel.setOnClickListener {
            canvas.setSelectionLocked(!canvas.isSelectionLocked())
            dismissSelectionActionsPanel(clearSelection = true)
        }
        actionHideToggleSel.setOnClickListener {
            canvas.setSelectionHidden(!canvas.isSelectionHidden())
            dismissSelectionActionsPanel(clearSelection = true)
        }
        actionDeleteSel.setOnClickListener {
            
            
            canvas.deleteSelection()
        }
    }

    private fun updateSelectionActionLabels() {
        actionGroupToggleLabel.text = if (canvas.isSelectionGrouped()) "Ungroup" else "Group"
    }

    
    private fun showSelectionActionsPanel() {
        updateSelectionActionLabels()
        panelBackPressedCallback.isEnabled = true
        selectionActionsPanel.animate().cancel()
        selectionActionsPanel.alpha = 1f
        selectionActionsPanel.visibility = View.VISIBLE
        selectionActionsPanel.translationX = 0f
        selectionActionsPanel.post {
            val dp24 = 24f * resources.displayMetrics.density
            selectionActionsPanel.translationX = -(selectionActionsPanel.width.toFloat() + dp24)
            selectionActionsPanel.animate().translationX(0f).setDuration(200).start()
        }
    }

    
    
    private fun dismissSelectionActionsPanel(clearSelection: Boolean) {
        hideSelectionActionsPanel()
        if (clearSelection) canvas.clearMultiSelection()
    }

    
    
    
    
    private fun hideSelectionActionsPanel() {
        if (selectionActionsPanel.visibility != View.VISIBLE) return
        val dp24 = 24f * resources.displayMetrics.density
        selectionActionsPanel.animate().cancel()
        selectionActionsPanel.animate()
            .translationX(-(selectionActionsPanel.width.toFloat() + dp24))
            .setDuration(160)
            .withEndAction {
                selectionActionsPanel.visibility = View.INVISIBLE
                selectionActionsPanel.translationX = 0f
            }
            .start()
        panelBackPressedCallback.isEnabled = elementsPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN
    }

    private fun notAvailableYet(feature: String) {
        Toast.makeText(this, "$feature isn't available yet", Toast.LENGTH_SHORT).show()
    }

    
    
    
    
    private fun setupNameTagEditor() {
        nameTagEditor.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitNameTagEdit()
                true
            } else {
                false
            }
        }
        nameTagEditor.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitNameTagEdit()
        }
    }

    
    
    
    private fun startNameTagEdit(part: SketchPart) {
        editingNamePart = part
        val rect = canvas.nameTagScreenRect(part)
        val lp = nameTagEditor.layoutParams as FrameLayout.LayoutParams
        lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
        lp.leftMargin = rect.left.roundToInt()
        lp.topMargin = rect.top.roundToInt()
        nameTagEditor.layoutParams = lp
        nameTagEditor.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            canvas.nameTagBaseTextSizePx() * canvas.currentScale(),
        )
        // Dark text on the page, white when the tag sits over the black outer area.
        val overPage = canvas.isNameTagOverPage(part)
        nameTagEditor.setTextColor(if (overPage) 0xFF1D1B20.toInt() else Color.WHITE)
        nameTagEditor.setHintTextColor(if (overPage) 0x801D1B20.toInt() else 0x80FFFFFF.toInt())
        nameTagEditor.setText(part.name)
        nameTagEditor.visibility = View.VISIBLE
        nameTagEditor.requestFocus()
        nameTagEditor.setSelection(nameTagEditor.text?.length ?: 0)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(nameTagEditor, InputMethodManager.SHOW_IMPLICIT)
    }

    
    
    private fun commitNameTagEdit() {
        val part = editingNamePart ?: return
        editingNamePart = null
        val newName = nameTagEditor.text?.toString().orEmpty()
        canvas.renamePart(part, newName)
        nameTagEditor.visibility = View.GONE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(nameTagEditor.windowToken, 0)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Zoomed-out screens carousel: it owns every touch (swipe / tap / pinch-out) and nothing
        // underneath - canvases, panels, bars - ever sees them. Events that are left over from the
        // gesture that entered/left the carousel are swallowed until the next fresh ACTION_DOWN.
        if (overviewTransitioning) return true
        if (overviewSwallowUntilDown) {
            if (ev.actionMasked != android.view.MotionEvent.ACTION_DOWN) return true
            overviewSwallowUntilDown = false
        }
        if (overviewActive) {
            handleOverviewTouch(ev)
            return true
        }
        
        
        if (editingNamePart != null && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val rect = android.graphics.Rect()
            nameTagEditor.getGlobalVisibleRect(rect)
            if (!rect.contains(ev.rawX.roundToInt(), ev.rawY.roundToInt())) {
                commitNameTagEdit()
            }
        }
        return super.dispatchTouchEvent(ev)
    }






    // ------------------------------------------------------------------------------------------
    // Zoomed-out screens carousel
    //
    // Pinching the active screen down to its minimum zoom (0.5x) enters this mode:
    //  - every panel, sheet, bar and sidebar is hidden (system bars are left alone so nothing
    //    resizes/shifts - each page is exactly the size it already is at 0.5x),
    //  - all screens' canvases are pinned to 0.5x, made transparent outside their page and laid
    //    out side by side (one page-width + a small gap apart) so the current screen sits centered
    //    with its neighbours peeking in,
    //  - swiping moves one screen at a time, tapping a screen opens it, pinching out (or Back)
    //    leaves the carousel. Navigate-only: no editing happens while zoomed out.
    // ------------------------------------------------------------------------------------------

    private var overviewActive = false
    private var overviewEnterPending = false
    private var overviewSwallowUntilDown = false
    private var overviewEntryIndex = 0
    // Fractional index into screenPages of the screen currently centered (e.g. 1.4 = 40% of the
    // way from screen 1 toward screen 2 while swiping).
    private var overviewPos = 0f
    private var overviewBottomNavWasVisible = false
    private var overviewSnapAnimator: ValueAnimator? = null

    // Enter/leave animation. While overviewTransitioning every touch is swallowed. overviewExiting
    // marks the leave animation specifically (overviewActive is already false during it, so the
    // bars are allowed to fade back in). overviewSpread scales the gap between screens so the
    // neighbours slide out from behind the current one while entering (1 = normal layout).
    private var overviewTransitioning = false
    private var overviewExiting = false
    private var overviewSpread = 1f
    private var overviewTransitionAnimator: ValueAnimator? = null

    private var overviewVelocity: VelocityTracker? = null
    private var overviewDownX = 0f
    private var overviewLastX = 0f
    private var overviewStartIndex = 0
    private var overviewDragging = false
    private var overviewMultiTouch = false
    private var overviewPinchScale = 1f
    private val overviewTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val overviewGapPx by lazy { dp(OVERVIEW_GAP_DP) }

    // Distance between two neighbouring screens' centers: one page width at min zoom + the gap.
    private val overviewStridePx: Float
        get() = canvasArea.width * canvas.minZoom + overviewGapPx

    private val overviewBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitOverview(overviewEntryIndex)
    }

    private val overviewScaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                overviewPinchScale = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                overviewPinchScale *= detector.scaleFactor
                if (overviewPinchScale > OVERVIEW_PINCH_OUT_THRESHOLD) {
                    exitOverview(overviewPos.roundToInt())
                }
                return true
            }
        })
    }

    private fun enterOverview() {
        if (overviewActive) return
        overviewActive = true
        // Captured before the panels are closed below: the bar is auto-hidden while a panel is
        // open, and it should still come back after the carousel in that case.
        overviewBottomNavWasVisible = bottomNavTarget == 1f || openPanels.isNotEmpty()

        // Abort whatever gesture on the canvas got us here, then swallow the rest of it.
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        canvas.dispatchTouchEvent(cancel)
        cancel.recycle()
        overviewSwallowUntilDown = true

        // Settle transient UI, and drop selections on every screen so no selection chrome shows.
        if (editingNamePart != null) commitNameTagEdit()
        dismissSelectionActionsPanel(clearSelection = true)
        for (c in screenCanvases.values) {
            c.clearMultiSelection()
            c.clearSelection()
        }

        // Hide every panel / bar / sidebar.
        closeElementsPanel()
        closeScreensPanel()
        closeButtonObjectPanel()
        closeTextPanel()
        elementsSidebar.setSuppressed(true)
        topBarHideHandler.removeCallbacks(hideTopBarRunnable)
        hideTopBar()
        hideBottomNavBar()

        // Lay the screens out as a carousel centered on the one we came from.
        overviewEntryIndex = screenPages.indexOfFirst { it.id == selectedScreenPageId }.coerceAtLeast(0)
        overviewPos = overviewEntryIndex.toFloat()
        for (page in screenPages) screenCanvases[page.id]?.enterOverview()
        overviewBackPressedCallback.isEnabled = true
        startEnterOverviewAnimation()
    }

    private fun setOverviewNeighborAlpha(alpha: Float) {
        screenPages.forEachIndexed { i, page ->
            if (i != overviewEntryIndex) screenCanvases[page.id]?.alpha = alpha
        }
    }

    // Neighbours fade in while sliding out from behind the current screen (which is already at
    // 0.5x from the pinch that got us here, so it needs no animation of its own).
    private fun startEnterOverviewAnimation() {
        overviewTransitioning = true
        overviewSpread = OVERVIEW_ENTER_SPREAD
        setOverviewNeighborAlpha(0f)
        layoutOverview()
        overviewTransitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OVERVIEW_TRANSITION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                overviewSpread = OVERVIEW_ENTER_SPREAD + (1f - OVERVIEW_ENTER_SPREAD) * t
                setOverviewNeighborAlpha(t)
                layoutOverview()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overviewSpread = 1f
                    setOverviewNeighborAlpha(1f)
                    layoutOverview()
                    overviewTransitioning = false
                    overviewTransitionAnimator = null
                }
            })
            start()
        }
    }

    // Positions every screen's canvas for the current overviewPos. Each canvas is a full-size
    // view whose (0.5x-scaled) page is centered, so shifting the view horizontally by
    // (index - overviewPos) * stride slides its page to the right slot. Only screens near the
    // center are kept VISIBLE.
    private fun layoutOverview() {
        val stride = overviewStridePx
        screenPages.forEachIndexed { i, page ->
            val c = screenCanvases[page.id] ?: return@forEachIndexed
            val offset = i - overviewPos
            c.translationX = offset * stride * overviewSpread
            c.visibility = if (abs(offset) < 2.5f) View.VISIBLE else View.GONE
        }
    }

    // Leaves the carousel and opens screens[targetIndex] at normal (100%) zoom: the chosen screen
    // grows from 0.5x to full size (sliding to center if it was a neighbour) while the others
    // fade out and the bars fade back in. The actual screen switch happens when it finishes.
    private fun exitOverview(targetIndex: Int) {
        if (!overviewActive || overviewExiting) return
        // Settle an unfinished enter animation (its end listener runs synchronously) so we start
        // from the final carousel layout.
        overviewTransitionAnimator?.cancel()
        overviewSnapAnimator?.cancel()
        overviewSnapAnimator = null
        overviewVelocity?.recycle()
        overviewVelocity = null

        overviewExiting = true
        overviewTransitioning = true
        overviewActive = false // lets showTopBar()/showBottomNavBar() run again

        val page = screenPages[targetIndex.coerceIn(0, screenPages.lastIndex)]
        val targetCanvas = screenCanvases[page.id] ?: canvas
        val others = screenCanvases.values.filter { it !== targetCanvas }
        val startTx = targetCanvas.translationX
        val minZoom = targetCanvas.minZoom

        showTopBar()
        elementsSidebar.setSuppressed(false)
        if (overviewBottomNavWasVisible) showBottomNavBar()

        overviewTransitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OVERVIEW_TRANSITION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                targetCanvas.setOverviewZoom(minZoom + (1f - minZoom) * t)
                targetCanvas.translationX = startTx * (1f - t)
                for (c in others) c.alpha = 1f - t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishExitOverview(page, targetCanvas)
            })
            start()
        }
    }

    private fun finishExitOverview(page: ScreenPage, targetCanvas: SketchCanvasView) {
        for (c in screenCanvases.values) {
            c.alpha = 1f
            c.translationX = 0f
            c.exitOverview()
            c.visibility = if (c === targetCanvas) View.VISIBLE else View.GONE
        }
        selectScreenPage(page)
        overviewBackPressedCallback.isEnabled = false
        overviewTransitionAnimator = null
        overviewExiting = false
        overviewTransitioning = false
        // If a finger is still down from the gesture that closed the carousel, ignore the rest of it.
        overviewSwallowUntilDown = true
    }

    private fun animateOverviewTo(target: Float) {
        overviewSnapAnimator?.cancel()
        overviewSnapAnimator = ValueAnimator.ofFloat(overviewPos, target).apply {
            duration = OVERVIEW_SNAP_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                overviewPos = it.animatedValue as Float
                layoutOverview()
            }
            start()
        }
    }

    // Snap to a whole screen, moving at most one screen away from where the swipe started.
    private fun settleOverview(velocityX: Float) {
        val projected = overviewPos - velocityX * OVERVIEW_FLING_PROJECTION_S / overviewStridePx
        val target = projected.roundToInt()
            .coerceIn(overviewStartIndex - 1, overviewStartIndex + 1)
            .coerceIn(0, screenPages.lastIndex)
        animateOverviewTo(target.toFloat())
    }

    // Index of the screen whose (0.5x) page contains screen-x, or null if x is in a gap/outside.
    private fun overviewIndexAt(rawX: Float): Int? {
        val loc = IntArray(2)
        canvasArea.getLocationOnScreen(loc)
        val x = rawX - loc[0]
        val stride = overviewStridePx
        val halfPage = canvasArea.width * canvas.minZoom / 2f
        val center = canvasArea.width / 2f
        for (i in screenPages.indices) {
            val cx = center + (i - overviewPos) * stride
            if (x >= cx - halfPage && x <= cx + halfPage) return i
        }
        return null
    }

    private fun handleOverviewTouch(ev: MotionEvent) {
        overviewScaleDetector.onTouchEvent(ev)
        if (!overviewActive) return // the pinch-out just closed the carousel

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                overviewSnapAnimator?.cancel()
                overviewVelocity?.recycle()
                overviewVelocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                overviewDownX = ev.x
                overviewLastX = ev.x
                overviewDragging = false
                overviewMultiTouch = false
                overviewStartIndex = overviewPos.roundToInt()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                overviewMultiTouch = true
                overviewDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                overviewVelocity?.addMovement(ev)
                if (overviewMultiTouch || ev.pointerCount > 1) {
                    overviewMultiTouch = true
                    return
                }
                if (!overviewDragging && abs(ev.x - overviewDownX) > overviewTouchSlop) {
                    overviewDragging = true
                    overviewLastX = ev.x
                }
                if (overviewDragging) {
                    val dx = ev.x - overviewLastX
                    overviewLastX = ev.x
                    overviewPos = (overviewPos - dx / overviewStridePx)
                        .coerceIn(0f, screenPages.lastIndex.toFloat())
                    layoutOverview()
                }
            }
            MotionEvent.ACTION_UP -> {
                overviewVelocity?.addMovement(ev)
                when {
                    overviewMultiTouch -> settleOverview(0f)
                    overviewDragging -> {
                        overviewVelocity?.computeCurrentVelocity(1000)
                        settleOverview(overviewVelocity?.xVelocity ?: 0f)
                    }
                    else -> {
                        val hit = overviewIndexAt(ev.rawX)
                        if (hit != null) exitOverview(hit) else animateOverviewTo(overviewPos.roundToInt().toFloat())
                    }
                }
                overviewVelocity?.recycle()
                overviewVelocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                settleOverview(0f)
                overviewVelocity?.recycle()
                overviewVelocity = null
            }
        }
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

    private fun addPart(kind: PartKind, x: Float, y: Float, label: String = ""): SketchPart =
        addPartTo(canvas, kind, x, y, label)

    // Lower-level version of addPart() that takes an explicit target canvas (and optionally
    // explicit sizing) instead of always acting on the active `canvas`. Used by
    // applyStarterTemplate() to place parts on a screen's canvas before it becomes active/laid
    // out, when canvasWidth/pageHeight can't yet be read off the target itself.
    private fun addPartTo(
        target: SketchCanvasView,
        kind: PartKind,
        x: Float,
        y: Float,
        label: String = "",
        canvasWidth: Int = target.width,
        pageHeight: Float = target.pageHeight,
    ): SketchPart {
        val density = resources.displayMetrics.density
        val fullWidth = kind == PartKind.TOP_APP_BAR || kind == PartKind.NAV_BAR
        val w = if (fullWidth) canvasWidth.toFloat() else kind.defaultW * density
        val h = kind.defaultH * density
        val px = if (fullWidth) 0f else (x - w / 2f).coerceIn(0f, (canvasWidth - w).coerceAtLeast(0f))
        val py = when (kind) {
            PartKind.TOP_APP_BAR -> 0f
            PartKind.NAV_BAR -> (pageHeight - h).coerceAtLeast(0f)
            else -> (y - h / 2f).coerceIn(0f, (pageHeight - h).coerceAtLeast(0f))
        }
        val part = SketchPart(nextId++, kind, px, py, w, h, label, fontSize = 13f * density)
        target.addPart(part)
        return part
    }



    // Bundles every ScreenPage's own parts + canvas size (not just the currently active screen)
    // for PromptGenerator/CodeGenerator, which now describe/scaffold the whole multi-screen app -
    // Splash/Onboarding/Home navigation flow included - rather than only whatever screen happens
    // to be on top when the user taps Generate/Export.
    private fun buildScreenExports(): List<ScreenExport> {
        val fallbackWidth = canvas.width
        val fallbackHeight = canvas.pageHeight.roundToInt()
        return screenPages.map { page ->
            val pageCanvas = screenCanvases[page.id] ?: canvas
            val w = pageCanvas.width.takeIf { it > 0 } ?: fallbackWidth
            val h = pageCanvas.pageHeight.takeIf { it > 0f }?.roundToInt() ?: fallbackHeight
            ScreenExport(
                type = page.type,
                name = page.name,
                parts = pageCanvas.parts.filterNot { it.hidden },
                canvasWidthPx = w,
                canvasHeightPx = h,
            )
        }
    }

    private fun generatePrompt() {
        val density = resources.displayMetrics.density
        val prompt = PromptGenerator.build(buildScreenExports(), density)
        showPromptDialog(this, prompt)
    }


    private fun exportProject() {
        val density = resources.displayMetrics.density
        val zip = CodeGenerator.generateProjectZip(
            context = this,
            screens = buildScreenExports(),
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