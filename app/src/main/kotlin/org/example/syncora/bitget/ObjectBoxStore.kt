package org.example.syncora.bitget

import android.content.Context
import io.objectbox.BoxStore

/**
 * Holds the single ObjectBox [BoxStore] for the app's lifetime.
 *
 * ObjectBox recommends exactly one open Store per database, kept open for
 * as long as the app runs rather than opened/closed per use - so this is
 * initialized once from [org.example.syncora.SyncoraApplication.onCreate]
 * and read from anywhere that needs a Box (currently just
 * [ObjectBoxKlineCacheStore]).
 *
 * `MyObjectBox` is the binding class ObjectBox's annotation processor
 * generates from the `@Entity`-annotated classes in this package (see
 * [CachedKlineEntity]) - it doesn't exist in source, only after a build.
 */
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
