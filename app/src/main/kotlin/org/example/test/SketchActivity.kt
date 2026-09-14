package org.example.test

import android.animation.ArgbEvaluator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
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





    private lateinit var topBar: LinearLayout
    private val topBarHideHandler = Handler(Looper.getMainLooper())
    private val hideTopBarRunnable = Runnable { hideTopBar() }

    private lateinit var tabSelect: LinearLayout
    private lateinit var tabShapes: LinearLayout
    private lateinit var tabText: LinearLayout
    private lateinit var tabMedia: LinearLayout
    private lateinit var tabComponents: LinearLayout
    private lateinit var allTabs: List<LinearLayout>

    // Temporary panel shown after a long-press + drag marquee selection is released on the
    // canvas (see SketchCanvasView.Listener#onMultiSelectionFinalized). Entirely separate from
    // bottomNavBar/elementsPanel: it's a small floating sidebar of icon buttons docked to the
    // screen edge
    // rather than a draggable sheet, and it auto-dismisses once an action is picked (or the
    // selection is otherwise cleared).
    private lateinit var selectionActionsPanel: LinearLayout
    private lateinit var actionGroupToggle: LinearLayout
    private lateinit var actionGroupToggleLabel: TextView
    private lateinit var actionDuplicateSel: LinearLayout
    private lateinit var actionMoveSel: LinearLayout
    private lateinit var actionLockToggleSel: LinearLayout
    private lateinit var actionHideToggleSel: LinearLayout
    private lateinit var actionDeleteSel: LinearLayout

    // Bottom navigation bar - a pill-shaped, floating "sheet" (bg drawn by bottomNavBar itself,
    // a fully-rounded capsule shape plus a 3mm extruded edge, like a physical 3D sheet), not a
    // BottomSheetBehavior sheet. Visible by default when SketchActivity opens; toggled
    // hidden/visible by tapping empty canvas (see onTapEmptySpace below). Never drags or
    // collapses - it's either fully shown or fully hidden.
    //
    // bottomNavBarContainer is the actual CoordinatorLayout child (owns the real margins/
    // insets and is what showBottomNavBar/hideBottomNavBar animate); bottomNavBar is just the
    // decorative sheet painted underneath; bottomNavTabs is the sibling row that holds the
    // real, tappable tab columns. Tabs are deliberately NOT children of bottomNavBar - see the
    // class doc on BottomNavSheetBar for why (the elevated selected frame must be free to float
    // above the sheet without being clipped by the sheet's own bounds).
    private lateinit var bottomNavBarContainer: FrameLayout
    private lateinit var bottomNavBar: BottomNavSheetBar
    private lateinit var bottomNavTabs: LinearLayout

    // Per-tab visual state for the elevated-frame illusion: each pill keeps a persistent,
    // alpha-animatable accent-color background (rather than a hard drawable swap) plus its own
    // elevation, so a selection change can fade/raise the new pill and fade/lower the old one
    // in the same animation frame that bottomNavBar's dimple is travelling between them - see
    // applySelectionProgress().
    private data class TabPillVisual(
        val pill: LinearLayout,
        val fill: GradientDrawable,
        val icon: ImageView,
        val label: TextView,
    )

    private lateinit var pillVisuals: List<TabPillVisual>
    private val pillBaseElevationPx by lazy { dp(3f) }
    // Raised further than before (was 12dp) now that the frame lives on a sibling layer free of
    // the sheet's own elevation-clip - a bigger elevation both lifts the frame clearly clear of
    // the sheet's surface and gives it a larger, softer native shadow blur.
    private val pillRaisedElevationPx by lazy { dp(20f) }
    private val pillRiseTranslationPx by lazy { -dp(20f) }
    private val activeAccentColor = Color.parseColor("#3D7EFF")
    private val inactiveTextColor = Color.parseColor("#9A9AA5")
    private val activeTextColor = Color.WHITE
    // Dark neutral rather than pure black, for a softer-looking drop shadow under the floating
    // selected frame.
    private val pillShadowColor = Color.parseColor("#1A1B24")

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // Separate sheet that opens above bottomNavBar to show the Components ("Elements") browser.
    // Independent BottomSheetBehavior from anything the nav bar does.
    private lateinit var elementsPanel: FrameLayout
    private lateinit var elementsPanelBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var componentsContentContainer: FrameLayout
    private var componentsContentBuilt = false
    private var showingComponents = false

    // bottomNavBar's own XML bottom margin (20dp - the floating gap beneath the pill), captured
    // once, plus whatever the bottom system-gesture inset turns out to be, so the floating pill
    // lifts clear of the system's own gesture/navigation bar instead of sinking behind it.
    // (paddingBottom is left alone - it's fixed to the pill's 3mm extruded-edge thickness so the
    // tab row lines up with the sheet's face, not with the system inset.)
    private var bottomNavBarBaseMarginBottom = 0

    // elementsPanel has no top padding of its own. Since the sheet only ever toggles between
    // hidden and fully expanded (skipCollapsed), when expanded it reaches the physical top of
    // the screen - so we pad its content that much to keep it from drawing behind the status bar.
    private var statusBarInsetTop = 0




    private val panelBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (selectionActionsPanel.visibility == View.VISIBLE) {
                dismissSelectionActionsPanel(clearSelection = true)
            } else {
                closeElementsPanel()
            }
        }
    }

    private var nextId = 1L

    companion object {
        private const val TOP_BAR_AUTO_HIDE_DELAY_MS = 5_000L
        private const val TOP_BAR_FADE_MS = 150L
        private const val BOTTOM_NAV_BAR_ANIM_MS = 250L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The sketch canvas is a fixed dark workspace regardless of the
        // app's Light/Dark mode setting (Settings tab) — force the system
        // bars to match rather than following whatever mode is active.
        window.statusBarColor = 0xFF121212.toInt()
        window.navigationBarColor = 0xFF121212.toInt()
        setContentView(R.layout.activity_sketch)

        canvas = findViewById(R.id.sketchCanvas)
        topBar = findViewById(R.id.topBar)

        tabSelect = findViewById(R.id.tabSelect)
        tabShapes = findViewById(R.id.tabShapes)
        tabText = findViewById(R.id.tabText)
        tabMedia = findViewById(R.id.tabMedia)
        tabComponents = findViewById(R.id.tabComponents)
        allTabs = listOf(tabSelect, tabShapes, tabText, tabMedia, tabComponents)

        selectionActionsPanel = findViewById(R.id.selectionActionsPanel)
        actionGroupToggle = findViewById(R.id.actionGroupToggle)
        actionGroupToggleLabel = findViewById(R.id.actionGroupToggleLabel)
        actionDuplicateSel = findViewById(R.id.actionDuplicateSel)
        actionMoveSel = findViewById(R.id.actionMoveSel)
        actionLockToggleSel = findViewById(R.id.actionLockToggleSel)
        actionHideToggleSel = findViewById(R.id.actionHideToggleSel)
        actionDeleteSel = findViewById(R.id.actionDeleteSel)

        bottomNavBarContainer = findViewById(R.id.bottomNavBarContainer)
        bottomNavBar = findViewById(R.id.bottomNavBar)
        bottomNavTabs = findViewById(R.id.bottomNavTabs)
        elementsPanel = findViewById(R.id.elementsPanel)
        componentsContentContainer = findViewById(R.id.componentsContentContainer)
        elementsPanelBehavior = BottomSheetBehavior.from(elementsPanel)
        onBackPressedDispatcher.addCallback(this, panelBackPressedCallback)

        canvas.listener = object : SketchCanvasView.Listener {
            override fun onLongPressEmptySpace() = openElementsPanel()

            override fun onDoubleTapEmptySpace() = openElementsPanel()

            override fun onTapEmptySpace() {
                closeElementsPanel()
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

            override fun onSelectionChanged(part: SketchPart?) = Unit

            override fun onPartsChanged() = Unit

            override fun onMultiSelectionFinalized(parts: List<SketchPart>) = showSelectionActionsPanel()

            override fun onMultiSelectionCleared() = hideSelectionActionsPanel()
        }

        setupTopBar()
        setupBottomNavBar()
        setupElementsPanel()
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







    // Fixed bottom navigation bar (Select/Pages/Text/Upload/Elements), styled as a pill-shaped
    // floating 3D sheet. Visible on launch; not a BottomSheetBehavior sheet, so there's no
    // drag/collapse state to manage, just window-inset margin (so the floating pill clears the
    // system nav bar instead of the sheet's own padding, which is reserved for the pill's
    // extruded-edge thickness), keeping elementsPanel docked above it, and the show/hide toggle
    // below.
    private fun setupBottomNavBar() {
        // bottomNavBarContainer (not bottomNavBar) is the real CoordinatorLayout child that
        // owns the 20dp floating-gap margin and needs to grow for the nav-bar inset.
        val navBarLp = bottomNavBarContainer.layoutParams as? CoordinatorLayout.LayoutParams
        bottomNavBarBaseMarginBottom = navBarLp?.bottomMargin ?: bottomNavBarContainer.marginBottom
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavBarContainer) { _, insets ->
            val navInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val lp = bottomNavBarContainer.layoutParams as? CoordinatorLayout.LayoutParams
            if (lp != null) {
                lp.bottomMargin = bottomNavBarBaseMarginBottom + navInset
                bottomNavBarContainer.layoutParams = lp
            }
            insets
        }

        // elementsPanel is a sibling sheet, not a child of bottomNavBarContainer, so it doesn't
        // automatically stop above it - keep its bottom margin matched to the nav bar's total
        // footprint (its measured height *plus* the floating gap beneath the pill; 0 while
        // hidden) so the sheet's content never slides underneath the bar - or the gap below it -
        // when it's shown, and can use the full height when it's hidden.
        bottomNavBarContainer.viewTreeObserver.addOnGlobalLayoutListener {
            val navBarHeight = if (bottomNavBarContainer.visibility == View.VISIBLE) {
                bottomNavBarContainer.height + bottomNavBarContainer.marginBottom
            } else {
                0
            }
            val lp = elementsPanel.layoutParams as? CoordinatorLayout.LayoutParams
            if (lp != null && lp.bottomMargin != navBarHeight) {
                lp.bottomMargin = navBarHeight
                elementsPanel.layoutParams = lp
            }
        }

        // bottomNavBar (the decorative sheet) no longer has the tab columns as children, so it
        // can't read their positions off itself any more - keep its dimple synced to whichever
        // tab is active whenever the sibling bottomNavTabs row re-lays-out (rotation, first
        // layout, etc.); a live selection animation owns the dimple's position on its own.
        bottomNavTabs.viewTreeObserver.addOnGlobalLayoutListener {
            bottomNavBar.syncRestingGeometry()
        }
    }

    // Shows the nav bar by sliding up into place while fading in, easing out (fast start,
    // gentle settle). Visible by default when SketchActivity opens; this is also the
    // toggled-on state after tapping empty canvas while it's hidden. Slides/fades in place -
    // doesn't affect canvas layout either way. Animates bottomNavBarContainer as a whole so the
    // sheet and its tab row move together.
    private fun showBottomNavBar() {
        if (bottomNavBarContainer.visibility == View.VISIBLE &&
            bottomNavBarContainer.alpha >= 1f &&
            bottomNavBarContainer.translationY == 0f
        ) return
        bottomNavBarContainer.animate().cancel()
        bottomNavBarContainer.alpha = 0f
        bottomNavBarContainer.translationY = bottomNavBarContainer.height.toFloat()
        bottomNavBarContainer.visibility = View.VISIBLE
        bottomNavBarContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(BOTTOM_NAV_BAR_ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // Hides the nav bar by sliding down out of view while fading out, easing in (gentle start,
    // fast finish). Toggled by tapping empty canvas while it's shown. Disappears in place -
    // the canvas underneath doesn't expand to fill the space.
    private fun hideBottomNavBar() {
        if (bottomNavBarContainer.visibility != View.VISIBLE) return
        bottomNavBarContainer.animate().cancel()
        bottomNavBarContainer.animate()
            .alpha(0f)
            .translationY(bottomNavBarContainer.height.toFloat())
            .setDuration(BOTTOM_NAV_BAR_ANIM_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                bottomNavBarContainer.visibility = View.GONE
                bottomNavBarContainer.translationY = 0f
            }
            .start()
    }

    // Toggles the nav bar between shown and hidden. The single gesture entry point: a tap on
    // empty canvas (see onTapEmptySpace below).
    private fun toggleBottomNavBar() {
        if (bottomNavBarContainer.visibility == View.VISIBLE) hideBottomNavBar() else showBottomNavBar()
    }

    // Separate sheet (independent from bottomNavBar) that shows the Components browser. Opened
    // by tapping the Elements tab, a long-press on empty canvas, or a double-tap on empty
    // canvas; closed by the back button/gesture, a swipe-down, dragging its handle, or tapping
    // empty canvas. Skips the collapsed state entirely -
    // it's either hidden or fully expanded, never a partial peek.
    private fun setupElementsPanel() {
        elementsPanelBehavior.isDraggable = true

        ViewCompat.setOnApplyWindowInsetsListener(elementsPanel) { _, insets ->
            statusBarInsetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            applyElementsPanelTopPadding(elementsPanelBehavior.state == BottomSheetBehavior.STATE_EXPANDED)
            insets
        }

        // Keeps the back-press callback and the panel's own content in sync with its state,
        // regardless of whether it was hidden by the back button, a swipe-down, or code.
        elementsPanelBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheetView: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        panelBackPressedCallback.isEnabled = false
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

            // Keeps the extra top padding in sync while the sheet is sliding, so its content
            // eases out from under the status bar instead of snapping.
            override fun onSlide(sheetView: View, slideOffset: Float) {
                val progress = slideOffset.coerceIn(0f, 1f)
                applyElementsPanelTopPadding(progressToStatusBarInset = progress)
            }
        })

        // Hidden by default: the panel only appears once the user triggers "Add to sketch".
        elementsPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    // Adjusts elementsPanel's top padding so its content clears the status bar once expanded
    // (it has no top padding of its own otherwise). `expanded` snaps straight to the fully-open
    // or fully-closed amount; `progressToStatusBarInset` (0..1) interpolates between them while
    // the sheet is sliding. Only one of the two is used per call.
    private fun applyElementsPanelTopPadding(expanded: Boolean? = null, progressToStatusBarInset: Float? = null) {
        val extra = when {
            progressToStatusBarInset != null -> (statusBarInsetTop * progressToStatusBarInset).roundToInt()
            expanded == true -> statusBarInsetTop
            else -> 0
        }
        elementsPanel.setPadding(elementsPanel.paddingLeft, extra, elementsPanel.paddingRight, elementsPanel.paddingBottom)
    }

    // Opens elementsPanel. Entry points are a long-press or a double-tap on empty canvas space
    // (see SketchCanvasView.Listener#onLongPressEmptySpace / #onDoubleTapEmptySpace above), as
    // well as tapping the Elements tab directly.
    private fun openElementsPanel() {
        setTabActive(tabComponents)
        showComponentsContent()
    }

    // Closes elementsPanel. Used by the back button/gesture, a tap on empty canvas, a swipe-down,
    // or dragging the sheet down by its handle (both native to the draggable BottomSheetBehavior).
    private fun closeElementsPanel() {
        if (elementsPanelBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            elementsPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    // Resets the nav bar back to the Select tab so the next time elementsPanel is opened it
    // starts fresh, whichever way it was just closed.
    private fun resetPanelContent() {
        showingComponents = false
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
        elementsPanelBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }



    private fun closeComponentsContent() {
        showingComponents = false
        setTabActive(tabSelect)
        elementsPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }



    private fun setupTabs() {
        // bottomNavTabs (not bottomNavBar) now owns the tab columns - see the class doc on
        // BottomNavSheetBar - but the sheet still needs to read their positions to place its
        // dimple.
        bottomNavBar.tabColumns = allTabs

        pillVisuals = allTabs.map { column ->
            // The pill translates well above its resting position when selected; without this,
            // the column (which otherwise wraps tightly around the pill) would clip that lift.
            column.clipChildren = false
            column.clipToPadding = false
            val pill = column.getChildAt(0) as LinearLayout
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(11f)
                setColor(activeAccentColor)
                alpha = 0
            }
            pill.background = fill
            pill.elevation = pillBaseElevationPx
            pill.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            pill.clipToOutline = false
            // Soft, dark-neutral shadow (rather than the harsher default pure-black) so the
            // raised frame reads as gently floating above the sheet instead of harshly cut out.
            pill.outlineAmbientShadowColor = pillShadowColor
            pill.outlineSpotShadowColor = pillShadowColor
            TabPillVisual(
                pill = pill,
                fill = fill,
                icon = pill.getChildAt(0) as ImageView,
                label = pill.getChildAt(1) as TextView,
            )
        }
        bottomNavBar.onSelectionProgress = { oldIndex, newIndex, t -> applySelectionProgress(oldIndex, newIndex, t) }

        setTabActive(tabSelect, animate = false)

        tabShapes.setOnClickListener {
            openPartPickerFromTab(tabShapes, "Shapes", listOf(PartKind.CARD, PartKind.IMAGE, PartKind.CHIP))
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


    // Selects `active` and tells bottomNavBar to travel its dimple there; the resulting
    // per-frame callback (applySelectionProgress) is what actually moves/colors the pills.
    // `animate = false` is used only for the very first, on-launch selection.
    private fun setTabActive(active: LinearLayout, animate: Boolean = true) {
        val index = allTabs.indexOf(active)
        if (index < 0) return
        bottomNavBar.setActiveTabIndex(index, animate)
    }

    // Driven every frame of bottomNavBar's selection animation (and once, instantly, for the
    // initial state). `t` runs 0->1 with a small spring overshoot for whichever pill is
    // becoming active, and the mirrored 1->0 for whichever pill is losing selection; every
    // other pill just stays fully at rest. Elevation/alpha/color are clamped to valid ranges,
    // but translationY is left unclamped so the frame's rise/descent carries the same gentle
    // overshoot as the sheet's dimple beneath it.
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
            visual.pill.translationY = pillRiseTranslationPx * localT
            visual.pill.elevation = pillBaseElevationPx + (pillRaisedElevationPx - pillBaseElevationPx) * clamped
            val color = evaluator.evaluate(clamped, inactiveTextColor, activeTextColor) as Int
            visual.icon.setColorFilter(color)
            visual.label.setTextColor(color)
        }
    }



    // --- Temporary multi-selection actions panel ----------------------------------------------
    // Opened by SketchCanvasView after a long-press + drag marquee release (onMultiSelectionFinalized)
    // and dismissed either by picking an action below or by the selection being cleared some
    // other way (tapping elsewhere on the canvas, deleting the selection, etc).

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
            // Just dismiss the panel - the multi-selection itself stays active and highlighted,
            // so the user can immediately drag any of the selected parts to move the whole group.
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
            // deleteSelection() already clears the selection and fires onMultiSelectionCleared,
            // which slides the panel back down for us.
            canvas.deleteSelection()
        }
    }

    private fun updateSelectionActionLabels() {
        actionGroupToggleLabel.text = if (canvas.isSelectionGrouped()) "Ungroup" else "Group"
    }

    // Slides the sidebar in from the left edge of the screen.
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

    // Slides the panel back down off-screen. `clearSelection` controls whether the underlying
    // multi-selection on the canvas is dropped too (false for "Move", where it should persist).
    private fun dismissSelectionActionsPanel(clearSelection: Boolean) {
        hideSelectionActionsPanel()
        if (clearSelection) canvas.clearMultiSelection()
    }

    // Purely visual: slides the panel back off-screen to the left and hides it, without
    // touching the canvas selection. Used both by dismissSelectionActionsPanel() above and
    // directly as the onMultiSelectionCleared callback, since in that case the canvas has
    // already cleared its own selection and is just notifying us to close the panel.
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
        // Hidden parts (see the multi-select "Hide" action) are editor-only and shouldn't leak
        // into the generated output.
        val visibleParts = canvas.parts.filterNot { it.hidden }
        val prompt = PromptGenerator.build(visibleParts, canvas.width, canvas.pageHeight.roundToInt(), density)
        showPromptDialog(this, prompt)
    }


    private fun exportProject() {
        val density = resources.displayMetrics.density
        val visibleParts = canvas.parts.filterNot { it.hidden }
        val zip = CodeGenerator.generateProjectZip(
            context = this,
            parts = visibleParts,
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