package org.example.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max










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

    private val bgPaint = Paint().apply { color = Color.WHITE }
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

    
    
    
    private var scaleFactor = 1f
    private val minScale = 0.5f
    private val maxScale = 4f
    private val zoomPivotX: Float get() = width / 2f
    private val zoomPivotY: Float get() = height / 2f

    private fun toContentX(screenX: Float) = zoomPivotX + (screenX - zoomPivotX) / scaleFactor
    private fun toContentY(screenY: Float) = zoomPivotY + (screenY - zoomPivotY) / scaleFactor

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val pinching = scaleGestureDetector.isInProgress || event.pointerCount > 1
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cx = toContentX(event.x)
                val cy = toContentY(event.y)
                val hit = hitTest(cx, cy)
                draggingPart = hit
                dragMoved = false
                if (hit != null) {
                    dragOffsetX = cx - hit.x
                    dragOffsetY = cy - hit.y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pinching) {
                    draggingPart?.let { p ->
                        val cx = toContentX(event.x)
                        val cy = toContentY(event.y)
                        p.x = (cx - dragOffsetX).coerceIn(0f, max(0f, width - p.w))
                        p.y = (cy - dragOffsetY).coerceIn(0f, max(0f, height - p.h))
                        dragMoved = true
                        invalidate()
                        listener?.onSelectionChanged(p)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingPart != null && dragMoved) listener?.onPartsChanged()
                draggingPart = null
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): SketchPart? =
        parts.lastOrNull { p -> x >= p.x && x <= p.x + p.w && y >= p.y && y <= p.y + p.h }

    fun addPart(part: SketchPart) {
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
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val saveCount = canvas.save()
        canvas.scale(scaleFactor, scaleFactor, zoomPivotX, zoomPivotY)
        for (part in parts) {
            // Draw the shadow frame behind the part first so the frame never covers its content.
            if (part === selected) drawSelectionFrame(canvas, part)
            drawPart(canvas, part)
        }
        // Handles are drawn last, on top of every part, so they stay grabbable.
        selected?.let { drawSelectionHandles(canvas, it) }
        canvas.restoreToCount(saveCount)
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
        canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize / 3f, textPaint)
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