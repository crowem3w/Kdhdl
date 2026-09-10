package org.example.test

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlin.math.roundToInt













class SketchActivity : AppCompatActivity() {

    private lateinit var canvas: SketchCanvasView
    private lateinit var emptyState: View

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

    private var nextId = 1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sketch)

        canvas = findViewById(R.id.sketchCanvas)
        emptyState = findViewById(R.id.emptyStateContainer)

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

            override fun onPartsChanged() = updateEmptyState()
        }

        setupTopBar()
        setupZoomControls()
        setupTabs()
        setupQuickActions()
        setupAnimationRow()

        emptyState.setOnClickListener {
            openPartPicker(
                title = "Add to sketch",
                kinds = PartKind.values().toList(),
                x = canvas.width / 2f,
                y = canvas.height / 2f,
            )
        }

        updateEmptyState()
        updateProperties(null)
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

    

    private fun setupZoomControls() {
        findViewById<View>(R.id.btnZoomIn).setOnClickListener { notAvailableYet("Zoom") }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener { notAvailableYet("Zoom") }
        findViewById<View>(R.id.btnLocate).setOnClickListener { notAvailableYet("Recenter") }
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
            openPartPickerFromTab(tabMedia, "Media", listOf(PartKind.IMAGE))
        }
        tabComponents.setOnClickListener {
            setTabActive(tabComponents)
            showComponentsPanel(
                context = this,
                onDismiss = { setTabActive(tabSelect) },
            )
        }
        tabSelect.setOnClickListener { setTabActive(tabSelect) }
    }

    private fun openPartPickerFromTab(tab: LinearLayout, title: String, kinds: List<PartKind>) {
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

    

    private fun updateEmptyState() {
        emptyState.visibility = if (canvas.parts.isEmpty()) View.VISIBLE else View.GONE
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