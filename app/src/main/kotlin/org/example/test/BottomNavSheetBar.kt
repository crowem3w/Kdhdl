package org.example.test

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min





















class BottomNavSheetBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val edgeThicknessPx = dp(6f)
    private val hugeCornerPx = dp(999f)

    
    
    
    private val dipDepthPx = dp(28f)
    private val dipHalfWidthFallbackPx = dp(38f)
    private val dipHalfWidthPaddingPx = dp(32f) 

    private var activeIndex = 0
    private var dipCenterX = 0f
    private var dipHalfWidth = dipHalfWidthFallbackPx
    private var geometryReady = false

    private var selectionAnimator: ValueAnimator? = null

    






    var onSelectionProgress: ((oldIndex: Int, newIndex: Int, t: Float) -> Unit)? = null

    

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A0A10") }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#33343F")
    }
    private val dimpleShadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contactShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.25f)
        color = Color.parseColor("#4A4B58")
        strokeCap = Paint.Cap.ROUND
    }

    private val facePath = Path()
    private val dipCurvePath = Path() 
    private val edgeRect = RectF()
    private val faceRect = RectF()
    private val cornerOval = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val faceBottom = h - edgeThicknessPx
        facePaint.shader = LinearGradient(
            0f, 0f, 0f, faceBottom,
            intArrayOf(Color.parseColor("#24252F"), Color.parseColor("#1A1B24"), Color.parseColor("#131319")),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        if (!geometryReady) {
            val cx = pillCenterX(activeIndex)
            if (cx > 0f) {
                dipCenterX = cx
                dipHalfWidth = pillHalfWidth(activeIndex)
            } else {
                dipCenterX = w / 2f
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        
        
        
        if (selectionAnimator?.isRunning != true) {
            val cx = pillCenterX(activeIndex)
            if (cx > 0f) {
                dipCenterX = cx
                dipHalfWidth = pillHalfWidth(activeIndex)
                geometryReady = true
                invalidate()
            }
        }
    }

    

    





    fun setActiveTabIndex(index: Int, animate: Boolean = true) {
        if (index == activeIndex && geometryReady) return
        val oldIndex = activeIndex
        activeIndex = index

        val oldCenter = if (geometryReady) dipCenterX else pillCenterX(oldIndex)
        val oldHalf = if (geometryReady) dipHalfWidth else pillHalfWidth(oldIndex)
        val newCenter = pillCenterX(index)
        val newHalf = pillHalfWidth(index)

        selectionAnimator?.cancel()

        if (!animate || newCenter <= 0f) {
            if (newCenter > 0f) {
                dipCenterX = newCenter
                dipHalfWidth = newHalf
                geometryReady = true
            } else if (oldCenter > 0f) {
                dipCenterX = oldCenter
                dipHalfWidth = oldHalf
            }
            invalidate()
            onSelectionProgress?.invoke(oldIndex, index, 1f)
            return
        }

        selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = OvershootInterpolator(1.15f)
            addUpdateListener {
                val t = it.animatedValue as Float
                dipCenterX = oldCenter + (newCenter - oldCenter) * t
                dipHalfWidth = oldHalf + (newHalf - oldHalf) * t
                geometryReady = true
                invalidate()
                onSelectionProgress?.invoke(oldIndex, index, t)
            }
            start()
        }
    }

    

    




    private fun pillCenterX(index: Int): Float {
        val column = getChildAt(index) as? LinearLayout ?: return 0f
        val pill = column.getChildAt(0) ?: return 0f
        if (pill.width <= 0) return 0f
        return column.left + pill.left + pill.width / 2f
    }

    private fun pillHalfWidth(index: Int): Float {
        val column = getChildAt(index) as? LinearLayout ?: return dipHalfWidthFallbackPx
        val pill = column.getChildAt(0) ?: return dipHalfWidthFallbackPx
        if (pill.width <= 0) return dipHalfWidthFallbackPx
        return pill.width / 2f + dipHalfWidthPaddingPx
    }

    

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        
        
        edgeRect.set(0f, edgeThicknessPx, w, h)
        val edgeRadius = min(hugeCornerPx, edgeRect.height() / 2f)
        canvas.drawRoundRect(edgeRect, edgeRadius, edgeRadius, edgePaint)

        
        val faceBottom = h - edgeThicknessPx
        faceRect.set(0f, 0f, w, faceBottom)
        val r = min(hugeCornerPx, faceRect.height() / 2f)
        buildFacePath(faceRect, r)
        canvas.drawPath(facePath, facePaint)
        canvas.drawPath(facePath, faceStroke)

        if (geometryReady) {
            canvas.save()
            canvas.clipPath(facePath)
            drawDimpleShading(canvas)
            drawContactShadow(canvas)
            canvas.restore()
            
            
            canvas.drawPath(dipCurvePath, rimHighlightPaint)
        }
    }

    private fun buildFacePath(rect: RectF, r: Float) {
        facePath.reset()
        dipCurvePath.reset()

        val left = rect.left
        val top = rect.top
        val right = rect.right
        val bottom = rect.bottom

        val dipLeft = max(left + r, dipCenterX - dipHalfWidth)
        val dipRight = min(right - r, dipCenterX + dipHalfWidth)
        val dipUsable = geometryReady && dipRight - dipLeft > dp(4f)

        facePath.moveTo(left + r, top)

        if (dipUsable) {
            facePath.lineTo(dipLeft, top)
            dipCurvePath.moveTo(dipLeft, top)

            val bottomY = top + dipDepthPx
            val leftCtrlSpan = (dipCenterX - dipLeft) * 0.55f
            val rightCtrlSpan = (dipRight - dipCenterX) * 0.55f

            
            
            facePath.cubicTo(
                dipLeft + leftCtrlSpan, top,
                dipCenterX - leftCtrlSpan, bottomY,
                dipCenterX, bottomY,
            )
            dipCurvePath.cubicTo(
                dipLeft + leftCtrlSpan, top,
                dipCenterX - leftCtrlSpan, bottomY,
                dipCenterX, bottomY,
            )
            
            facePath.cubicTo(
                dipCenterX + rightCtrlSpan, bottomY,
                dipRight - rightCtrlSpan, top,
                dipRight, top,
            )
            dipCurvePath.cubicTo(
                dipCenterX + rightCtrlSpan, bottomY,
                dipRight - rightCtrlSpan, top,
                dipRight, top,
            )
            facePath.lineTo(right - r, top)
        } else {
            facePath.lineTo(right - r, top)
        }

        cornerOval.set(right - 2 * r, top, right, top + 2 * r)
        facePath.arcTo(cornerOval, -90f, 90f, false)
        facePath.lineTo(right, bottom - r)
        cornerOval.set(right - 2 * r, bottom - 2 * r, right, bottom)
        facePath.arcTo(cornerOval, 0f, 90f, false)
        facePath.lineTo(left + r, bottom)
        cornerOval.set(left, bottom - 2 * r, left + 2 * r, bottom)
        facePath.arcTo(cornerOval, 90f, 90f, false)
        facePath.lineTo(left, top + r)
        cornerOval.set(left, top, left + 2 * r, top + 2 * r)
        facePath.arcTo(cornerOval, 180f, 90f, false)
        facePath.close()
    }

    

    private fun drawDimpleShading(canvas: Canvas) {
        val cy = dipDepthPx
        val radius = dipHalfWidth * 1.35f
        dimpleShadePaint.shader = RadialGradient(
            dipCenterX, cy, radius,
            intArrayOf(0x66000000, 0x2E000000, 0x00000000),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(dipCenterX, cy, radius, dimpleShadePaint)
    }

    


    private fun drawContactShadow(canvas: Canvas) {
        val cy = dipDepthPx * 0.9f
        val radius = dipHalfWidth * 0.85f
        contactShadowPaint.shader = RadialGradient(
            dipCenterX, cy, radius,
            intArrayOf(0x59000000, 0x24000000, 0x00000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(dipCenterX, cy, radius, contactShadowPaint)
    }
}
