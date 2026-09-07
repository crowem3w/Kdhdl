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

    
    suspend fun load(): RrlAgentCheckpoint? = withContext(Dispatchers.IO) {
        try {
            val uri = findExistingUri() ?: return@withContext null
            readCheckpoint(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load RRL checkpoint '$checkpointKey': ${e.message}")
            null
        }
    }

    














    suspend fun loadFrom(uri: Uri): RrlAgentCheckpoint? = withContext(Dispatchers.IO) {
        try {
            readCheckpoint(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to import RRL checkpoint from $uri: ${e.message}")
            null
        }
    }

    
    suspend fun saveTo(uri: Uri, checkpoint: RrlAgentCheckpoint) {
        withContext(Dispatchers.IO) {
            val json = checkpoint.toJson().toString()
            resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: throw java.io.IOException("Could not open output stream for $uri")
        }
    }

    private fun readCheckpoint(uri: Uri): RrlAgentCheckpoint? {
        val text = resolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        } ?: return null
        if (text.isBlank()) return null
        return RrlAgentCheckpoint.fromJson(JSONObject(text))
    }

    
    suspend fun exists(): Boolean = withContext(Dispatchers.IO) { findExistingUri() != null }

    
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
            
            
            resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        } finally {
            pending.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, pending, null, null)
        }
    }
}