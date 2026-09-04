package org.example.syncora.bitget

import android.content.Context
import io.objectbox.BoxStore














object ObjectBoxStore {
    private var store: BoxStore? = null

    fun init(context: Context) {
        if (store != null) return
        store = MyObjectBox.builder()
            .androidContext(context.applicationContext)
            .build()
    }

    fun requireStore(): BoxStore =
        store ?: error("ObjectBoxStore.init(context) must be called before use (see SyncoraApplication.onCreate)")
}