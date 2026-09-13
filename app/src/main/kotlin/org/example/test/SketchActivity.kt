package org.example.test

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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

    // Bottom navigation bar - a pill-shaped, floating LinearLayout (bg_bottom_nav_pill gives it
    // a fully-rounded capsule shape plus a 3mm extruded edge, like a physical 3D sheet), not a
    // BottomSheetBehavior sheet. Visible by default when SketchActivity opens; toggled
    // hidden/visible by tapping empty canvas (see onTapEmptySpace below). Never drags or
    // collapses - it's either fully shown or fully hidden.
    private lateinit var bottomNavBar: LinearLayout

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

        // How far the selected tab's chip (bg_tab_chip.xml) rises above the nav bar's surface,
        // and how long that rise (plus the matching socket/dent fading in beneath it) takes.
        private const val TAB_CHIP_LIFT_DP = 5f
        private const val TAB_CHIP_ANIM_MS = 220L
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

        bottomNavBar = findViewById(R.id.bottomNavBar)
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

        // elementsPanel is a sibling sheet, not a child of bottomNavBar, so it doesn't
        // automatically stop above it - keep its bottom margin matched to the nav bar's total
        // footprint (its measured height *plus* the floating gap beneath the pill; 0 while
        // hidden) so the sheet's content never slides underneath the bar - or the gap below it -
        // when it's shown, and can use the full height when it's hidden.
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
        }
    }

    // Shows bottomNavBar by sliding up into place while fading in, easing out (fast start,
    // gentle settle). Visible by default when SketchActivity opens; this is also the
    // toggled-on state after tapping empty canvas while it's hidden. Slides/fades in place -
    // doesn't affect canvas layout either way.
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

    // Hides bottomNavBar by sliding down out of view while fading out, easing in (gentle start,
    // fast finish). Toggled by tapping empty canvas while it's shown. Disappears in place -
    // the canvas underneath doesn't expand to fill the space.
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

    // Toggles bottomNavBar between shown and hidden. The single gesture entry point: a tap on
    // empty canvas (see onTapEmptySpace below).
    private fun toggleBottomNavBar() {
        if (bottomNavBar.visibility == View.VISIBLE) hideBottomNavBar() else showBottomNavBar()
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
        // No animation on first layout - the Select tab is simply active from the start, it
        // didn't just get tapped.
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


    private fun setTabActive(active: LinearLayout, animate: Boolean = true) {
        for (tab in allTabs) setTabVisualState(tab, tab === active, animate)
    }

    // Each tab is: LinearLayout (tabSelect etc, the SketchTabColumn) -> FrameLayout (the
    // SketchTabSlot) -> [View socket (SketchTabSocket, index 0), LinearLayout pill
    // (SketchTabPill, index 1)]. socket and pill are same-sized siblings, not parent/child, so
    // that animating the socket's alpha never fades the icon/label riding on the pill above it.
    //
    // Selecting a tab plays two animations in lockstep: the pill rises off the bar
    // (translationY) at the same time bg_tab_chip.xml is applied to it, while bg_tab_socket.xml
    // fades in on the socket sitting right where the pill used to rest flush. Together they read
    // as a small rounded-square tile lifting up and out of a dent pressed into the sheet -
    // rather than the sheet just changing colour under a flat icon.
    private fun setTabVisualState(tab: LinearLayout, active: Boolean, animate: Boolean = true) {
        val slot = tab.getChildAt(0) as FrameLayout
        val socket = slot.getChildAt(0)
        val pill = slot.getChildAt(1) as LinearLayout

        val color = if (active) Color.WHITE else Color.parseColor("#9A9AA5")
        when (val icon = pill.getChildAt(0)) {
            is ImageView -> icon.setColorFilter(color)
            is TextView -> icon.setTextColor(color)
        }
        (pill.getChildAt(1) as TextView).setTextColor(color)

        pill.animate().cancel()
        socket.animate().cancel()

        val liftPx = TAB_CHIP_LIFT_DP * resources.displayMetrics.density

        if (!animate) {
            pill.translationY = if (active) -liftPx else 0f
            pill.setBackgroundResource(if (active) R.drawable.bg_tab_chip else 0)
            socket.alpha = if (active) 1f else 0f
            return
        }

        if (active) {
            // The chip needs its raised-tile background in place *before* it starts rising,
            // otherwise there's nothing to lift - it'd just be a flat icon sliding upward.
            pill.setBackgroundResource(R.drawable.bg_tab_chip)
            pill.translationY = 0f
            pill.animate()
                .translationY(-liftPx)
                .setDuration(TAB_CHIP_ANIM_MS)
                .setInterpolator(OvershootInterpolator(1.5f))
                .start()
            socket.animate()
                .alpha(1f)
                .setDuration(TAB_CHIP_ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            pill.animate()
                .translationY(0f)
                .setDuration(TAB_CHIP_ANIM_MS)
                .setInterpolator(AccelerateInterpolator())
                // Only strip the chip background back off once it has settled flush again -
                // removing it mid-flight would make the tile vanish before it lands.
                .withEndAction { pill.setBackgroundResource(0) }
                .start()
            socket.animate()
                .alpha(0f)
                .setDuration(TAB_CHIP_ANIM_MS)
                .setInterpolator(AccelerateInterpolator())
                .start()
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