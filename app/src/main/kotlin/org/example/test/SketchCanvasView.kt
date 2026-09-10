package org.example.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.parseColor("#B3261E")
        pathEffect = DashPathEffect(floatArrayOf(12f * density, 8f * density), 0f)
    }
    private var selected: SketchPart? = null
    private var draggingPart: SketchPart? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dragMoved = false

    // Zoom state. Scaling pivots around the view's center; content-space coordinates (used for
    // hit-testing/dragging) are derived from screen coordinates via the pivot + scaleFactor, see
    // toContentX/toContentY.
    private var scaleFactor = 1f
    private val minScale = 0.5f
    private val maxScale = 4f
    private val pivotX: Float get() = width / 2f
    private val pivotY: Float get() = height / 2f

    private fun toContentX(screenX: Float) = pivotX + (screenX - pivotX) / scaleFactor
    private fun toContentY(screenY: Float) = pivotY + (screenY - pivotY) / scaleFactor

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
        canvas.scale(scaleFactor, scaleFactor, pivotX, pivotY)
        for (part in parts) drawPart(canvas, part)
        selected?.let { drawSelection(canvas, it) }
        canvas.restoreToCount(saveCount)
    }

    private fun drawPart(canvas: Canvas, part: SketchPart) {
        val rect = RectF(part.x, part.y, part.x + part.w, part.y + part.h)
        val radius = part.kind.cornerRadius * density
        if (part.kind.fillColor != Color.TRANSPARENT) {
            fillPaint.color = part.kind.fillColor
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        val label = part.label.ifBlank { part.kind.displayLabel }
        canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize / 3f, textPaint)
    }

    private fun drawSelection(canvas: Canvas, part: SketchPart) {
        val pad = 6f * density
        val rect = RectF(part.x - pad, part.y - pad, part.x + part.w + pad, part.y + part.h + pad)
        val radius = part.kind.cornerRadius * density + pad
        canvas.drawRoundRect(rect, radius, radius, selectionPaint)
    }
}