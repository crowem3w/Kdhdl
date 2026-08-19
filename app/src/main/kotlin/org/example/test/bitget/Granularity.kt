package org.example.test.bitget

object Granularity {

    fun toMillisOrNull(raw: String): Long? {
        val trimmed = raw.removePrefix("candle")
        val unit = trimmed.lastOrNull { it.isLetter() } ?: return null
        val amount = trimmed.dropLast(1).toLongOrNull() ?: return null
        if (amount <= 0) return null

        val unitMillis = when (unit) {

            'm' -> 60_000L

            'H' -> 60 * 60_000L

            'D' -> 24 * 60 * 60_000L
            'W' -> 7 * 24 * 60 * 60_000L

            'M' -> 30 * 24 * 60 * 60_000L
            else -> return null
        }
        return amount * unitMillis
    }

    fun toMillis(raw: String, default: Long = 60_000L): Long = toMillisOrNull(raw) ?: default
}
