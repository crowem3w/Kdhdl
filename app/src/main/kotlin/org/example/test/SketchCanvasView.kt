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
        // A software layer is required for Paint#setShadowLayer to render on shapes (not just
        // text) reliably across API levels — used for the shadow-only selection frame below.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    interface Listener {
        fun onLongPressEmptySpace(x: Float, y: Float)
        fun onPartLongPressed(part: SketchPart)
        fun onSelectionChanged(part: SketchPart?)
        fun onPartsChanged()
    }

    var listener: Listener? = null
    val parts = mutableListOf<SketchPart>()

    
    val selectedPart: SketchPart? get() = selected

    private val density = context.resources.displayMetrics.density

    // --- Mobile UI "page" (the resizable white artboard) -------------------------------------
    // The page represents the actual bottom-bounded content area of the mobile app/page being
    // designed. Everything outside it (below its bottom edge) is pure black - an editor-only
    // backdrop that exists purely for contrast/visibility and must never factor into the page's
    // reported height (see pageHeight below, which callers should use instead of View#getHeight
    // whenever they mean "the height of the app page", e.g. centering new parts or exporting).
    var pageHeight: Float = 0f
        private set

    // Exposed so callers outside the view (e.g. the activity's own gesture handling) can avoid
    // starting a competing gesture while the user is actively resizing the page.
    val isDraggingPageHandle: Boolean get() = draggingPageHandle
    private val minPageHeight = 160f * density
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val pagePath = Path()
    private val pageCornerRadius = 12f * density
    private val pageRadii = FloatArray(8)

    // Bottom-edge drag handle: a small pill centered under the page's bottom edge, kept in
    // screen-space (constant on-screen size regardless of zoom) while its Y position tracks the
    // page's bottom edge through the same pan/zoom transform used for everything else.
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
    // -------------------------------------------------------------------------------------------

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#6750A4")
    }
    // Used for single-line, centered labels on non-text parts (buttons, cards, chips, etc).
    // textSize is set per-part (part.fontSize) right before each draw call.
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D1B20")
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    // Used for wrapped, left-aligned TEXT parts via StaticLayout. textSize is set per-part
    // (part.fontSize) right before each draw/measure call.
    private val wrapTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D1B20")
    }
    // Inner padding kept between a TEXT part's bounding box edges and its wrapped text, both for
    // rendering and for measuring how tall the wrapped text needs the box to be.
    private val textWrapHorizontalPadding = 6f * density
    private val textWrapVerticalPadding = 4f * density

    // Selection frame: 0px border, 12dp corner radius, rendered as a white card matching the
    // canvas background so it reads as a soft drop shadow around the selected element rather
    // than a visible box outline.
    private val selectionFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        setShadowLayer(10f * density, 0f, 3f * density, Color.parseColor("#40000000"))
    }
    private val selectionPad = 10f * density
    private val selectionRadius = 12f * density

    // Purple resize handles on the 4 sides + 4 corners of the selection frame, drawn as short
    // straight lines on the sides and small curved (quarter-circle) lines on the corners, sitting
    // 1px outside the (0px) selection border.
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

    // Resize handle interaction. A handle grab is detected on ACTION_DOWN (only when the touched
    // part is already selected) and takes priority over the plain move/drag path.
    private enum class Handle { TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT }

    private val handleHitSlop = 16f * density
    // Safety floor only (not a design minimum) so dimensions/font size never hit zero or flip
    // negative mid-drag.
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
    private fun toContentY(screenY: Float) = zoomPivotY + (screenY - zoomPivotY) / scaleFactor
    private fun toScreenY(contentY: Float) = zoomPivotY + (contentY - zoomPivotY) * scaleFactor

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            // Don't treat a long-press that starts on a resize handle as a long-press-to-open-menu.
            if (resizingPart != null) return
            val cx = toContentX(e.x)
            val cy = toContentY(e.y)
            val hit = hitTest(cx, cy)
            if (hit != null) {
                listener?.onPartLongPressed(hit)
            } else {
                listener?.onLongPressEmptySpace(cx, cy)
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val hit = hitTest(toContentX(e.x), toContentY(e.y))
            if (hit != selected) {
                selected = hit
                invalidate()
                listener?.onSelectionChanged(hit)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            scaleFactor = 1f
            invalidate()
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Defaults the page to fill the whole view the first time it's laid out, so nothing
        // appears cut off until the user deliberately drags the handle to shorten it.
        if (pageHeight <= 0f) pageHeight = h.toFloat()
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
                    draggingPart = hit
                    dragMoved = false
                    if (hit != null) {
                        dragOffsetX = cx - hit.x
                        dragOffsetY = cy - hit.y
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
                    } else {
                        draggingPart?.let { p ->
                            val cx = toContentX(event.x)
                            val cy = toContentY(event.y)
                            p.x = (cx - dragOffsetX).coerceIn(0f, max(0f, width - p.w))
                            p.y = (cy - dragOffsetY).coerceIn(0f, max(0f, pageHeight - p.h))
                            dragMoved = true
                            invalidate()
                            listener?.onSelectionChanged(p)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if ((draggingPart != null || resizingPart != null) && dragMoved) listener?.onPartsChanged()
                draggingPart = null
                resizingPart = null
                activeHandle = null
            }
        }
        return true
    }

    // Detects and drives dragging the bottom-edge page handle. Kept entirely separate from (and
    // checked before) the normal touch pipeline above so it never competes with part
    // selection/dragging/resizing or pinch-to-zoom for the same touch stream: once a drag on the
    // handle starts, every event in that stream is consumed here and nothing else sees it.
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
                pageHeight = (pageDragStartHeight + deltaContent).coerceAtLeast(minPageHeight)
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger touching down mid-drag means pinch-to-zoom is taking over;
                // bail out of the page-resize drag rather than fight it for the gesture.
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

    // Mouse-only hover feedback (touch input never generates hover events) so the handle visibly
    // reacts before the user even presses down on it.
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

    // Fixed point (in content coordinates) that stays put while a given handle is dragged, i.e.
    // the corner/edge of the part's bounding box opposite the one being grabbed.
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

    // Corner handles: scale font size and both box dimensions together, anchored at the opposite
    // corner. For TEXT parts, if the wrapped label needs more vertical room than the proportional
    // scale gives it, the box expands downward to fit (auto-wrap/overflow takes priority).
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

    // Left/right side handles: change only the box width (and x, if grabbed from the left), never
    // the font size. This re-wraps (or unwraps) TEXT content within the new width.
    private fun resizeSideWidth(part: SketchPart, cx: Float, cy: Float) {
        val handle = activeHandle ?: return
        val newW = abs(cx - resizeAnchorX).coerceAtLeast(minDimension)
        val newX = if (handle == Handle.LEFT) resizeAnchorX - newW else resizeAnchorX
        part.x = newX
        part.w = newW

        if (part.kind == PartKind.TEXT) applyTextAutoHeight(part)
    }

    // Top/bottom side handles: change only the box height (and y, if grabbed from the top). Not
    // reachable for TEXT parts — handleHitTest excludes them there since TEXT height is driven by
    // wrapped content, not a manual side drag.
    private fun resizeSideHeight(part: SketchPart, cx: Float, cy: Float) {
        val handle = activeHandle ?: return
        val newH = abs(cy - resizeAnchorY).coerceAtLeast(minDimension)
        val newY = if (handle == Handle.TOP) resizeAnchorY - newH else resizeAnchorY
        part.y = newY
        part.h = newH
    }

    // Grows (never shrinks) a TEXT part's height so its wrapped label always fits, expanding
    // downward since y is left untouched.
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

    // Recomputes a TEXT part's auto-expanded height (e.g. after its label changes externally)
    // and redraws. No-op for non-text parts.
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
        // Top/bottom side handles are disabled for TEXT: its height always follows wrapped
        // content, so there's nothing meaningful for them to drag.
        if (part.kind != PartKind.TEXT) {
            if (near(midX, rect.top)) return Handle.TOP
            if (near(midX, rect.bottom)) return Handle.BOTTOM
        }
        if (near(rect.left, midY)) return Handle.LEFT
        if (near(rect.right, midY)) return Handle.RIGHT
        return null
    }

    private fun hitTest(x: Float, y: Float): SketchPart? =
        parts.lastOrNull { p -> x >= p.x && x <= p.x + p.w && y >= p.y && y <= p.y + p.h }

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
        invalidate()
        listener?.onPartsChanged()
    }

    fun clearAll() {
        parts.clear()
        selected = null
        invalidate()
        listener?.onSelectionChanged(null)
        listener?.onPartsChanged()
    }

    override fun onDraw(canvas: Canvas) {
        // Pure black backdrop, unaffected by pan/zoom, so it always fully covers whatever the
        // (possibly shorter, possibly zoomed) white page doesn't. This area is editor-only chrome
        // - never part of the app page - and is what makes the page's true bottom edge legible.
        canvas.drawColor(Color.BLACK)
        val saveCount = canvas.save()
        canvas.scale(scaleFactor, scaleFactor, zoomPivotX, zoomPivotY)
        drawPage(canvas)
        for (part in parts) {
            // Draw the shadow frame behind the part first so the frame never covers its content.
            if (part === selected) drawSelectionFrame(canvas, part)
            drawPart(canvas, part)
        }
        // Handles are drawn last, on top of every part, so they stay grabbable.
        selected?.let { drawSelectionHandles(canvas, it) }
        canvas.restoreToCount(saveCount)

        // Drawn after the pan/zoom transform is restored so the handle keeps a constant on-screen
        // size (only its Y position tracks the zoomed page bottom edge), matching how a resize
        // affordance should feel regardless of zoom level.
        drawPageHandle(canvas)
    }

    // The white artboard representing the actual mobile app page, rounded only at the bottom two
    // corners (12dp) to read as the bottom edge of a device screen.
    private fun drawPage(canvas: Canvas) {
        pageRadii[0] = 0f; pageRadii[1] = 0f // top-left
        pageRadii[2] = 0f; pageRadii[3] = 0f // top-right
        pageRadii[4] = pageCornerRadius; pageRadii[5] = pageCornerRadius // bottom-right
        pageRadii[6] = pageCornerRadius; pageRadii[7] = pageCornerRadius // bottom-left
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

    // TEXT parts wrap within their bounding box width and draw top-left aligned, rather than as a
    // single centered line.
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

        // 4 sides: short straight lines, 1px outside the border, one horizontal (—) on
        // top/bottom, one vertical (|) on left/right.
        canvas.drawLine(midX - half, rect.top - o, midX + half, rect.top - o, handleLinePaint)
        canvas.drawLine(midX - half, rect.bottom + o, midX + half, rect.bottom + o, handleLinePaint)
        canvas.drawLine(rect.left - o, midY - half, rect.left - o, midY + half, handleLinePaint)
        canvas.drawLine(rect.right + o, midY - half, rect.right + o, midY + half, handleLinePaint)

        // 4 corners: small curved (quarter-circle) lines, 1px outside the border, each one
        // bowing away from the selection and tangent to its two adjacent side lines.
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
