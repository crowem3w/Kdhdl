package org.example.test.bitget

class KlineBuffer(private val capacity: Int = 100) {
    private val lock = Any()
    private val deque = ArrayDeque<Kline>(capacity)

    val size: Int
        get() = synchronized(lock) { deque.size }

    fun applyUpdates(updates: List<Kline>): List<Kline> {
        synchronized(lock) {
            for (candle in updates) {
                upsertLocked(candle)
            }
            return deque.toList()
        }
    }

    fun snapshot(): List<Kline> = synchronized(lock) { deque.toList() }

    fun clear() = synchronized(lock) { deque.clear() }

    private fun upsertLocked(candle: Kline) {
        val lastIndex = deque.size - 1
        when {
            deque.isEmpty() -> deque.addLast(candle)
            candle.startTime == deque[lastIndex].startTime -> deque[lastIndex] = candle
            candle.startTime > deque[lastIndex].startTime -> {
                deque.addLast(candle)
                if (deque.size > capacity) deque.removeFirst()
            }
            else -> {

                val idx = deque.indexOfLast { it.startTime == candle.startTime }
                if (idx >= 0) deque[idx] = candle

            }
        }
    }
}
