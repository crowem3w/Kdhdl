package org.example.test.bitget

import java.util.TreeMap
import java.util.zip.CRC32

data class DepthLevel(val price: Double, val size: Double)

data class LevelChange(val side: BookSide, val price: Double, val size: Double)

data class DepthDelta(val changes: List<LevelChange>)

data class DepthSnapshot(
    val bids: List<DepthLevel>,
    val asks: List<DepthLevel>,
    val lastUpdateMs: Long,
    val lastSeq: Long,
)

class DepthMatrix(private val depthLimit: Int = 200) {
    private val lock = Any()

    private val bids = TreeMap<Double, Double>(compareByDescending { it })
    private val asks = TreeMap<Double, Double>()

    private var lastUpdateMs: Long = 0L
    private var lastSeq: Long = -1L
    private var primed = false

    val isPrimed: Boolean get() = synchronized(lock) { primed }

    private val safetyCapPerSide = (depthLimit * 25).coerceAtLeast(2000)

    fun applySnapshot(update: DepthUpdate) {
        synchronized(lock) {
            bids.clear()
            asks.clear()
            for (level in update.bids) upsertLocked(bids, level)
            for (level in update.asks) upsertLocked(asks, level)
            lastUpdateMs = update.timestampMs
            lastSeq = update.seq
            primed = true
        }
    }

    fun applyUpdate(update: DepthUpdate): DepthDelta? {
        synchronized(lock) {
            if (!primed) return null
            for (level in update.bids) upsertLocked(bids, level)
            for (level in update.asks) upsertLocked(asks, level)
            val evictedBids = safetyTrimLocked(bids)
            val evictedAsks = safetyTrimLocked(asks)
            lastUpdateMs = update.timestampMs
            lastSeq = update.seq

            val changes = ArrayList<LevelChange>(update.bids.size + update.asks.size + evictedBids.size + evictedAsks.size)
            for (level in update.bids) changes.add(LevelChange(BookSide.BID, level.price, level.size))
            for (level in update.asks) changes.add(LevelChange(BookSide.ASK, level.price, level.size))

            for (price in evictedBids) changes.add(LevelChange(BookSide.BID, price, 0.0))
            for (price in evictedAsks) changes.add(LevelChange(BookSide.ASK, price, 0.0))
            return DepthDelta(changes)
        }
    }

    fun mergeRestLevels(bidLevels: List<OrderBookLevel>, askLevels: List<OrderBookLevel>): DepthDelta? {
        synchronized(lock) {
            if (!primed) return null
            val changes = ArrayList<LevelChange>(bidLevels.size + askLevels.size)
            for (level in bidLevels) {
                upsertLocked(bids, level)
                changes.add(LevelChange(BookSide.BID, level.price, level.size))
            }
            for (level in askLevels) {
                upsertLocked(asks, level)
                changes.add(LevelChange(BookSide.ASK, level.price, level.size))
            }
            val evictedBids = safetyTrimLocked(bids)
            val evictedAsks = safetyTrimLocked(asks)
            for (price in evictedBids) changes.add(LevelChange(BookSide.BID, price, 0.0))
            for (price in evictedAsks) changes.add(LevelChange(BookSide.ASK, price, 0.0))
            if (changes.isEmpty()) return null
            return DepthDelta(changes)
        }
    }

    private fun upsertLocked(book: TreeMap<Double, Double>, level: OrderBookLevel) {
        if (level.size == 0.0) {
            book.remove(level.price)
        } else {
            book[level.price] = level.size
        }
    }

    private fun safetyTrimLocked(book: TreeMap<Double, Double>): List<Double> {
        val overflow = book.size - safetyCapPerSide
        if (overflow <= 0) return emptyList()

        val farEntries = book.entries.asSequence().drop(safetyCapPerSide).toList()
        val evicted = ArrayList<Double>()
        for (entry in farEntries.sortedBy { it.value }.take(overflow)) {
            book.remove(entry.key)
            evicted.add(entry.key)
        }
        return evicted
    }

    fun snapshot(levels: Int? = null): DepthSnapshot = synchronized(lock) {
        val bidEntries = if (levels != null) bids.entries.asSequence().take(levels) else bids.entries.asSequence()
        val askEntries = if (levels != null) asks.entries.asSequence().take(levels) else asks.entries.asSequence()
        DepthSnapshot(
            bids = bidEntries.map { DepthLevel(it.key, it.value) }.toList(),
            asks = askEntries.map { DepthLevel(it.key, it.value) }.toList(),
            lastUpdateMs = lastUpdateMs,
            lastSeq = lastSeq,
        )
    }

    fun clear() = synchronized(lock) {
        bids.clear()
        asks.clear()
        lastUpdateMs = 0L
        lastSeq = -1L
        primed = false
    }

    fun verifyChecksum(expected: Long): Boolean {
        if (expected == 0L) return true
        val computed = synchronized(lock) {
            val sb = StringBuilder()
            val bidIt = bids.entries.iterator()
            val askIt = asks.entries.iterator()
            var i = 0
            while (i < 25 && (bidIt.hasNext() || askIt.hasNext())) {
                if (bidIt.hasNext()) {
                    val (price, size) = bidIt.next()
                    sb.append(formatChecksumNumber(price)).append(':').append(formatChecksumNumber(size)).append(':')
                }
                if (askIt.hasNext()) {
                    val (price, size) = askIt.next()
                    sb.append(formatChecksumNumber(price)).append(':').append(formatChecksumNumber(size)).append(':')
                }
                i++
            }
            if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
            val crc = CRC32()
            crc.update(sb.toString().toByteArray(Charsets.US_ASCII))
            crc.value.toInt().toLong()
        }
        return computed == expected
    }

    private fun formatChecksumNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
