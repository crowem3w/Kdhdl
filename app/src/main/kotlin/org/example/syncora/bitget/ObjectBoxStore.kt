package org.example.syncora.bitget

import android.content.Context
import io.objectbox.BoxStore

/**
 * App-wide ObjectBox [BoxStore] singleton.
 *
 * Opening more than one [BoxStore] against the same on-disk database throws,
 * so every local ObjectBox-backed store (currently just [Ohlcv1mArchiveStore])
 * must obtain its store through [get] rather than building its own via
 * `MyObjectBox.builder()...build()` directly.
 */
object ObjectBoxStore {

    @Volatile
    private var instance: BoxStore? = null

    fun get(context: Context): BoxStore =
        instance ?: synchronized(this) {
            instance ?: MyObjectBox.builder()
                .androidContext(context.applicationContext)
                .build()
                .also { instance = it }
        }
}
