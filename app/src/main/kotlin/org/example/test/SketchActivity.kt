package org.example.test

import android.animation.ArgbEvaluator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var tabElements: LinearLayout
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

    
    
    
    
    private lateinit var screensPanel: FrameLayout
    private lateinit var screensPanelBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var screensContentContainer: FrameLayout
    private var screensContentBuilt = false
    private var showingScreens = false

    
    
    
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
            } else if (screensPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                closeScreensPanel()
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
            toggleBottomNavBar()
        }

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

        override fun onSelectionChanged(part: SketchPart?) {
            
            if (editingNamePart != null && part !== editingNamePart) commitNameTagEdit()
        }

        // Live-updates a Blank screen's thumbnail label (Blank -> Normal, see hasScreenContent)
        // the moment content is added to or removed from its canvas, not just on screen switch.
        override fun onPartsChanged() = refreshScreenThumbnails()

        override fun onNameTagTapped(part: SketchPart) = startNameTagEdit(part)

        override fun onMultiSelectionFinalized(parts: List<SketchPart>) = showSelectionActionsPanel()

        override fun onMultiSelectionCleared() = hideSelectionActionsPanel()
    }

    companion object {
        private const val TOP_BAR_AUTO_HIDE_DELAY_MS = 5_000L
        private const val TOP_BAR_FADE_MS = 150L
        private const val BOTTOM_NAV_BAR_ANIM_MS = 250L

        // elementsPanel's minimum/default height when no real keyboard height has been measured
        // yet (i.e. the keyboard isn't currently showing). Matches screensPanel's peekHeight.
        private const val ELEMENTS_PANEL_FALLBACK_PEEK_HEIGHT_DP = 280
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
        tabElements = findViewById(R.id.tabElements)
        allTabs = listOf(tabScreens, tabText, tabSelect, tabMedia, tabElements)

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
        elementsPanelBehavior = BottomSheetBehavior.from(elementsPanel)
        screensPanel = findViewById(R.id.screensPanel)
        screensContentContainer = findViewById(R.id.screensContentContainer)
        screensPanelBehavior = BottomSheetBehavior.from(screensPanel)
        screenThumbnailsRow = findViewById(R.id.screenThumbnailsRow)
        onBackPressedDispatcher.addCallback(this, panelBackPressedCallback)

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
        setupScreensPanel()
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

        
        
        
        
        
        bottomNavBar.viewTreeObserver.addOnGlobalLayoutListener {
            val navBarHeight = if (bottomNavBar.visibility == View.VISIBLE) {
                bottomNavBar.height + bottomNavBar.marginBottom
            } else {
                0
            }
            val lp = elementsPanel.layoutParams as? CoordinatorLayout.LayoutParams
            if (lp != null && lp.bottomMargin != navBarHeight) {
                lp.bottomMargin = navBarHeight
                elementsPanel.layoutParams = lp
            }
            val screensLp = screensPanel.layoutParams as? CoordinatorLayout.LayoutParams
            if (screensLp != null && screensLp.bottomMargin != navBarHeight) {
                screensLp.bottomMargin = navBarHeight
                screensPanel.layoutParams = screensLp
            }
        }
    }

    
    
    
    
    private fun showBottomNavBar() {
        if (bottomNavBar.visibility == View.VISIBLE && bottomNavBar.alpha >= 1f && bottomNavBar.translationY == 0f) return
        bottomNavBar.animate().cancel()
        bottomNavBar.alpha = 0f
        bottomNavBar.translationY = bottomNavBar.height.toFloat()
        bottomNavBar.visibility = View.VISIBLE
        bottomNavBar.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(BOTTOM_NAV_BAR_ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    
    
    
    private fun hideBottomNavBar() {
        if (bottomNavBar.visibility != View.VISIBLE) return
        bottomNavBar.animate().cancel()
        bottomNavBar.animate()
            .alpha(0f)
            .translationY(bottomNavBar.height.toFloat())
            .setDuration(BOTTOM_NAV_BAR_ANIM_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                bottomNavBar.visibility = View.GONE
                bottomNavBar.translationY = 0f
            }
            .start()
    }

    
    
    private fun toggleBottomNavBar() {
        if (bottomNavBar.visibility == View.VISIBLE) hideBottomNavBar() else showBottomNavBar()
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
        val buttonPanelRootTopPaddingPx = (1 * resources.displayMetrics.density).roundToInt()
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
        setTabActive(tabElements)
        showComponentsContent()
    }

    
    
    private fun closeElementsPanel() {
        if (elementsPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            elementsPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    
    
    private fun resetPanelContent() {
        showingComponents = false
        
        
    }



    private fun showComponentsContent(expanded: Boolean = false) {
        if (!componentsContentBuilt) {
            componentsContentContainer.addView(
                buildComponentsContent(
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
                    // selectedVariant), at the active screen's canvas center, then closes the
                    // Elements panel - same pattern as the Screens panel's onPick.
                    onAddButtonToCanvasRequested = { style ->
                        addPart(PartKind.BUTTON, canvas.width / 2f, canvas.pageHeight / 2f, label = "Button")
                            .buttonStyle = style
                        canvas.invalidate()
                        closeElementsPanel()
                    },
                )
            )
            componentsContentBuilt = true
        }
        showingComponents = true
        elementsPanelBehavior.state =
            if (expanded) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_COLLAPSED
    }



    private fun closeComponentsContent() {
        showingComponents = false
        
        
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
        screenThumbnailsRow.visibility = View.GONE
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
        tabElements.setOnClickListener { selectComponentsTab(expanded = true) }
        tabSelect.setOnClickListener { selectSelectTab() }

        
        
        
        
        bottomNavBar.onTabDragMove = { index ->
            allTabs.getOrNull(index)?.let { setTabActive(it) }
        }

        
        
        bottomNavBar.onTabDragSelect = { index ->
            when (allTabs.getOrNull(index)) {
                tabScreens -> selectShapesTab()
                tabText -> selectTextTab()
                tabMedia -> selectMediaTab()
                tabElements -> selectComponentsTab()
                tabSelect -> selectSelectTab()
                else -> {}
            }
        }
    }

    private fun selectShapesTab() {
        if (showingComponents) closeComponentsContent()
        setTabActive(tabScreens)
        openScreensPanel()
    }

    private fun selectTextTab() {
        openTextInputModal()
    }

    private fun selectMediaTab() {
        openPartPickerFromTab(tabMedia, "Upload", listOf(PartKind.IMAGE))
    }

    private fun selectComponentsTab(expanded: Boolean = false) {
        if (showingScreens) closeScreensPanel()
        setTabActive(tabElements)
        showComponentsContent(expanded)
    }

    private fun selectSelectTab() {
        
        
        if (showingComponents) closeComponentsContent()
        if (showingScreens) closeScreensPanel()
        setTabActive(tabSelect)
    }



    private fun openTextInputModal() {
        if (showingComponents) closeComponentsContent()
        if (showingScreens) closeScreensPanel()
        setTabActive(tabText)

        showTextInputDialog(
            context = this,
            onConfirm = { text ->
                if (text.isNotBlank()) {
                    addPart(PartKind.TEXT, canvas.width / 2f, canvas.pageHeight / 2f, label = text)
                }
                
            },
            onCancel = { },
        )
    }

    
    
    private fun openPartPickerFromTab(tab: LinearLayout, title: String, kinds: List<PartKind>) {
        if (showingComponents) closeComponentsContent()
        if (showingScreens) closeScreensPanel()
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
        
        
        if (editingNamePart != null && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val rect = android.graphics.Rect()
            nameTagEditor.getGlobalVisibleRect(rect)
            if (!rect.contains(ev.rawX.roundToInt(), ev.rawY.roundToInt())) {
                commitNameTagEdit()
            }
        }
        return super.dispatchTouchEvent(ev)
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
