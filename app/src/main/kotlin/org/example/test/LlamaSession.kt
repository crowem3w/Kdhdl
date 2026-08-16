package org.example.test

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class LoadedModelInfo(val contextSize: Int, val gpuLayers: Int, val backendInfo: String)

/**
 * Owns one llama.cpp model + context for the app's lifetime and funnels
 * every native call through a single dedicated thread, since a llama.cpp
 * context must only ever be touched from one thread at a time.
 */
class LlamaSession {

    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "llama-inference") }
            .asCoroutineDispatcher()

    private var modelPtr: Long = 0
    private var ctxPtr: Long = 0
    private var batchPtr: Long = 0

    private val nCtx = 4096

    @Volatile
    private var cancelled = false

    suspend fun init() = withContext(dispatcher) {
        LlamaBridge.backendInit()
    }

    /**
     * Loads a GGUF model from [modelPath]. [gpuLayers] = 999 tries to
     * offload the whole model to the Vulkan backend; pass 0 to force
     * CPU-only (useful if a device's GPU driver crashes on a given model).
     */
    suspend fun loadModel(modelPath: String, gpuLayers: Int = 999): LoadedModelInfo =
        withContext(dispatcher) {
            unloadLocked()

            modelPtr = LlamaBridge.loadModel(modelPath, gpuLayers)
            require(modelPtr != 0L) { "Failed to load model — is the GGUF file valid/complete?" }

            val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
            ctxPtr = LlamaBridge.newContext(modelPtr, nCtx, threads)
            if (ctxPtr == 0L) {
                LlamaBridge.freeModel(modelPtr)
                modelPtr = 0
                error("Failed to create inference context (model may need more RAM than is free)")
            }

            batchPtr = LlamaBridge.newBatch(nCtx)

            LoadedModelInfo(
                contextSize = nCtx,
                gpuLayers = gpuLayers,
                backendInfo = LlamaBridge.systemInfo()
            )
        }

    suspend fun unload() = withContext(dispatcher) { unloadLocked() }

    private fun unloadLocked() {
        if (batchPtr != 0L) { LlamaBridge.freeBatch(batchPtr); batchPtr = 0 }
        if (ctxPtr != 0L) { LlamaBridge.freeContext(ctxPtr); ctxPtr = 0 }
        if (modelPtr != 0L) { LlamaBridge.freeModel(modelPtr); modelPtr = 0 }
    }

    fun cancelGeneration() { cancelled = true }

    /**
     * Streams the model's completion for [prompt] one text piece at a
     * time. Collect this from a lifecycle-aware coroutine scope.
     */
    fun complete(prompt: String, maxTokens: Int = 512): Flow<String> = flow {
        check(modelPtr != 0L && ctxPtr != 0L) { "No model loaded" }
        cancelled = false

        val samplerPtr = LlamaBridge.newSampler(/*temp=*/0.7f, /*topK=*/40, /*topP=*/0.9f)
        try {
            LlamaBridge.kvCacheClear(ctxPtr)

            val promptTokens = LlamaBridge.tokenize(modelPtr, prompt, /*addBos=*/true)
            var pastCount = 0

            // Prime the KV cache with the whole prompt first.
            var nextToken = LlamaBridge.decodeAndSample(
                ctxPtr, batchPtr, samplerPtr, promptTokens, pastCount
            )
            pastCount += promptTokens.size

            var emitted = 0
            while (!cancelled && emitted < maxTokens) {
                if (nextToken < 0) break
                if (LlamaBridge.isEndOfGeneration(modelPtr, nextToken)) break

                emit(LlamaBridge.tokenToPiece(modelPtr, nextToken))
                emitted++

                nextToken = LlamaBridge.decodeAndSample(
                    ctxPtr, batchPtr, samplerPtr, intArrayOf(nextToken), pastCount
                )
                pastCount += 1
            }
        } finally {
            LlamaBridge.freeSampler(samplerPtr)
        }
    }.flowOn(dispatcher)
}
