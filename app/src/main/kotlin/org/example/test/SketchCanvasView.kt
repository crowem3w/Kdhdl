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
        
        
        fun onFlickEmptySpace()
        
        
        
        fun onDoubleTapEmptySpace()
        
        
        fun onTapEmptySpace()
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

    
    
    val isDraggingPageHandle: Boolean get() = draggingPageHandle
    
    
    
    private val minPageHeight: Float get() = height.toFloat()
    private val maxPageHeight: Float get() = height.toFloat() * 10f
    private val pagePaint = Paint().apply { color = Color.WHITE }
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
    

    
    
    
    
    
    
    
    
    
    var panOffsetY: Float = 0f
        private set
    val isPanningCanvas: Boolean get() = canvasPanning
    private var panCandidate = false
    private var canvasPanning = false
    private var panDragStartScreenY = 0f
    private var panDragStartOffset = 0f
    private val panTouchSlop = 8f * density
    
    
    private val panHandleBottomMargin = 32f * density
    
    
    
    
    
    private val flickMinDistance = 24f * density
    private val flickMaxDistance = 160f * density

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
    private fun toScreenY(contentY: Float) = zoomPivotY + (contentY - zoomPivotY) * scaleFactor - panOffsetY

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
            clampPan()
            invalidate()
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
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val hit = hitTest(toContentX(e.x), toContentY(e.y))
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
            } else {
                scaleFactor = 1f
                clampPan()
                invalidate()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            
            
            
            if (scaleGestureDetector.isInProgress || e2.pointerCount > 1) return false
            if (resizingPart != null || draggingPart != null || draggingGroup) return false
            if (canvasPanning || marqueeArmed || marqueeActive) return false
            if (hitTest(toContentX(start.x), toContentY(start.y)) != null) return false

            val distance = kotlin.math.hypot((e2.x - start.x).toDouble(), (e2.y - start.y).toDouble()).toFloat()
            if (distance < flickMinDistance || distance > flickMaxDistance) return false

            listener?.onFlickEmptySpace()
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
        val duplicates = multiSelected.map { p ->
            p.copy(
                id = newId++,
                x = (p.x + offset).coerceIn(0f, max(0f, width - p.w)),
                y = (p.y + offset).coerceIn(0f, max(0f, pageHeight - p.h)),
                groupId = null,
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
        if (part.kind == PartKind.TEXT) applyTextAutoHeight(part)
        parts.add(part)
        selected = part
        invalidate()
        listener?.onSelectionChanged(part)
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
        
        
        
        canvas.drawColor(Color.BLACK)
        val saveCount = canvas.save()
        
        
        
        canvas.translate(0f, -panOffsetY)
        canvas.scale(scaleFactor, scaleFactor, zoomPivotX, zoomPivotY)
        drawPage(canvas)
        for (part in parts) {
            if (part.hidden) continue
            
            if (part === selected) drawSelectionFrame(canvas, part)
            drawPart(canvas, part)
        }
        
        selected?.let { drawSelectionHandles(canvas, it) }

        
        
        
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

        
        
        
        
        drawPageHandle(canvas)
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
        canvas.drawRoundRect(pageHandleRect, halfH, halfH, pageHandlePaint)
    }

    private fun drawPart(canvas: Canvas, part: SketchPart) {
        val rect = RectF(part.x, part.y, part.x + part.w, part.y + part.h)
        val radius = part.kind.cornerRadius * density
        if (part.kind.fillColor != Color.TRANSPARENT) {
            fillPaint.color = part.kind.fillColor
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
        if (part.kind.hasBorder) {
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }
        val label = part.label.ifBlank { part.kind.displayLabel }
        if (part.kind == PartKind.TEXT) {
            drawWrappedText(canvas, part, label)
        } else {
            textPaint.textSize = part.fontSize.coerceAtLeast(minFontSize)
            canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize / 3f, textPaint)
        }
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