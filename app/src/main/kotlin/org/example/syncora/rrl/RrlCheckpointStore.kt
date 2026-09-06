package org.example.syncora.rrl

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Persists [RrlAgentCheckpoint]s as a single JSON file under the device's
 * shared `Downloads/Syncora/` folder, so it's visible in the Files app and
 * easy for a user to pull off the device (back it up, move it to another
 * phone, attach it to a bug report, etc.) without needing an in-app export
 * flow.
 *
 * Every [save] overwrites the *same* file (keyed by [checkpointKey], e.g. an
 * instrument symbol or trading-mode identifier) rather than accumulating a
 * history: only the latest learned state is kept, consistent with this
 * being a live "resume where I left off" checkpoint rather than a version
 * history.
 *
 * Uses the `MediaStore.Downloads` collection rather than a raw `File` path,
 * since the app targets scoped storage (minSdk 30) and needs no runtime
 * storage permission to read/write files it created itself there.
 */
class RrlCheckpointStore(
    context: Context,
    private val checkpointKey: String,
) {
    private companion object {
        const val TAG = "RrlCheckpointStore"
        const val SUBFOLDER = "Syncora"
        const val MIME_TYPE = "application/json"
    }

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver
    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER/"
    private val displayName = "rrl_checkpoint_$checkpointKey.json"

    /** Serialises and writes [checkpoint], overwriting any previous checkpoint for this key. */
    suspend fun save(checkpoint: RrlAgentCheckpoint) {
        withContext(Dispatchers.IO) {
            try {
                val json = checkpoint.toJson().toString()
                val existing = findExistingUri()
                if (existing != null) {
                    overwrite(existing, json)
                } else {
                    insertNew(json)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save RRL checkpoint '$checkpointKey': ${e.message}")
            }
        }
    }

    /** Reads back the most recently saved checkpoint for this key, or null if none exists or it's unreadable. */
    suspend fun load(): RrlAgentCheckpoint? = withContext(Dispatchers.IO) {
        try {
            val uri = findExistingUri() ?: return@withContext null
            readCheckpoint(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load RRL checkpoint '$checkpointKey': ${e.message}")
            null
        }
    }

    /**
     * Reads and parses a checkpoint from an arbitrary [uri] -- typically one
     * returned by a Storage Access Framework picker
     * (`ActivityResultContracts.OpenDocument`) after the user chose a file
     * that isn't necessarily this store's own managed one. This is what
     * makes an exported checkpoint actually *importable*: a file renamed,
     * moved out of `Downloads/Syncora/`, shared from another app, or copied
     * over from a different device can still be loaded, as long as its JSON
     * is a valid [RrlAgentCheckpoint].
     *
     * Does not touch this store's own managed file or overwrite anything;
     * the caller (see [RrlAgentLayer.restoreFromUri]) decides whether and
     * how to apply the result. A subsequent autosave will still write to
     * this store's own canonical location as normal.
     */
    suspend fun loadFrom(uri: Uri): RrlAgentCheckpoint? = withContext(Dispatchers.IO) {
        try {
            readCheckpoint(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to import RRL checkpoint from $uri: ${e.message}")
            null
        }
    }

    private fun readCheckpoint(uri: Uri): RrlAgentCheckpoint? {
        val text = resolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        } ?: return null
        if (text.isBlank()) return null
        return RrlAgentCheckpoint.fromJson(JSONObject(text))
    }

    /** True if a checkpoint file currently exists for this key. */
    suspend fun exists(): Boolean = withContext(Dispatchers.IO) { findExistingUri() != null }

    /** Deletes the persisted checkpoint for this key, if any. Does not affect any in-memory agent state. */
    suspend fun delete() {
        withContext(Dispatchers.IO) {
            try {
                findExistingUri()?.let { resolver.delete(it, null, null) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete RRL checkpoint '$checkpointKey': ${e.message}")
            }
        }
    }

    private fun findExistingUri(): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val args = arrayOf(displayName, relativePath)
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun insertNew(json: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
            Log.w(TAG, "MediaStore refused to create checkpoint file for '$checkpointKey'")
            return
        }
        try {
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        } finally {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    private fun overwrite(uri: Uri, json: String) {
        val pending = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 1) }
        resolver.update(uri, pending, null, null)
        try {
            // "wt" truncates before writing, so a smaller new checkpoint doesn't leave trailing
            // bytes from a larger previous one.
            resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        } finally {
            pending.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, pending, null, null)
        }
    }
}
