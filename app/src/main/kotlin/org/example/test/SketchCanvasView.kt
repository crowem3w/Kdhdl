package org.example.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class SketchCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    init {
        
        
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    interface Listener {
        fun onPartLongPressed(part: SketchPart)
        fun onSelectionChanged(part: SketchPart?)
        fun onPartsChanged()
        
        
        fun onMultiSelectionFinalized(parts: List<SketchPart>)
        
        
        fun onMultiSelectionCleared()
        
        
        
        fun onLongPressEmptySpace()
        
        
        
        fun onDoubleTapEmptySpace()
        
        
        
        fun onTapEmptySpace()
        
        
        
        fun onNameTagTapped(part: SketchPart) {}

        // Fired when an existing part is double-tapped (as opposed to onDoubleTapEmptySpace
        // above). Default no-op preserves the prior behavior (zoom reset to 100%, see onDoubleTap
        // below) for any listener/part that doesn't care about this.
        fun onPartDoubleTapped(part: SketchPart): Boolean = false

        // Fired (repeatedly, while the pinch continues) whenever pinch-zoom has been driven all
        // the way down to the minimum zoom level. SketchActivity uses it to switch into the
        // zoomed-out screens carousel (see enterOverview() there). Default no-op.
        fun onMaxZoomOut() {}
    }

    var listener: Listener? = null
    val parts = mutableListOf<SketchPart>()


    val selectedPart: SketchPart? get() = selected

    private val density = context.resources.displayMetrics.density

    
    
    
    
    private val multiSelected = mutableSetOf<SketchPart>()
    val multiSelectedParts: List<SketchPart> get() = multiSelected.toList()
    val isMarqueeActive: Boolean get() = marqueeArmed || marqueeActive

    private var marqueeArmed = false
    private var marqueeActive = false
    private var marqueeAnchorX = 0f
    private var marqueeAnchorY = 0f
    private val marqueeRect = RectF()
    private val marqueeLive = mutableSetOf<SketchPart>()
    private var nextGroupId = 1L

    private var draggingGroup = false
    private var groupDragAnchorX = 0f
    private var groupDragAnchorY = 0f

    private val marqueeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#296750A4")
    }
    private val marqueeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#6750A4")
    }
    private val multiSelectStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#6750A4")
    }
    private val marqueeArmIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#6750A4")
    }
    

    
    
    
    
    
    
    var pageHeight: Float = 0f
        private set

    /** Seeds pageHeight from outside the class for a canvas that hasn't been laid out yet (so it
     *  has no intrinsic height of its own) - used by SketchActivity.applyStarterTemplate() to
     *  size a newly created screen before its first layout pass. No-op once pageHeight is set. */
    fun ensurePageHeight(value: Float) {
        if (pageHeight <= 0f) pageHeight = value
    }

    
    
    val isDraggingPageHandle: Boolean get() = draggingPageHandle
    
    
    
    private val minPageHeight: Float get() = height.toFloat()
    private val maxPageHeight: Float get() = height.toFloat() * 10f
    private val pagePaint = Paint().apply { color = Color.parseColor("#F3F4F6") }
    // Fill for the area OUTSIDE the page (visible when zooming out / panning past the page edges).
    private val outerBackgroundColor = Color.BLACK
    private val pagePath = Path()
    private val pageCornerRadius = 12f * density
    private val pageRadii = FloatArray(8)

    
    
    
    private var draggingPageHandle = false
    private var pageHandleHovered = false
    private var pageDragStartScreenY = 0f
    private var pageDragStartHeight = 0f
    private val pageHandleWidth = 56f * density
    private val pageHandleHeight = 5f * density
    private val pageHandleTouchHalfWidth = 56f * density
    private val pageHandleTouchHalfHeight = 24f * density
    private val pageHandleColorIdle = Color.parseColor("#3A3B47")
    private val pageHandleColorHover = Color.parseColor("#53556A")
    private val pageHandleColorDrag = Color.parseColor("#6750A4")
    private val pageHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageHandleColorIdle
        setShadowLayer(6f * density, 0f, 2f * density, Color.parseColor("#40000000"))
    }
    private val pageHandleRect = RectF()
    // White halo drawn behind the handle so its lower half stays visible over the black outer area.
    private val pageHandleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pageHandleHaloRect = RectF()
    private val pageHandleHaloInset = 1.5f * density
    

    
    
    
    
    
    
    
    
    
    var panOffsetY: Float = 0f
        private set
    val isPanningCanvas: Boolean get() = canvasPanning
    private var panCandidate = false
    private var canvasPanning = false
    private var panDragStartScreenY = 0f
    private var panDragStartOffset = 0f
    private val panTouchSlop = 8f * density
    
    
    private val panHandleBottomMargin = 32f * density

    private fun maxPanOffsetY(): Float {
        val handleBottomContentY = pageHeight + panHandleBottomMargin
        val naturalScreenY = zoomPivotY + (handleBottomContentY - zoomPivotY) * scaleFactor
        return (naturalScreenY - height).coerceAtLeast(0f)
    }

    private fun clampPan() {
        val newPan = panOffsetY.coerceIn(0f, maxPanOffsetY())
        if (newPan != panOffsetY) panOffsetY = newPan
    }
    

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#6750A4")
    }
    
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D1B20")
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    
    
    private val wrapTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D1B20")
    }
    
    
    private val textWrapHorizontalPadding = 6f * density
    private val textWrapVerticalPadding = 4f * density

    // Inset kept clear of a FRAMED button's edges when its label is Start/End/Justify-aligned
    // (see drawFramedButtonLabel() below), so the text never touches the button's rounded border.
    private val buttonLabelHorizontalPadding = 10f * density

    
    
    
    private val selectionFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        setShadowLayer(10f * density, 0f, 3f * density, Color.parseColor("#40000000"))
    }
    private val selectionPad = 10f * density
    private val selectionRadius = 12f * density

    
    
    
    private val handleOffset = 1f * density
    private val handleLineHalfLength = 7f * density
    private val handleCornerRadius = 5f * density
    private val handleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#6750A4")
    }
    private val handleCornerOval = RectF()

    
    
    
    // Dark text on the page, white text on the black outer area (see drawNameTag()).
    private val nameTagTextColorOnPage = Color.parseColor("#1D1B20")
    private val nameTagTextColorOffPage = Color.WHITE
    private val nameTagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = nameTagTextColorOnPage
        textSize = 12f * density
    }
    private val pageContentRect = RectF()
    private val nameTagHorizontalPad = 8f * density
    private val nameTagVerticalPad = 4f * density
    private val nameTagGap = 6f * density
    private val nameTagRect = RectF()

    private var selected: SketchPart? = null
    private var draggingPart: SketchPart? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dragMoved = false

    
    
    private enum class Handle { TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT }

    private val handleHitSlop = 16f * density
    
    
    private val minDimension = 4f * density
    private val minFontSize = 1f * density

    private var resizingPart: SketchPart? = null
    private var activeHandle: Handle? = null
    private var resizeAnchorX = 0f
    private var resizeAnchorY = 0f
    private var resizeStartW = 0f
    private var resizeStartH = 0f
    private var resizeStartFontSize = 0f




    private var scaleFactor = 1f
    private val minScale = 0.5f
    private val maxScale = 4f
    private val zoomPivotX: Float get() = width / 2f
    private val zoomPivotY: Float get() = height / 2f

    private fun toContentX(screenX: Float) = zoomPivotX + (screenX - zoomPivotX) / scaleFactor
    private fun toContentY(screenY: Float) = zoomPivotY + (screenY + panOffsetY - zoomPivotY) / scaleFactor
    private fun toScreenX(contentX: Float) = zoomPivotX + (contentX - zoomPivotX) * scaleFactor
    private fun toScreenY(contentY: Float) = zoomPivotY + (contentY - zoomPivotY) * scaleFactor - panOffsetY

    
    
    fun currentScale(): Float = scaleFactor

    /** The smallest zoom level pinch-zoom can reach (0.5x). Page width at this zoom is
     *  width * minZoom - SketchActivity's screens carousel uses it to lay screens out. */
    val minZoom: Float get() = minScale

    /**
     * True while SketchActivity's zoomed-out screens carousel is showing this canvas. In this mode
     * the canvas paints ONLY its page (no opaque outer background, no page-height handle, no
     * selection chrome) so neighbouring screens' pages can show through beside it, and it never
     * receives touches (the activity handles swipe/tap itself).
     */
    var overviewMode = false
        private set

    /** Pins zoom to the minimum (0.5x) and switches to the page-only overview rendering. Pan is
     *  left as-is (only clamped), so the page doesn't shift relative to where it was. */
    fun enterOverview() {
        overviewMode = true
        scaleFactor = minScale
        clampPan()
        invalidate()
    }

    /** Sets the zoom directly (clamped to the normal range) - used by SketchActivity to animate a
     *  screen between its 0.5x carousel size and 100% while entering/leaving the carousel. */
    fun setOverviewZoom(scale: Float) {
        scaleFactor = scale.coerceIn(minScale, maxScale)
        clampPan()
        invalidate()
    }

    /** Leaves overview rendering and returns to 100% zoom. */
    fun exitOverview() {
        overviewMode = false
        scaleFactor = 1f
        clampPan()
        invalidate()
    }

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
            clampPan()
            invalidate()
            if (scaleFactor <= minScale + 0.001f) listener?.onMaxZoomOut()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            
            
            if (resizingPart != null || canvasPanning) return
            val cx = toContentX(e.x)
            val cy = toContentY(e.y)
            val hit = hitTest(cx, cy)
            if (hit != null) {
                listener?.onPartLongPressed(hit)
            } else {
                
                
                
                
                if (multiSelected.isNotEmpty()) clearMultiSelection()
                marqueeArmed = true
                marqueeActive = false
                marqueeAnchorX = cx
                marqueeAnchorY = cy
                marqueeRect.set(cx, cy, cx, cy)
                marqueeLive.clear()
                panCandidate = false
                canvasPanning = false
                if (HapticSettings.isEnabled(context)) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                invalidate()
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val cx = toContentX(e.x)
            val cy = toContentY(e.y)
            
            
            val sel = selected
            if (sel != null && !sel.hidden && nameTagContentRect(sel).contains(cx, cy)) {
                listener?.onNameTagTapped(sel)
                return true
            }
            val hit = hitTest(cx, cy)
            if (multiSelected.isNotEmpty()) {
                if (hit == null || hit !in multiSelected) {
                    clearMultiSelection()
                } else {
                    
                    
                    return true
                }
            }
            if (hit != selected) {
                selected = hit
                invalidate()
                listener?.onSelectionChanged(hit)
            }
            if (hit == null) {
                
                listener?.onTapEmptySpace()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val hit = hitTest(toContentX(e.x), toContentY(e.y))
            if (hit == null) {
                
                listener?.onDoubleTapEmptySpace()
            } else if (listener?.onPartDoubleTapped(hit) != true) {
                // Listener declined (default no-op returns false, or there's no listener) - fall
                // back to the original behavior of resetting zoom to 100%.
                scaleFactor = 1f
                clampPan()
                invalidate()
            }
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        
        if (pageHeight <= 0f) pageHeight = h.toFloat()
        clampPan()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handlePageHandleTouch(event)) return true

        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val pinching = scaleGestureDetector.isInProgress || event.pointerCount > 1
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cx = toContentX(event.x)
                val cy = toContentY(event.y)
                val sel = selected
                val handle = sel?.let { handleHitTest(it, cx, cy) }
                if (sel != null && handle != null) {
                    resizingPart = sel
                    activeHandle = handle
                    resizeStartW = sel.w
                    resizeStartH = sel.h
                    resizeStartFontSize = sel.fontSize
                    val (ax, ay) = anchorPointFor(sel, handle)
                    resizeAnchorX = ax
                    resizeAnchorY = ay
                    draggingPart = null
                    dragMoved = false
                } else {
                    resizingPart = null
                    activeHandle = null
                    val hit = hitTest(cx, cy)
                    dragMoved = false
                    if (hit != null && hit in multiSelected) {
                        
                        
                        draggingGroup = true
                        draggingPart = null
                        groupDragAnchorX = cx
                        groupDragAnchorY = cy
                        panCandidate = false
                    } else {
                        draggingGroup = false
                        draggingPart = if (hit != null && !hit.locked) hit else null
                        if (hit != null) {
                            dragOffsetX = cx - hit.x
                            dragOffsetY = cy - hit.y
                            panCandidate = false
                        } else {
                            
                            
                            
                            
                            panCandidate = true
                            canvasPanning = false
                            panDragStartScreenY = event.y
                            panDragStartOffset = panOffsetY
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pinching) {
                    val resizing = resizingPart
                    if (resizing != null) {
                        val cx = toContentX(event.x)
                        val cy = toContentY(event.y)
                        when (activeHandle) {
                            Handle.TOP_LEFT, Handle.TOP_RIGHT, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT ->
                                resizeCorner(resizing, cx, cy)
                            Handle.LEFT, Handle.RIGHT -> resizeSideWidth(resizing, cx, cy)
                            Handle.TOP, Handle.BOTTOM -> resizeSideHeight(resizing, cx, cy)
                            null -> Unit
                        }
                        dragMoved = true
                        invalidate()
                        listener?.onSelectionChanged(resizing)
                    } else if (draggingPart != null) {
                        draggingPart?.let { p ->
                            val cx = toContentX(event.x)
                            val cy = toContentY(event.y)
                            p.x = (cx - dragOffsetX).coerceIn(0f, max(0f, width - p.w))
                            p.y = (cy - dragOffsetY).coerceIn(0f, max(0f, pageHeight - p.h))
                            dragMoved = true
                            invalidate()
                            listener?.onSelectionChanged(p)
                        }
                    } else if (draggingGroup) {
                        val cx = toContentX(event.x)
                        val cy = toContentY(event.y)
                        val dx = cx - groupDragAnchorX
                        val dy = cy - groupDragAnchorY
                        if (dx != 0f || dy != 0f) {
                            for (p in multiSelected) {
                                if (p.locked) continue
                                p.x = (p.x + dx).coerceIn(0f, max(0f, width - p.w))
                                p.y = (p.y + dy).coerceIn(0f, max(0f, pageHeight - p.h))
                            }
                            groupDragAnchorX = cx
                            groupDragAnchorY = cy
                            dragMoved = true
                            invalidate()
                        }
                    } else if (marqueeArmed) {
                        val cx = toContentX(event.x)
                        val cy = toContentY(event.y)
                        marqueeActive = true
                        marqueeRect.set(
                            minOf(marqueeAnchorX, cx), minOf(marqueeAnchorY, cy),
                            maxOf(marqueeAnchorX, cx), maxOf(marqueeAnchorY, cy),
                        )
                        marqueeLive.clear()
                        for (p in parts) {
                            if (p.hidden) continue
                            val pr = RectF(p.x, p.y, p.x + p.w, p.y + p.h)
                            if (RectF.intersects(marqueeRect, pr)) marqueeLive.add(p)
                        }
                        invalidate()
                    } else if (panCandidate) {
                        val deltaScreen = panDragStartScreenY - event.y
                        if (!canvasPanning && abs(deltaScreen) > panTouchSlop && maxPanOffsetY() > 0f) {
                            canvasPanning = true
                        }
                        if (canvasPanning) {
                            panOffsetY = (panDragStartOffset + deltaScreen).coerceIn(0f, maxPanOffsetY())
                            invalidate()
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                
                
                if (marqueeArmed || marqueeActive) {
                    marqueeArmed = false
                    marqueeActive = false
                    marqueeLive.clear()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if ((draggingPart != null || resizingPart != null || draggingGroup) && dragMoved) {
                    listener?.onPartsChanged()
                }
                if (marqueeActive) {
                    finalizeMarqueeSelection()
                } else if (marqueeArmed) {
                    
                    
                    listener?.onLongPressEmptySpace()
                }
                draggingPart = null
                draggingGroup = false
                resizingPart = null
                activeHandle = null
                panCandidate = false
                canvasPanning = false
                marqueeArmed = false
                marqueeActive = false
                marqueeLive.clear()
            }
        }
        return true
    }

    private fun finalizeMarqueeSelection() {
        val result = mutableSetOf<SketchPart>()
        result.addAll(marqueeLive)
        
        val groupIds = marqueeLive.mapNotNull { it.groupId }.toSet()
        if (groupIds.isNotEmpty()) {
            for (p in parts) if (p.groupId != null && p.groupId in groupIds) result.add(p)
        }
        multiSelected.clear()
        multiSelected.addAll(result)
        marqueeLive.clear()
        if (selected != null) {
            selected = null
            listener?.onSelectionChanged(null)
        }
        invalidate()
        if (multiSelected.isNotEmpty()) {
            listener?.onMultiSelectionFinalized(multiSelected.toList())
        } else {
            listener?.onMultiSelectionCleared()
        }
    }

    
    

    fun clearMultiSelection() {
        if (multiSelected.isEmpty()) return
        multiSelected.clear()
        invalidate()
        listener?.onMultiSelectionCleared()
    }

    // Clears the single-part selection (as opposed to the marquee multi-selection above).
    // Used when a screen's starter template parts are added programmatically, so the last part
    // placed doesn't end up looking pre-selected the first time the screen is shown.
    fun clearSelection() {
        if (selected == null) return
        selected = null
        invalidate()
        listener?.onSelectionChanged(null)
    }

    fun isSelectionGrouped(): Boolean =
        multiSelected.isNotEmpty() && multiSelected.all { it.groupId != null }

    fun toggleGroupSelection() {
        if (multiSelected.isEmpty()) return
        if (isSelectionGrouped()) {
            for (p in multiSelected) p.groupId = null
        } else {
            val gid = nextGroupId++
            for (p in multiSelected) p.groupId = gid
        }
        invalidate()
        listener?.onPartsChanged()
    }

    fun duplicateSelection() {
        if (multiSelected.isEmpty()) return
        var newId = (parts.maxOfOrNull { it.id } ?: 0L) + 1
        val offset = 24f * density
        
        
        val usedNames = parts.mapNotNullTo(HashSet()) { it.name.ifBlank { null } }
        val duplicates = multiSelected.map { p ->
            val newName = generateUniqueName(p.kind, usedNames)
            usedNames.add(newName)
            p.copy(
                id = newId++,
                x = (p.x + offset).coerceIn(0f, max(0f, width - p.w)),
                y = (p.y + offset).coerceIn(0f, max(0f, pageHeight - p.h)),
                groupId = null,
                name = newName,
            )
        }
        parts.addAll(duplicates)
        multiSelected.clear()
        multiSelected.addAll(duplicates)
        invalidate()
        listener?.onPartsChanged()
    }

    fun isSelectionLocked(): Boolean = multiSelected.isNotEmpty() && multiSelected.all { it.locked }

    fun setSelectionLocked(locked: Boolean) {
        if (multiSelected.isEmpty()) return
        for (p in multiSelected) p.locked = locked
        invalidate()
        listener?.onPartsChanged()
    }

    fun isSelectionHidden(): Boolean = multiSelected.isNotEmpty() && multiSelected.all { it.hidden }

    fun setSelectionHidden(hidden: Boolean) {
        if (multiSelected.isEmpty()) return
        for (p in multiSelected) p.hidden = hidden
        invalidate()
        listener?.onPartsChanged()
    }

    fun deleteSelection() {
        if (multiSelected.isEmpty()) return
        parts.removeAll(multiSelected)
        multiSelected.clear()
        invalidate()
        listener?.onPartsChanged()
        listener?.onMultiSelectionCleared()
    }
    

    
    
    
    
    private fun handlePageHandleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isNearPageHandle(event.x, event.y)) {
                    draggingPageHandle = true
                    pageDragStartScreenY = event.y
                    pageDragStartHeight = pageHeight
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!draggingPageHandle) return false
                val deltaScreen = event.y - pageDragStartScreenY
                val deltaContent = deltaScreen / scaleFactor
                pageHeight = (pageDragStartHeight + deltaContent).coerceIn(minPageHeight, maxPageHeight)
                clampPan()
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                
                
                if (draggingPageHandle) {
                    draggingPageHandle = false
                    invalidate()
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!draggingPageHandle) return false
                draggingPageHandle = false
                invalidate()
                return true
            }
            else -> return draggingPageHandle
        }
    }

    
    
    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val hovering = isNearPageHandle(event.x, event.y)
                if (hovering != pageHandleHovered) {
                    pageHandleHovered = hovering
                    invalidate()
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (pageHandleHovered) {
                    pageHandleHovered = false
                    invalidate()
                }
            }
        }
        return super.onHoverEvent(event)
    }

    private fun isNearPageHandle(screenX: Float, screenY: Float): Boolean {
        val centerX = width / 2f
        val centerY = toScreenY(pageHeight)
        return abs(screenX - centerX) <= pageHandleTouchHalfWidth &&
            abs(screenY - centerY) <= pageHandleTouchHalfHeight
    }

    
    
    private fun anchorPointFor(part: SketchPart, handle: Handle): Pair<Float, Float> {
        val left = part.x
        val top = part.y
        val right = part.x + part.w
        val bottom = part.y + part.h
        return when (handle) {
            Handle.TOP_LEFT -> right to bottom
            Handle.TOP -> left to bottom
            Handle.TOP_RIGHT -> left to bottom
            Handle.RIGHT -> left to top
            Handle.BOTTOM_RIGHT -> left to top
            Handle.BOTTOM -> left to top
            Handle.BOTTOM_LEFT -> right to top
            Handle.LEFT -> right to top
        }
    }

    
    
    
    private fun resizeCorner(part: SketchPart, cx: Float, cy: Float) {
        val handle = activeHandle ?: return
        val rawW = abs(cx - resizeAnchorX).coerceAtLeast(minDimension)
        val rawH = abs(cy - resizeAnchorY).coerceAtLeast(minDimension)
        val scale = ((rawW / resizeStartW) + (rawH / resizeStartH)) / 2f
        val newW = (resizeStartW * scale).coerceAtLeast(minDimension)
        val newH = (resizeStartH * scale).coerceAtLeast(minDimension)
        val newFontSize = (resizeStartFontSize * scale).coerceAtLeast(minFontSize)

        val newX = if (handle == Handle.TOP_LEFT || handle == Handle.BOTTOM_LEFT) resizeAnchorX - newW else resizeAnchorX
        val newY = if (handle == Handle.TOP_LEFT || handle == Handle.TOP_RIGHT) resizeAnchorY - newH else resizeAnchorY

        part.x = newX
        part.y = newY
        part.w = newW
        part.h = newH
        part.fontSize = newFontSize

        if (part.kind == PartKind.TEXT) applyTextAutoHeight(part)
    }

    
    
    private fun resizeSideWidth(part: SketchPart, cx: Float, cy: Float) {
        val handle = activeHandle ?: return
        val newW = abs(cx - resizeAnchorX).coerceAtLeast(minDimension)
        val newX = if (handle == Handle.LEFT) resizeAnchorX - newW else resizeAnchorX
        part.x = newX
        part.w = newW

        if (part.kind == PartKind.TEXT) applyTextAutoHeight(part)
    }

    
    
    
    private fun resizeSideHeight(part: SketchPart, cx: Float, cy: Float) {
        val handle = activeHandle ?: return
        val newH = abs(cy - resizeAnchorY).coerceAtLeast(minDimension)
        val newY = if (handle == Handle.TOP) resizeAnchorY - newH else resizeAnchorY
        part.y = newY
        part.h = newH
    }

    
    
    private fun applyTextAutoHeight(part: SketchPart) {
        val needed = measureWrappedTextHeight(part.label.ifBlank { part.kind.displayLabel }, part.fontSize, part.w)
        if (needed > part.h) part.h = needed
    }

    private fun measureWrappedTextHeight(text: String, fontSizePx: Float, boxWidthPx: Float): Float {
        val innerWidth = max(1, (boxWidthPx - textWrapHorizontalPadding * 2f).roundToInt())
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fontSizePx.coerceAtLeast(minFontSize) }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, innerWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
        return layout.height.toFloat() + textWrapVerticalPadding * 2f
    }

    
    
    fun relayoutTextIfNeeded(part: SketchPart) {
        if (part.kind != PartKind.TEXT) return
        applyTextAutoHeight(part)
        invalidate()
    }

    private fun handleHitTest(part: SketchPart, x: Float, y: Float): Handle? {
        val rect = selectionRect(part)
        val midX = rect.centerX()
        val midY = rect.centerY()
        val slop = handleHitSlop
        fun near(px: Float, py: Float) = abs(x - px) <= slop && abs(y - py) <= slop

        if (near(rect.left, rect.top)) return Handle.TOP_LEFT
        if (near(rect.right, rect.top)) return Handle.TOP_RIGHT
        if (near(rect.left, rect.bottom)) return Handle.BOTTOM_LEFT
        if (near(rect.right, rect.bottom)) return Handle.BOTTOM_RIGHT
        
        
        if (part.kind != PartKind.TEXT) {
            if (near(midX, rect.top)) return Handle.TOP
            if (near(midX, rect.bottom)) return Handle.BOTTOM
        }
        if (near(rect.left, midY)) return Handle.LEFT
        if (near(rect.right, midY)) return Handle.RIGHT
        return null
    }

    private fun hitTest(x: Float, y: Float): SketchPart? =
        parts.lastOrNull { p -> !p.hidden && x >= p.x && x <= p.x + p.w && y >= p.y && y <= p.y + p.h }

    fun addPart(part: SketchPart) {
        if (part.name.isBlank()) part.name = generatePartName(part.kind)
        if (part.kind == PartKind.TEXT) applyTextAutoHeight(part)
        parts.add(part)
        selected = part
        invalidate()
        listener?.onSelectionChanged(part)
        listener?.onPartsChanged()
    }

    
    
    
    
    
    fun generatePartName(kind: PartKind): String {
        val usedNames = parts.mapNotNullTo(HashSet()) { it.name.ifBlank { null } }
        return generateUniqueName(kind, usedNames)
    }

    private fun generateUniqueName(kind: PartKind, usedNames: Set<String>): String {
        val base = kind.displayLabel
        var n = 1
        while ("$base $n" in usedNames) n++
        return "$base $n"
    }

    
    
    
    fun renamePart(part: SketchPart, newName: String) {
        val trimmed = newName.trim()
        part.name = if (trimmed.isEmpty()) generatePartName(part.kind) else trimmed
        invalidate()
        listener?.onPartsChanged()
    }

    fun removePart(part: SketchPart) {
        parts.remove(part)
        if (selected == part) {
            selected = null
            listener?.onSelectionChanged(null)
        }
        if (multiSelected.remove(part) && multiSelected.isEmpty()) {
            listener?.onMultiSelectionCleared()
        }
        invalidate()
        listener?.onPartsChanged()
    }

    fun clearAll() {
        parts.clear()
        selected = null
        val hadMultiSelection = multiSelected.isNotEmpty()
        multiSelected.clear()
        invalidate()
        listener?.onSelectionChanged(null)
        if (hadMultiSelection) listener?.onMultiSelectionCleared()
        listener?.onPartsChanged()
    }

    override fun onDraw(canvas: Canvas) {
        
        
        
        if (!overviewMode) canvas.drawColor(outerBackgroundColor)
        val saveCount = canvas.save()
        
        
        
        canvas.translate(0f, -panOffsetY)
        canvas.scale(scaleFactor, scaleFactor, zoomPivotX, zoomPivotY)
        drawPage(canvas)
        for (part in parts) {
            if (part.hidden) continue
            
            if (part === selected) drawSelectionFrame(canvas, part)
            drawPart(canvas, part)
        }
        
        selected?.let {
            drawSelectionHandles(canvas, it)
            if (!it.hidden) drawNameTag(canvas, it)
        }

        
        
        
        if (marqueeActive) {
            for (p in marqueeLive) {
                canvas.drawRoundRect(selectionRect(p), selectionRadius, selectionRadius, multiSelectStrokePaint)
            }
            canvas.drawRect(marqueeRect, marqueeFillPaint)
            canvas.drawRect(marqueeRect, marqueeStrokePaint)
        } else {
            if (marqueeArmed) {
                
                
                canvas.drawCircle(marqueeAnchorX, marqueeAnchorY, 6f * density, marqueeArmIndicatorPaint)
            }
            for (p in multiSelected) {
                canvas.drawRoundRect(selectionRect(p), selectionRadius, selectionRadius, multiSelectStrokePaint)
            }
        }
        canvas.restoreToCount(saveCount)

        
        
        
        
        if (!overviewMode) drawPageHandle(canvas)
    }

    
    
    private fun drawPage(canvas: Canvas) {
        pageRadii[0] = 0f; pageRadii[1] = 0f 
        pageRadii[2] = 0f; pageRadii[3] = 0f 
        pageRadii[4] = pageCornerRadius; pageRadii[5] = pageCornerRadius 
        pageRadii[6] = pageCornerRadius; pageRadii[7] = pageCornerRadius 
        pagePath.reset()
        pagePath.addRoundRect(0f, 0f, width.toFloat(), pageHeight, pageRadii, Path.Direction.CW)
        canvas.drawPath(pagePath, pagePaint)
    }

    private fun drawPageHandle(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = toScreenY(pageHeight)
        pageHandlePaint.color = when {
            draggingPageHandle -> pageHandleColorDrag
            pageHandleHovered -> pageHandleColorHover
            else -> pageHandleColorIdle
        }
        val widthScale = if (draggingPageHandle) 1.15f else if (pageHandleHovered) 1.08f else 1f
        val halfW = (pageHandleWidth / 2f) * widthScale
        val halfH = pageHandleHeight / 2f
        pageHandleRect.set(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
        pageHandleHaloRect.set(pageHandleRect)
        pageHandleHaloRect.inset(-pageHandleHaloInset, -pageHandleHaloInset)
        canvas.drawRoundRect(pageHandleHaloRect, halfH + pageHandleHaloInset, halfH + pageHandleHaloInset, pageHandleHaloPaint)
        canvas.drawRoundRect(pageHandleRect, halfH, halfH, pageHandlePaint)
    }

    private fun drawPart(canvas: Canvas, part: SketchPart) {
        val rect = RectF(part.x, part.y, part.x + part.w, part.y + part.h)
        val radius = part.kind.cornerRadius * density
        // Buttons placed via the Buttons panel's "+ Add to Canvas" button (see
        // buildButtonCategoryPanel() in ComponentsPanel.kt) carry a buttonStyle recording which
        // of the two panel previews was selected, and are drawn to match that preview instead of
        // PartKind.BUTTON's generic purple-pill defaults. Buttons placed any other way (starter
        // templates, etc.) have buttonStyle == null and keep falling through to the generic path.
        val buttonStyle = part.buttonStyle.takeIf { part.kind == PartKind.BUTTON }
        var textColorOverride: Int? = null
        when (buttonStyle) {
            ButtonStyle.FRAMELESS -> {
                // No fill, no border - just the label, matching the frameless preview.
            }
            ButtonStyle.FRAMED -> {
                // Solid black fill/border, white label, matching the framed preview's default
                // black styling.
                fillPaint.color = Color.BLACK
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                val previousStrokeColor = strokePaint.color
                strokePaint.color = Color.BLACK
                canvas.drawRoundRect(rect, radius, radius, strokePaint)
                strokePaint.color = previousStrokeColor
                textColorOverride = Color.WHITE
            }
            null -> {
                if (part.kind.fillColor != Color.TRANSPARENT) {
                    fillPaint.color = part.kind.fillColor
                    canvas.drawRoundRect(rect, radius, radius, fillPaint)
                }
                if (part.kind.hasBorder) {
                    canvas.drawRoundRect(rect, radius, radius, strokePaint)
                }
            }
        }
        val label = part.label.ifBlank { part.kind.displayLabel }
        if (part.kind == PartKind.TEXT) {
            drawWrappedText(canvas, part, label)
        } else if (buttonStyle == ButtonStyle.FRAMED) {
            // FRAMED buttons honor part.buttonAlign (set from the Buttons panel's Align control -
            // see buildButtonCategoryPanel() in ComponentsPanel.kt). FRAMELESS and generic parts
            // keep the plain centered draw below, matching the panel preview's own behavior
            // (Align only appears for the framed variant there too).
            drawFramedButtonLabel(canvas, part, rect, label, textColorOverride)
        } else {
            val previousTextColor = textPaint.color
            if (textColorOverride != null) textPaint.color = textColorOverride
            textPaint.textSize = part.fontSize.coerceAtLeast(minFontSize)
            canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize / 3f, textPaint)
            textPaint.color = previousTextColor
        }
    }

    // Draws a FRAMED button's label according to part.buttonAlign. Justify and Stack have no
    // natural single-line rendering, so they're given real (not placeholder) treatments: Justify
    // spreads the label's words edge-to-edge, Stack wraps the label across centered lines.
    private fun drawFramedButtonLabel(canvas: Canvas, part: SketchPart, rect: RectF, label: String, textColorOverride: Int?) {
        val previousColor = textPaint.color
        val previousAlign = textPaint.textAlign
        if (textColorOverride != null) textPaint.color = textColorOverride
        textPaint.textSize = part.fontSize.coerceAtLeast(minFontSize)
        val baselineY = rect.centerY() + textPaint.textSize / 3f
        when (part.buttonAlign) {
            "Start" -> {
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(label, rect.left + buttonLabelHorizontalPadding, baselineY, textPaint)
            }
            "End" -> {
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(label, rect.right - buttonLabelHorizontalPadding, baselineY, textPaint)
            }
            "Justify" -> drawJustifiedButtonLabel(canvas, rect, label, baselineY)
            "Stack" -> drawStackedButtonLabel(canvas, rect, label)
            else -> {
                // "Centered" and any unrecognized value fall back to centered - the same default
                // the panel preview starts on.
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(label, rect.centerX(), baselineY, textPaint)
            }
        }
        textPaint.color = previousColor
        textPaint.textAlign = previousAlign
    }

    // Real justification: each word is drawn left-to-right with the extra space (available width
    // minus the words' own width) spread evenly between them, so the line's first and last
    // characters land on the button's left/right padding. A single word has no gap to stretch, so
    // it falls back to centered - same as the panel preview's own Justify approximation.
    private fun drawJustifiedButtonLabel(canvas: Canvas, rect: RectF, label: String, baselineY: Float) {
        val words = label.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(label, rect.centerX(), baselineY, textPaint)
            return
        }
        textPaint.textAlign = Paint.Align.LEFT
        val availableWidth = (rect.width() - buttonLabelHorizontalPadding * 2f).coerceAtLeast(0f)
        val wordWidths = words.map { textPaint.measureText(it) }
        val totalWordWidth = wordWidths.sum()
        val gapCount = words.size - 1
        val minGap = textPaint.measureText(" ")
        val gap = ((availableWidth - totalWordWidth) / gapCount).coerceAtLeast(minGap)
        var x = rect.left + buttonLabelHorizontalPadding
        words.forEachIndexed { index, word ->
            canvas.drawText(word, x, baselineY, textPaint)
            x += wordWidths[index] + gap
        }
    }

    // Wraps the label across multiple centered lines instead of one, approximating a "stacked"
    // label. Reuses wrapTextPaint/StaticLayout the same way drawWrappedText() does for
    // PartKind.TEXT further below.
    private fun drawStackedButtonLabel(canvas: Canvas, rect: RectF, label: String) {
        wrapTextPaint.color = textPaint.color
        wrapTextPaint.textSize = textPaint.textSize
        val innerWidth = max(1, (rect.width() - buttonLabelHorizontalPadding * 2f).roundToInt())
        val layout = StaticLayout.Builder
            .obtain(label, 0, label.length, wrapTextPaint, innerWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
        val saveCount = canvas.save()
        canvas.translate(rect.left + buttonLabelHorizontalPadding, rect.centerY() - layout.height / 2f)
        layout.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    
    
    private fun drawWrappedText(canvas: Canvas, part: SketchPart, label: String) {
        wrapTextPaint.textSize = part.fontSize.coerceAtLeast(minFontSize)
        val innerWidth = max(1, (part.w - textWrapHorizontalPadding * 2f).roundToInt())
        val layout = StaticLayout.Builder
            .obtain(label, 0, label.length, wrapTextPaint, innerWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
        val saveCount = canvas.save()
        canvas.translate(part.x + textWrapHorizontalPadding, part.y + textWrapVerticalPadding)
        layout.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun selectionRect(part: SketchPart): RectF {
        val pad = selectionPad
        return RectF(part.x - pad, part.y - pad, part.x + part.w + pad, part.y + part.h + pad)
    }

    
    
    
    private fun nameTagContentRect(part: SketchPart): RectF {
        val text = part.name.ifBlank { part.kind.displayLabel }
        val textWidth = nameTagTextPaint.measureText(text)
        val rect = selectionRect(part)
        val tagHeight = nameTagTextPaint.textSize + nameTagVerticalPad * 2f
        val tagWidth = textWidth + nameTagHorizontalPad * 2f
        val left = rect.left
        var bottom = rect.top - nameTagGap
        var top = bottom - tagHeight
        if (top < 0f) {
            
            top = rect.top + nameTagGap
            bottom = top + tagHeight
        }
        return RectF(left, top, left + tagWidth, bottom)
    }

    private fun drawNameTag(canvas: Canvas, part: SketchPart) {
        nameTagRect.set(nameTagContentRect(part))
        
        val text = part.name.ifBlank { part.kind.displayLabel }
        val baseline = nameTagRect.top + nameTagVerticalPad - nameTagTextPaint.ascent()
        val x = nameTagRect.left + nameTagHorizontalPad
        pageContentRect.set(0f, 0f, width.toFloat(), pageHeight)

        if (pageContentRect.contains(nameTagRect)) {
            // Fully over the page - the common case.
            nameTagTextPaint.color = nameTagTextColorOnPage
            canvas.drawText(text, x, baseline, nameTagTextPaint)
            return
        }

        // Tag sits (fully or partly) over the black outer area: draw it twice, once clipped to
        // the page (dark) and once clipped to everything else (white), so a tag straddling the
        // page edge stays readable on both sides.
        var save = canvas.save()
        canvas.clipRect(pageContentRect)
        nameTagTextPaint.color = nameTagTextColorOnPage
        canvas.drawText(text, x, baseline, nameTagTextPaint)
        canvas.restoreToCount(save)

        save = canvas.save()
        canvas.clipOutRect(pageContentRect)
        nameTagTextPaint.color = nameTagTextColorOffPage
        canvas.drawText(text, x, baseline, nameTagTextPaint)
        canvas.restoreToCount(save)

        nameTagTextPaint.color = nameTagTextColorOnPage
    }

    /** True when the centre of [part]'s name tag lies over the page (not the black outer area).
     *  Used by SketchActivity to pick a readable text colour for the inline name editor. */
    fun isNameTagOverPage(part: SketchPart): Boolean {
        val c = nameTagContentRect(part)
        return c.centerX() in 0f..width.toFloat() && c.centerY() in 0f..pageHeight
    }

    
    
    
    fun nameTagScreenRect(part: SketchPart): RectF {
        val c = nameTagContentRect(part)
        return RectF(toScreenX(c.left), toScreenY(c.top), toScreenX(c.right), toScreenY(c.bottom))
    }

    
    fun nameTagBaseTextSizePx(): Float = nameTagTextPaint.textSize

    private fun drawSelectionFrame(canvas: Canvas, part: SketchPart) {
        val rect = selectionRect(part)
        canvas.drawRoundRect(rect, selectionRadius, selectionRadius, selectionFramePaint)
    }

    private fun drawSelectionHandles(canvas: Canvas, part: SketchPart) {
        val rect = selectionRect(part)
        val midX = rect.centerX()
        val midY = rect.centerY()
        val o = handleOffset
        val half = handleLineHalfLength

        
        
        canvas.drawLine(midX - half, rect.top - o, midX + half, rect.top - o, handleLinePaint)
        canvas.drawLine(midX - half, rect.bottom + o, midX + half, rect.bottom + o, handleLinePaint)
        canvas.drawLine(rect.left - o, midY - half, rect.left - o, midY + half, handleLinePaint)
        canvas.drawLine(rect.right + o, midY - half, rect.right + o, midY + half, handleLinePaint)

        
        
        val r = handleCornerRadius
        val d = r * 2f
        handleCornerOval.set(rect.left - o, rect.top - o, rect.left - o + d, rect.top - o + d)
        canvas.drawArc(handleCornerOval, 180f, 90f, false, handleLinePaint)
        handleCornerOval.set(rect.right + o - d, rect.top - o, rect.right + o, rect.top - o + d)
        canvas.drawArc(handleCornerOval, 270f, 90f, false, handleLinePaint)
        handleCornerOval.set(rect.right + o - d, rect.bottom + o - d, rect.right + o, rect.bottom + o)
        canvas.drawArc(handleCornerOval, 0f, 90f, false, handleLinePaint)
        handleCornerOval.set(rect.left - o, rect.bottom + o - d, rect.left - o + d, rect.bottom + o)
        canvas.drawArc(handleCornerOval, 90f, 90f, false, handleLinePaint)
    }
}