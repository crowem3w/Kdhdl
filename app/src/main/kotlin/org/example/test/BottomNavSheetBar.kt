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
    private val edgeTopHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#3E3F4C")
        strokeCap = Paint.Cap.ROUND
    }
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
    
    
    private val dipCreaseShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#66000000")
        strokeCap = Paint.Cap.ROUND
    }
    private val dipBounceLightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val facePath = Path()
    private val edgePath = Path()
    private val dipCurvePath = Path() 
    private val creaseShadowPath = Path()
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
        
        
        facePaint.shader = LinearGradient(
            0f, edgeThicknessPx, 0f, h.toFloat(),
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

        
        
        edgeRect.set(0f, 0f, w, h - edgeThicknessPx)
        val edgeRadius = min(hugeCornerPx, edgeRect.height() / 2f)
        
        
        
        buildOutlinePath(edgePath, edgeRect, edgeRadius, cutDip = true, dipCurveOut = null)
        canvas.drawPath(edgePath, edgePaint)

        
        
        canvas.save()
        canvas.clipRect(0f, 0f, w, edgeThicknessPx + dp(1.5f))
        canvas.drawPath(edgePath, edgeTopHighlightPaint)
        canvas.restore()

        
        faceRect.set(0f, edgeThicknessPx, w, h)
        val r = min(hugeCornerPx, faceRect.height() / 2f)
        buildOutlinePath(facePath, faceRect, r, cutDip = true, dipCurveOut = dipCurvePath)
        canvas.drawPath(facePath, facePaint)
        canvas.drawPath(facePath, faceStroke)

        if (geometryReady) {
            canvas.save()
            canvas.clipPath(facePath)
            drawDimpleShading(canvas)
            drawContactShadow(canvas)
            drawDipTrompeLoeil(canvas)
            canvas.restore()
            
            
            canvas.drawPath(dipCurvePath, rimHighlightPaint)
        }
    }

    private fun buildOutlinePath(out: Path, rect: RectF, r: Float, cutDip: Boolean, dipCurveOut: Path?) {
        out.reset()
        dipCurveOut?.reset()

        val left = rect.left
        val top = rect.top
        val right = rect.right
        val bottom = rect.bottom

        
        
        
        val localDipDepth = (dipDepthPx - top).coerceAtLeast(0f)

        val dipLeft = max(left + r, dipCenterX - dipHalfWidth)
        val dipRight = min(right - r, dipCenterX + dipHalfWidth)
        val dipUsable = cutDip && geometryReady && dipRight - dipLeft > dp(4f) && localDipDepth > dp(1f)

        out.moveTo(left + r, top)

        if (dipUsable) {
            out.lineTo(dipLeft, top)
            dipCurveOut?.moveTo(dipLeft, top)

            val bottomY = top + localDipDepth
            val leftCtrlSpan = (dipCenterX - dipLeft) * 0.55f
            val rightCtrlSpan = (dipRight - dipCenterX) * 0.55f

            
            
            out.cubicTo(
                dipLeft + leftCtrlSpan, top,
                dipCenterX - leftCtrlSpan, bottomY,
                dipCenterX, bottomY,
            )
            dipCurveOut?.cubicTo(
                dipLeft + leftCtrlSpan, top,
                dipCenterX - leftCtrlSpan, bottomY,
                dipCenterX, bottomY,
            )
            
            out.cubicTo(
                dipCenterX + rightCtrlSpan, bottomY,
                dipRight - rightCtrlSpan, top,
                dipRight, top,
            )
            dipCurveOut?.cubicTo(
                dipCenterX + rightCtrlSpan, bottomY,
                dipRight - rightCtrlSpan, top,
                dipRight, top,
            )
            out.lineTo(right - r, top)
        } else {
            out.lineTo(right - r, top)
        }

        cornerOval.set(right - 2 * r, top, right, top + 2 * r)
        out.arcTo(cornerOval, -90f, 90f, false)
        out.lineTo(right, bottom - r)
        cornerOval.set(right - 2 * r, bottom - 2 * r, right, bottom)
        out.arcTo(cornerOval, 0f, 90f, false)
        out.lineTo(left + r, bottom)
        cornerOval.set(left, bottom - 2 * r, left + 2 * r, bottom)
        out.arcTo(cornerOval, 90f, 90f, false)
        out.lineTo(left, top + r)
        cornerOval.set(left, top, left + 2 * r, top + 2 * r)
        out.arcTo(cornerOval, 180f, 90f, false)
        out.close()
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

    
    
    
    
    
    
    private fun drawDipTrompeLoeil(canvas: Canvas) {
        
        
        val creaseTop = dipDepthPx * 0.14f
        val creaseHalfWidth = dipHalfWidth * 0.86f
        val creaseDepth = dipDepthPx * 0.55f
        buildSymmetricDipCurve(creaseShadowPath, dipCenterX, creaseHalfWidth, creaseTop, creaseDepth)
        canvas.drawPath(creaseShadowPath, dipCreaseShadowPaint)

        
        
        val bounceCy = dipDepthPx * 0.92f
        val bounceRadius = dipHalfWidth * 0.5f
        dipBounceLightPaint.shader = RadialGradient(
            dipCenterX, bounceCy, bounceRadius,
            intArrayOf(0x33FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(dipCenterX, bounceCy, bounceRadius, dipBounceLightPaint)
    }

    
    
    private fun buildSymmetricDipCurve(path: Path, centerX: Float, halfWidth: Float, top: Float, depth: Float) {
        path.reset()
        val left = centerX - halfWidth
        val right = centerX + halfWidth
        val bottomY = top + depth
        val ctrlSpan = halfWidth * 0.55f
        path.moveTo(left, top)
        path.cubicTo(left + ctrlSpan, top, centerX - ctrlSpan, bottomY, centerX, bottomY)
        path.cubicTo(centerX + ctrlSpan, bottomY, right - ctrlSpan, top, right, top)
    }
}
