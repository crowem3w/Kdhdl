package org.example.syncora.storage

import android.content.Context
import io.objectbox.BoxStore

/**
 * Holds the single [BoxStore] for the process. ObjectBox recommends exactly
 * one BoxStore per app, opened once and kept for the process lifetime -
 * mirrors how [org.example.syncora.SyncoraApplication] already treats its
 * other app-scoped singletons (pipelines, credential stores, etc).
 *
 * [init] must be called once, from [org.example.syncora.SyncoraApplication.onCreate],
 * before anything touches [boxStore].
 */
object ObjectBoxStore {

    lateinit var boxStore: BoxStore
        private set

    fun init(context: Context) {
        if (::boxStore.isInitialized) return
        boxStore = MyObjectBox.builder()
            .androidContext(context.applicationContext)
            .build()
    }
}
