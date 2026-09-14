package org.example.test

import android.animation.ArgbEvaluator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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

    
    
    
    
    
    
    private lateinit var selectionActionsPanel: LinearLayout
    private lateinit var actionGroupToggle: LinearLayout
    private lateinit var actionGroupToggleLabel: TextView
    private lateinit var actionDuplicateSel: LinearLayout
    private lateinit var actionMoveSel: LinearLayout
    private lateinit var actionLockToggleSel: LinearLayout
    private lateinit var actionHideToggleSel: LinearLayout
    private lateinit var actionDeleteSel: LinearLayout

    
    
    
    
    
    private lateinit var bottomNavBar: BottomNavSheetBar

    
    
    
    
    
    private data class TabPillVisual(
        val pill: LinearLayout,
        val fill: GradientDrawable,
        val icon: ImageView,
        val label: TextView,
    )

    private lateinit var pillVisuals: List<TabPillVisual>
    private val pillBaseElevationPx by lazy { dp(3f) }
    private val pillRaisedElevationPx by lazy { dp(16f) }
    
    
    
    
    private val navBarTopPaddingPx by lazy { bottomNavBar.paddingTop.toFloat() }
    private val activeAccentColor = Color.parseColor("#3D7EFF")
    private val inactiveTextColor = Color.parseColor("#9A9AA5")
    private val activeTextColor = Color.WHITE

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    
    
    private lateinit var elementsPanel: FrameLayout
    private lateinit var elementsPanelBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var componentsContentContainer: FrameLayout
    private var componentsContentBuilt = false
    private var showingComponents = false

    
    
    
    
    
    private var bottomNavBarBaseMarginBottom = 0

    
    
    
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
        allTabs = listOf(tabShapes, tabText, tabSelect, tabMedia, tabComponents)

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

        ViewCompat.setOnApplyWindowInsetsListener(elementsPanel) { _, insets ->
            statusBarInsetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            applyElementsPanelTopPadding(elementsPanelBehavior.state == BottomSheetBehavior.STATE_EXPANDED)
            insets
        }

        
        
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

            
            
            override fun onSlide(sheetView: View, slideOffset: Float) {
                val progress = slideOffset.coerceIn(0f, 1f)
                applyElementsPanelTopPadding(progressToStatusBarInset = progress)
            }
        })

        
        elementsPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    
    
    
    
    private fun applyElementsPanelTopPadding(expanded: Boolean? = null, progressToStatusBarInset: Float? = null) {
        val extra = when {
            progressToStatusBarInset != null -> (statusBarInsetTop * progressToStatusBarInset).roundToInt()
            expanded == true -> statusBarInsetTop
            else -> 0
        }
        elementsPanel.setPadding(elementsPanel.paddingLeft, extra, elementsPanel.paddingRight, elementsPanel.paddingBottom)
    }

    
    
    
    private fun openElementsPanel() {
        setTabActive(tabComponents)
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
        
        
        elementsPanelBehavior.state = BottomSheetBehavior.STATE_HIDDEN
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
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(11f)
                setColor(activeAccentColor)
                alpha = 0
            }
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
            
            
            if (showingComponents) closeComponentsContent()
            setTabActive(tabSelect)
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
                
            },
            onCancel = { },
        )
    }

    
    
    private fun openPartPickerFromTab(tab: LinearLayout, title: String, kinds: List<PartKind>) {
        if (showingComponents) closeComponentsContent()
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
            val color = evaluator.evaluate(clamped, inactiveTextColor, activeTextColor) as Int
            visual.icon.setColorFilter(color)
            visual.label.setTextColor(color)
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