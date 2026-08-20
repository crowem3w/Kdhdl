package org.example.test.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import org.example.test.bitget.BookSide
import org.example.test.bitget.DepthDelta
import org.example.test.bitget.DepthLevel
import org.example.test.bitget.DepthSnapshot
import org.example.test.bitget.Kline
import org.example.test.bitget.LiquidityShelf
import org.example.test.bitget.LiquidityZone
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class DepthHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    fun submitDepth(snapshot: DepthSnapshot, nowMs: Long = System.currentTimeMillis()) {
        lastSnapshot = snapshot
        if (plotWidthPx <= 0 || plotHeightPx <= 0) return
        if (!priceRangeInitialized) return

        val elapsedMs = if (lastUpdateMs == 0L) Long.MAX_VALUE else nowMs - lastUpdateMs
        updateSmoothedRows(snapshot, elapsedMs)
        lastUpdateMs = nowMs
        invalidate()
    }

    fun submitDepthDelta(delta: DepthDelta, fullSnapshot: DepthSnapshot, nowMs: Long = System.currentTimeMillis()) {
        val hadIncrementalState = lastSnapshot != null
        lastSnapshot = fullSnapshot
        if (plotWidthPx <= 0 || plotHeightPx <= 0) return
        if (!priceRangeInitialized) return

        val elapsedMs = if (lastUpdateMs == 0L) Long.MAX_VALUE else nowMs - lastUpdateMs
        if (!hadIncrementalState) {

            updateSmoothedRows(fullSnapshot, elapsedMs)
            lastUpdateMs = nowMs
            invalidate()
            return
        }

        ensureRowCapacity()
        ensureRawBucketCapacity()
        applyDeltaToRawBuckets(delta)
        blendDirtyRows(elapsedMs)
        lastUpdateMs = nowMs
        invalidate()
    }

    fun syncToCandles(candleWindow: List<Kline>, barDurationMillis: Long) {
        if (candleWindow.isEmpty()) return
        lastCandleWindow = candleWindow
        lastBarDurationMillis = barDurationMillis

        if (interactiveOverrideActive) return

        val range = computeUnionPriceRange(candleWindow) ?: return
        applyPriceRange(range.minPrice, range.maxPrice)
        invalidate()
    }

    fun submitLiquidityZones(zones: List<LiquidityZone>) {
        latestLiquidityZones = zones
    }

    fun submitLiquidityShelves(shelves: List<LiquidityShelf>) {
        latestLiquidityShelves = shelves
    }

    fun setInteractiveOverride(range: ChartPriceRange?) {
        interactiveOverrideActive = range != null
        if (range != null) {
            if (range.maxPrice > range.minPrice) setPriceRange(range.minPrice, range.maxPrice)
        } else {
            val autoRange = ChartPriceRange.from(lastCandleWindow) ?: depthPriceRange(lastSnapshot)
            if (autoRange != null) applyPriceRange(autoRange.minPrice, autoRange.maxPrice)
        }
        invalidate()
    }

    private var interactiveOverrideActive = false

    fun reset() {
        lastUpdateMs = 0L
        priceRangeInitialized = false
        runningMaxVolume = 0f
        bidRowVolume = FloatArray(0)
        askRowVolume = FloatArray(0)
        bidRawLevels.clear()
        askRawLevels.clear()
        rawBidBucket = FloatArray(0)
        rawAskBucket = FloatArray(0)
        dirtyRows.clear()
        rowSpanPrice = 0.0
        lastSnapshot = null
        lastCandleWindow = emptyList()
        lastBarDurationMillis = 0L
        interactiveOverrideActive = false
        latestLiquidityZones = emptyList()
        latestLiquidityShelves = emptyList()
        invalidate()
    }

    private val midPriceColor = Color.parseColor("#787B86")

    private val overlayAlpha = 185

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private val priceAxisWidth get() = ChartLayoutMetrics.priceAxisWidthPx(resources)
    private val timeAxisHeight get() = ChartLayoutMetrics.timeAxisHeightPx(resources)

    private val targetRowHeightPx get() = dp(3f)
    private val minRowCount = 48
    private val maxRowCount = 220

    private var bidRowVolume: FloatArray = FloatArray(0)

    private var askRowVolume: FloatArray = FloatArray(0)

    private val smoothingTauMs = 350f

    private val negligibleRowVolume = 1e-4f

    private fun rowCountFor(heightPx: Int): Int {
        if (heightPx <= 0) return 0
        val raw = (heightPx / targetRowHeightPx).toInt()
        return raw.coerceIn(minRowCount, maxRowCount)
    }

    private fun ensureRowCapacity() {
        val target = rowCountFor(plotHeightPx)
        if (bidRowVolume.size != target) bidRowVolume = FloatArray(target)
        if (askRowVolume.size != target) askRowVolume = FloatArray(target)
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val nodeCornerRadiusPx = dp(2f)
    private val nodeEdgeFadeWidthPx = dp(14f)

    private val midPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = midPriceColor
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }

    private val reusableRect = RectF()

    private var plotWidthPx = 0
    private var plotHeightPx = 0

    private var lastUpdateMs = 0L
    private var lastSnapshot: DepthSnapshot? = null

    private val redrawIntervalMs = 200L
    private val redrawTicker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, redrawIntervalMs)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(redrawTicker, redrawIntervalMs)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(redrawTicker)
        super.onDetachedFromWindow()
    }

    private var bidRawLevels = HashMap<Double, Float>()
    private var askRawLevels = HashMap<Double, Float>()

    private var latestLiquidityZones: List<LiquidityZone> = emptyList()

    private var latestLiquidityShelves: List<LiquidityShelf> = emptyList()

    private var rawBidBucket: FloatArray = FloatArray(0)
    private var rawAskBucket: FloatArray = FloatArray(0)

    private val dirtyRows = HashSet<Int>()

    private fun ensureRawBucketCapacity() {
        val target = bidRowVolume.size
        if (rawBidBucket.size != target) rawBidBucket = FloatArray(target)
        if (rawAskBucket.size != target) rawAskBucket = FloatArray(target)
    }

    private fun rebuildRawBucketsFromLevels() {
        ensureRawBucketCapacity()
        rawBidBucket.fill(0f)
        rawAskBucket.fill(0f)
        for ((price, size) in bidRawLevels) {
            rowIndexForPrice(price)?.let { row -> if (row < rawBidBucket.size) rawBidBucket[row] += size }
        }
        for ((price, size) in askRawLevels) {
            rowIndexForPrice(price)?.let { row -> if (row < rawAskBucket.size) rawAskBucket[row] += size }
        }
    }

    private var rowSpanPrice: Double = 0.0
    private val rowSpanHysteresisFraction = 0.15

    private fun updateRowSpanPrice(count: Int) {
        if (count <= 0) return
        val spanPrice = maxPrice - minPrice
        if (spanPrice <= 0.0) return
        val candidate = spanPrice / count
        if (rowSpanPrice <= 0.0) {
            rowSpanPrice = candidate
            return
        }

        val ratio = candidate / rowSpanPrice
        if (ratio < (1.0 - rowSpanHysteresisFraction) || ratio > (1.0 + rowSpanHysteresisFraction)) {
            rowSpanPrice = candidate
        }
    }

    private fun rowIndexForPrice(price: Double): Int? {
        val count = bidRowVolume.size
        if (count == 0 || !priceRangeInitialized || rowSpanPrice <= 0.0) return null
        if (price < minPrice || price > maxPrice) return null

        val anchorMax = ceil(maxPrice / rowSpanPrice) * rowSpanPrice
        var row = ((anchorMax - price) / rowSpanPrice).toInt()
        if (row < 0) row = 0
        if (row >= count) row = count - 1
        return row
    }

    private fun applyDeltaToRawBuckets(delta: DepthDelta) {
        dirtyRows.clear()
        for (change in delta.changes) {
            val levels = if (change.side == BookSide.BID) bidRawLevels else askRawLevels
            val bucket = if (change.side == BookSide.BID) rawBidBucket else rawAskBucket
            val oldSize = levels[change.price] ?: 0f
            val newSize = change.size.toFloat()
            if (change.size <= 0.0) levels.remove(change.price) else levels[change.price] = newSize

            val row = rowIndexForPrice(change.price) ?: continue
            if (row < bucket.size) {
                bucket[row] = (bucket[row] - oldSize + newSize).coerceAtLeast(0f)
                dirtyRows.add(row)
            }
        }
    }

    private fun blendDirtyRows(elapsedMs: Long) {
        if (rawBidBucket.isEmpty()) return
        var rawPeak = 0f
        for (row in dirtyRows) {
            rawPeak = max(rawPeak, max(rawBidBucket[row], rawAskBucket[row]))
        }
        runningMaxVolume = max(runningMaxVolume * maxVolumeDecayPerUpdate, rawPeak)

        val alpha = smoothingAlpha(elapsedMs)
        for (row in dirtyRows) {
            bidRowVolume[row] += (rawBidBucket[row] - bidRowVolume[row]) * alpha
            askRowVolume[row] += (rawAskBucket[row] - askRowVolume[row]) * alpha
        }
    }

    private var lastCandleWindow: List<Kline> = emptyList()
    private var lastBarDurationMillis: Long = 0L

    private var minPrice = 0.0
    private var maxPrice = 0.0
    private var priceRangeInitialized = false

    private val recenterMarginFraction = 0.12

    private val volumeCutoffFraction = 0.04f

    private val quantileCeilingFraction = 0.72f

    private val logContrastSteepness = 8.0f
    private val logContrastDenominator = ln(1f + logContrastSteepness)

    private val minAlphaScale = 0.30f

    private val minPaintedDensity = 0.30f

    private fun densityFor(rowVolume: Float, colorCeiling: Float): Float? {
        if (colorCeiling <= 0f || rowVolume <= negligibleRowVolume) return null
        val ceiling = colorCeiling * quantileCeilingFraction
        if (ceiling <= 0f) return null
        val raw = (rowVolume / ceiling).coerceIn(0f, 1f)
        val density = (ln(1f + logContrastSteepness * raw) / logContrastDenominator).coerceIn(0f, 1f)
        if (density < minPaintedDensity) return null
        return density
    }

    private var runningMaxVolume = 0f
    private val maxVolumeDecayPerUpdate = 0.995f

    private val combinedCeiling: Float get() = if (runningMaxVolume > 0f) runningMaxVolume else 1f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewportWidthPx = max(0, w)
        val chartBottom = h - timeAxisHeight
        plotWidthPx = max(0, (w - priceAxisWidth).toInt())
        plotHeightPx = max(0, ChartLayoutMetrics.priceAreaHeightPx(chartBottom).toInt())
        if (plotWidthPx <= 0 || plotHeightPx <= 0) return

        ensureRowCapacity()
        if (priceRangeInitialized) updateRowSpanPrice(bidRowVolume.size)
        bidRowVolume.fill(0f)
        askRowVolume.fill(0f)

        val candleWindow = lastCandleWindow
        if (candleWindow.isNotEmpty()) {
            syncToCandles(candleWindow, lastBarDurationMillis)
        }
        if (priceRangeInitialized) {
            lastSnapshot?.let {
                updateSmoothedRows(it, Long.MAX_VALUE)
                lastUpdateMs = System.currentTimeMillis()
            }
        }

        rebuildRawBucketsFromLevels()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!priceRangeInitialized || plotWidthPx <= 0 || plotHeightPx <= 0) return

        drawHistogramBars(canvas)
        drawFarBookLevels(canvas)
        drawMidPriceLine(canvas)
        drawDepthLadder(canvas)
    }

    private var viewportWidthPx = 0

    private val neutralLowColor = Color.parseColor("#9C7A70")
    private val bidMidColor = Color.parseColor("#61D983")
    private val bidPeakColor = Color.parseColor("#00FF7F")
    private val askMidColor = Color.parseColor("#C24070")
    private val askPeakColor = Color.parseColor("#FF007F")

    private fun lerpRgb(from: Int, to: Int, t: Float, alpha: Int = 255): Int {
        val ct = t.coerceIn(0f, 1f)
        val r = Color.red(from) + ((Color.red(to) - Color.red(from)) * ct).toInt()
        val g = Color.green(from) + ((Color.green(to) - Color.green(from)) * ct).toInt()
        val b = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * ct).toInt()
        return Color.argb(alpha, r, g, b)
    }

    private fun colorForDensity(isBid: Boolean, density: Float, alpha: Int = 255): Int {
        val d = density.coerceIn(0f, 1f)
        val midColor = if (isBid) bidMidColor else askMidColor
        val peakColor = if (isBid) bidPeakColor else askPeakColor
        return if (d <= 0.5f) {
            lerpRgb(neutralLowColor, midColor, d / 0.5f, alpha)
        } else {
            lerpRgb(midColor, peakColor, (d - 0.5f) / 0.5f, alpha)
        }
    }

    private val nearLevelMinBarWidthFraction = 0.03f

    private fun drawHistogramBars(canvas: Canvas) {
        val bids = bidRowVolume
        val asks = askRowVolume
        if (bids.isEmpty() || rowSpanPrice <= 0.0 || maxPrice <= minPrice) return
        val ceiling = combinedCeiling

        val anchorMax = ceil(maxPrice / rowSpanPrice) * rowSpanPrice
        val pxPerPrice = plotHeightPx / (maxPrice - minPrice)
        val rowHeightPx = (rowSpanPrice * pxPerPrice).toFloat()
        val gridTopPx = ((maxPrice - anchorMax) * pxPerPrice).toFloat()

        val maxBarWidthPx = plotWidthPx.toFloat()
        val minBarWidthPx = maxBarWidthPx * nearLevelMinBarWidthFraction

        for (row in bids.indices) {
            val density = densityFor(blendedCombinedVolume(bids, asks, row), colorCeiling = ceiling) ?: continue

            val barWidthPx = minBarWidthPx + (maxBarWidthPx - minBarWidthPx) * density

            val top = gridTopPx + row * rowHeightPx
            val bottom = top + rowHeightPx
            val isBid = bids[row] >= asks[row]
            drawHeatmapNode(canvas, top, bottom, barWidthPx, density, isBid)
        }
    }

    private fun drawHeatmapNode(canvas: Canvas, top: Float, bottom: Float, barWidthPx: Float, density: Float, isBid: Boolean) {
        if (barWidthPx <= 0f) return
        val rightEdge = plotWidthPx.toFloat()
        val left = rightEdge - barWidthPx
        reusableRect.set(left, top, rightEdge, bottom)

        barPaint.alpha = alphaFor(density)
        val fadeWidth = min(nodeEdgeFadeWidthPx, barWidthPx)
        val fadeFraction = (fadeWidth / barWidthPx).coerceIn(0.001f, 0.999f)
        val transparentNeutral = Color.argb(
            0, Color.red(neutralLowColor), Color.green(neutralLowColor), Color.blue(neutralLowColor),
        )
        val peakColor = colorForDensity(isBid, density)
        barPaint.shader = LinearGradient(
            left, 0f, rightEdge, 0f,
            intArrayOf(transparentNeutral, neutralLowColor, peakColor),
            floatArrayOf(0f, fadeFraction, 1f),
            Shader.TileMode.CLAMP,
        )
        val cornerRadius = min(nodeCornerRadiusPx, min(bottom - top, barWidthPx) / 2f)
        canvas.drawRoundRect(reusableRect, cornerRadius, cornerRadius, barPaint)
    }

    private val verticalBlendWeight = 0.16f

    private fun blendedCombinedVolume(bids: FloatArray, asks: FloatArray, row: Int): Float {
        val center = bids[row] + asks[row]
        val above = if (row > 0) bids[row - 1] + asks[row - 1] else center
        val below = if (row < bids.size - 1) bids[row + 1] + asks[row + 1] else center
        return center * (1f - 2f * verticalBlendWeight) + (above + below) * verticalBlendWeight
    }

    private fun alphaFor(density: Float): Int =
        (overlayAlpha * (minAlphaScale + (1f - minAlphaScale) * density)).toInt().coerceIn(0, 255)

    private fun priceToY(price: Double): Float? {
        if (!priceRangeInitialized || maxPrice <= minPrice) return null
        if (price < minPrice || price > maxPrice) return null
        val ratio = ((maxPrice - price) / (maxPrice - minPrice)).toFloat()
        return ratio * plotHeightPx
    }

    private fun applyPriceRange(targetMin: Double, targetMax: Double) {
        if (!priceRangeInitialized || maxPrice <= minPrice) {
            setPriceRange(targetMin, targetMax)
            return
        }
        val span = maxPrice - minPrice
        val margin = span * recenterMarginFraction
        val wouldCrop = targetMin < minPrice || targetMax > maxPrice
        val driftedFar = (targetMin - minPrice) > margin || (maxPrice - targetMax) > margin
        if (wouldCrop || driftedFar) {
            setPriceRange(targetMin, targetMax)
        }
    }

    private fun setPriceRange(newMin: Double, newMax: Double) {
        minPrice = newMin
        maxPrice = newMax
        priceRangeInitialized = true

        ensureRowCapacity()
        updateRowSpanPrice(bidRowVolume.size)
        bidRowVolume.fill(0f)
        askRowVolume.fill(0f)

        if (plotWidthPx > 0 && plotHeightPx > 0) {
            lastSnapshot?.let {
                val nowMs = System.currentTimeMillis()
                updateSmoothedRows(it, Long.MAX_VALUE)
                lastUpdateMs = nowMs
            }
        }

        rebuildRawBucketsFromLevels()
        invalidate()
    }

    private fun bestPricesOf(snapshot: DepthSnapshot): Pair<Double, Double>? {
        val bestBid = snapshot.bids.firstOrNull()?.price ?: return null
        val bestAsk = snapshot.asks.firstOrNull()?.price ?: return null
        return bestBid to bestAsk
    }

    private val depthPriceRangePaddingFraction = 0.08

    private fun depthPriceRange(snapshot: DepthSnapshot?): ChartPriceRange? {
        if (snapshot == null) return null
        val (bestBid, bestAsk) = bestPricesOf(snapshot) ?: return null
        val mid = (bestBid + bestAsk) / 2.0

        var minBookPrice = bestBid
        var maxBookPrice = bestAsk
        for (level in snapshot.bids) if (level.price < minBookPrice) minBookPrice = level.price
        for (level in snapshot.asks) if (level.price > maxBookPrice) maxBookPrice = level.price

        var halfSpan = max(mid - minBookPrice, maxBookPrice - mid)
        if (halfSpan <= 0.0) halfSpan = mid * 0.01
        val paddedHalfSpan = halfSpan * (1.0 + depthPriceRangePaddingFraction)
        return ChartPriceRange(mid - paddedHalfSpan, mid + paddedHalfSpan)
    }

    private val unionRangeBoundMultiplier = 2.5

    private fun computeUnionPriceRange(candleWindow: List<Kline>): ChartPriceRange? {
        val base = ChartPriceRange.from(candleWindow) ?: return null
        val span = base.maxPrice - base.minPrice
        if (span <= 0.0) return base

        val threshold = combinedCeiling * quantileCeilingFraction
        if (threshold <= 0f) return base

        val maxExpansionDistance = span * unionRangeBoundMultiplier
        var expandedMin = base.minPrice
        var expandedMax = base.maxPrice

        fun consider(price: Double, volume: Float) {
            if (volume < threshold) return
            if (price < base.minPrice) {
                val distance = base.minPrice - price
                if (distance <= maxExpansionDistance && price < expandedMin) expandedMin = price
            } else if (price > base.maxPrice) {
                val distance = price - base.maxPrice
                if (distance <= maxExpansionDistance && price > expandedMax) expandedMax = price
            }
        }

        for ((price, volume) in combinedVolumeByPrice()) consider(price, volume)

        return ChartPriceRange(expandedMin, expandedMax)
    }

    private fun combinedVolumeByPrice(): Map<Double, Float> {
        val zones = latestLiquidityZones
        val result = HashMap<Double, Float>()
        if (zones.isNotEmpty()) {
            for (zone in zones) result[zone.price] = (result[zone.price] ?: 0f) + zone.volume.toFloat()
        } else {
            for ((price, size) in bidRawLevels) result[price] = (result[price] ?: 0f) + size
            for ((price, size) in askRawLevels) result[price] = (result[price] ?: 0f) + size
        }
        return result
    }

    private fun updateSmoothedRows(snapshot: DepthSnapshot, elapsedMs: Long) {
        ensureRowCapacity()
        val rawBids = bucketRawRowVolumes(snapshot.bids)
        val rawAsks = bucketRawRowVolumes(snapshot.asks)
        if (rawBids.isEmpty()) return

        val rawPeak = max(rawBids.maxOrNull() ?: 0f, rawAsks.maxOrNull() ?: 0f)
        runningMaxVolume = max(runningMaxVolume * maxVolumeDecayPerUpdate, rawPeak)

        val alpha = smoothingAlpha(elapsedMs)
        for (i in rawBids.indices) {
            bidRowVolume[i] += (rawBids[i] - bidRowVolume[i]) * alpha
            askRowVolume[i] += (rawAsks[i] - askRowVolume[i]) * alpha
        }
    }

    private fun smoothingAlpha(elapsedMs: Long): Float {
        if (elapsedMs <= 0L || elapsedMs == Long.MAX_VALUE) return 1f
        val ratio = elapsedMs / smoothingTauMs
        return (1f - kotlin.math.exp(-ratio)).coerceIn(0f, 1f)
    }

    private fun bucketRawRowVolumes(levels: List<DepthLevel>): FloatArray {
        val count = bidRowVolume.size
        val raw = FloatArray(count)
        if (count == 0) return raw
        for (level in levels) {
            if (level.size <= 0.0) continue
            val row = rowIndexForPrice(level.price) ?: continue
            raw[row] += level.size.toFloat()
        }
        return raw
    }

    private fun drawMidPriceLine(canvas: Canvas) {
        val snapshot = lastSnapshot ?: return
        val (bestBid, bestAsk) = bestPricesOf(snapshot) ?: return
        val mid = (bestBid + bestAsk) / 2.0
        val y = priceToY(mid) ?: return
        canvas.drawLine(0f, y, plotWidthPx.toFloat(), y, midPricePaint)
    }

    private val ladderWidthPx get() = dp(40f)

    private val ladderBackgroundPaint = Paint().apply {
        color = Color.argb(90, 10, 12, 18)
    }
    private val ladderBidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22C55E")
        alpha = 170
        style = Paint.Style.STROKE

        strokeWidth = 0f
    }
    private val ladderAskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F97316")
        alpha = 170
        style = Paint.Style.STROKE
        strokeWidth = 0f
    }
    private val ladderLinePath = android.graphics.Path()

    private fun drawDepthLadder(canvas: Canvas) {
        val snapshot = lastSnapshot ?: return
        if (!priceRangeInitialized || plotWidthPx <= 0 || plotHeightPx <= 0) return

        val ladderWidth = ladderWidthPx.coerceAtMost(plotWidthPx.toFloat())
        val right = plotWidthPx.toFloat()
        val left = right - ladderWidth

        reusableRect.set(left, 0f, right, plotHeightPx.toFloat())
        canvas.drawRect(reusableRect, ladderBackgroundPaint)

        var maxLevelSize = 0f
        for (level in snapshot.bids) if (level.price in minPrice..maxPrice) maxLevelSize = max(maxLevelSize, level.size.toFloat())
        for (level in snapshot.asks) if (level.price in minPrice..maxPrice) maxLevelSize = max(maxLevelSize, level.size.toFloat())
        if (maxLevelSize <= 0f) return

        drawLadderLineSide(canvas, snapshot.bids, maxLevelSize, left, right, ladderBidPaint)
        drawLadderLineSide(canvas, snapshot.asks, maxLevelSize, left, right, ladderAskPaint)
    }

    private fun drawLadderLineSide(
        canvas: Canvas,
        levels: List<DepthLevel>,
        maxLevelSize: Float,
        left: Float,
        right: Float,
        paint: Paint,
    ) {

        val path = ladderLinePath
        path.rewind()
        var hasSegments = false

        for (level in levels) {
            val y = priceToY(level.price) ?: continue
            val fraction = (level.size.toFloat() / maxLevelSize).coerceIn(0f, 1f)
            if (fraction < volumeCutoffFraction) continue
            val x = right - fraction * (right - left)

            path.moveTo(right, y)
            path.lineTo(x, y)
            hasSegments = true
        }

        if (hasSegments) canvas.drawPath(path, paint)
    }

    private val farLevelRowHeightPx get() = dp(3f)
    private val farLevelMinBarWidthFraction = 0.03f

    private fun drawFarBookLevels(canvas: Canvas) {
        if (!priceRangeInitialized || plotWidthPx <= 0 || plotHeightPx <= 0) return
        if (maxPrice <= minPrice) return

        val ceiling = combinedCeiling
        if (ceiling <= 0f) return

        val levels = farBookLevelsByPrice()
        if (levels.isEmpty()) return

        val pxPerPrice = plotHeightPx / (maxPrice - minPrice)
        val halfRow = farLevelRowHeightPx / 2f
        val maxBarWidthPx = plotWidthPx.toFloat()
        val minBarWidthPx = maxBarWidthPx * farLevelMinBarWidthFraction

        for ((price, level) in levels) {

            if (price in minPrice..maxPrice) continue

            val density = densityFor(level.volume, ceiling) ?: continue
            val y = ((maxPrice - price) * pxPerPrice).toFloat()
            val top = y - halfRow
            val bottom = y + halfRow
            val barWidthPx = minBarWidthPx + (maxBarWidthPx - minBarWidthPx) * density
            drawHeatmapNode(canvas, top, bottom, barWidthPx, density, level.isBid)
        }
    }

    private class FarLevel(val volume: Float, val isBid: Boolean)

    private fun farBookLevelsByPrice(): Map<Double, FarLevel> {
        val zones = latestLiquidityZones
        if (zones.isNotEmpty()) {
            val result = HashMap<Double, FarLevel>(zones.size)
            for (zone in zones) {
                val existing = result[zone.price]
                val volume = (existing?.volume ?: 0f) + zone.volume.toFloat()
                val isBid = if (existing != null) existing.isBid else zone.side == BookSide.BID
                result[zone.price] = FarLevel(volume, isBid)
            }
            return result
        }

        val result = HashMap<Double, FarLevel>(bidRawLevels.size + askRawLevels.size)
        for ((price, size) in bidRawLevels) {
            val existing = result[price]
            result[price] = FarLevel((existing?.volume ?: 0f) + size, isBid = true)
        }
        for ((price, size) in askRawLevels) {
            val existing = result[price]
            val volume = (existing?.volume ?: 0f) + size
            val isBid = existing?.isBid ?: false
            result[price] = FarLevel(volume, isBid)
        }
        return result
    }
}
