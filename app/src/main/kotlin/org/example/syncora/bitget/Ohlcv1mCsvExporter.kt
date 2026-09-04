package org.example.syncora.bitget

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Result of a completed export - see [Ohlcv1mCsvExporter.export]. */
data class CsvExportResult(val displayName: String, val rowCount: Int)

/**
 * Writes the locally archived 1m OHLCV history to a CSV file in the device's
 * public Downloads folder via [MediaStore], so the file shows up in the
 * system Downloads app / file browser like any other user file.
 *
 * Uses the [MediaStore.Downloads] collection rather than a raw file path, so
 * no storage permission is needed on API 29+ (this app's minSdk is 30).
 */
class Ohlcv1mCsvExporter(context: Context) {

    private val appContext = context.applicationContext

    private companion object {
        val ROW_TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val FILE_STAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        const val CSV_HEADER =
            "start_time_utc,start_time_epoch_ms,open,high,low,close,base_volume,quote_volume,usdt_volume"
    }

    /**
     * Exports [candles] (expected oldest-first, as returned by
     * [Ohlcv1mArchiveStore.exportAllOrderedByTime]) as a CSV file named
     * `syncora_ohlcv_1m_<symbol>_<timestamp>.csv` in Downloads.
     */
    suspend fun export(symbol: String, candles: List<Kline>): CsvExportResult = withContext(Dispatchers.IO) {
        val fileName = "syncora_ohlcv_1m_${symbol}_${FILE_STAMP_FORMAT.format(Date())}.csv"
        val resolver = appContext.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create $fileName in Downloads")

        val stream = resolver.openOutputStream(uri)
            ?: throw IllegalStateException("Could not open an output stream for $fileName")

        stream.bufferedWriter().use { writer ->
            writer.write(CSV_HEADER)
            writer.newLine()
            for (candle in candles) {
                writer.write(candle.toCsvRow())
                writer.newLine()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        CsvExportResult(displayName = fileName, rowCount = candles.size)
    }

    private fun Kline.toCsvRow(): String = listOf(
        ROW_TIMESTAMP_FORMAT.format(Date(startTime)),
        startTime,
        open,
        high,
        low,
        close,
        baseVolume,
        quoteVolume,
        usdtVolume,
    ).joinToString(",")
}
