package org.example.test

/**
 * Thin 1:1 mapping onto the JNI functions in llama-android.cpp.
 *
 * IMPORTANT: every function here must be called from the same single
 * background thread for the lifetime of a given model/context — llama.cpp
 * contexts are not thread-safe. Don't call these directly; go through
 * [LlamaSession], which owns that thread.
 */
object LlamaBridge {

    init {
        System.loadLibrary("llama-android")
    }

    external fun backendInit()
    external fun backendFree()
    external fun systemInfo(): String

    external fun loadModel(path: String, nGpuLayers: Int): Long
    external fun freeModel(modelPtr: Long)

    external fun newContext(modelPtr: Long, nCtx: Int, nThreads: Int): Long
    external fun freeContext(ctxPtr: Long)
    external fun kvCacheClear(ctxPtr: Long)

    external fun newBatch(nTokens: Int): Long
    external fun freeBatch(batchPtr: Long)

    external fun newSampler(temp: Float, topK: Int, topP: Float): Long
    external fun freeSampler(samplerPtr: Long)

    external fun tokenize(modelPtr: Long, text: String, addBos: Boolean): IntArray
    external fun tokenToPiece(modelPtr: Long, token: Int): String
    external fun isEndOfGeneration(modelPtr: Long, token: Int): Boolean

    external fun decodeAndSample(
        ctxPtr: Long,
        batchPtr: Long,
        samplerPtr: Long,
        tokens: IntArray,
        pastCount: Int
    ): Int
}
