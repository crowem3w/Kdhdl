// JNI bridge between Kotlin (LlamaBridge.kt) and llama.cpp's public C API
// (llama.h). Kept intentionally close to llama.cpp's own
// examples/llama.android reference implementation.
//
// Design notes:
//  - All llama_* calls for a given context must happen on ONE thread.
//    llama.cpp is not thread-safe per-context. LlamaSession.kt enforces
//    this by running everything through a single-threaded dispatcher —
//    this file does not add its own locking on top of that contract.
//  - Handles (model/context/batch/sampler) are passed to Kotlin as jlong
//    pointers rather than wrapped in JNI global refs, matching upstream.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#include "llama.h"

#define TAG "llama-android"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

// ---------------------------------------------------------------------
// Backend lifecycle
// ---------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_org_example_test_LlamaBridge_backendInit(JNIEnv *, jobject) {
    llama_backend_init();
}

JNIEXPORT void JNICALL
Java_org_example_test_LlamaBridge_backendFree(JNIEnv *, jobject) {
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_org_example_test_LlamaBridge_systemInfo(JNIEnv *env, jobject) {
    // Reports which backends actually compiled in (handy to show the user
    // whether Vulkan is really active on their device vs. CPU fallback).
    return env->NewStringUTF(llama_print_system_info());
}

// ---------------------------------------------------------------------
// Model loading
// ---------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_org_example_test_LlamaBridge_loadModel(
        JNIEnv *env, jobject, jstring jPath, jint nGpuLayers) {
    const char *path = env->GetStringUTFChars(jPath, nullptr);

    llama_model_params params = llama_model_default_params();
    // nGpuLayers = 999 offloads everything Vulkan can take; 0 = CPU only.
    // Passed in from Kotlin so the user can fall back to CPU if a device's
    // Vulkan driver misbehaves with a given model.
    params.n_gpu_layers = nGpuLayers;

    llama_model *model = llama_model_load_from_file(path, params);
    env->ReleaseStringUTFChars(jPath, path);

    if (model == nullptr) {
        LOGe("failed to load model");
        return 0;
    }
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_org_example_test_LlamaBridge_freeModel(JNIEnv *, jobject, jlong modelPtr) {
    llama_model_free(reinterpret_cast<llama_model *>(modelPtr));
}

// ---------------------------------------------------------------------
// Context (KV cache + inference state)
// ---------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_org_example_test_LlamaBridge_newContext(
        JNIEnv *, jobject, jlong modelPtr, jint nCtx, jint nThreads) {
    auto *model = reinterpret_cast<llama_model *>(modelPtr);

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = nCtx;
    ctxParams.n_batch = nCtx > 512 ? 512 : nCtx;
    ctxParams.n_threads = nThreads;
    ctxParams.n_threads_batch = nThreads;

    llama_context *ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        LOGe("failed to create context");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_org_example_test_LlamaBridge_freeContext(JNIEnv *, jobject, jlong ctxPtr) {
    llama_free(reinterpret_cast<llama_context *>(ctxPtr));
}

JNIEXPORT void JNICALL
Java_org_example_test_LlamaBridge_kvCacheClear(JNIEnv *, jobject, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    llama_memory_clear(llama_get_memory(ctx), true);
}

// ---------------------------------------------------------------------
// Batch
// ---------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_org_example_test_LlamaBridge_newBatch(JNIEnv *, jobject, jint nTokens) {
    llama_batch *batch = new llama_batch(llama_batch_init(nTokens, 0, 1));
    return reinterpret_cast<jlong>(batch);
}

JNIEXPORT void JNICALL
Java_org_example_test_LlamaBridge_freeBatch(JNIEnv *, jobject, jlong batchPtr) {
    auto *batch = reinterpret_cast<llama_batch *>(batchPtr);
    llama_batch_free(*batch);
    delete batch;
}

// ---------------------------------------------------------------------
// Sampler chain (temperature + top-k + top-p + final distribution sample)
// ---------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_org_example_test_LlamaBridge_newSampler(
        JNIEnv *, jobject, jfloat temp, jint topK, jfloat topP) {
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *chain = llama_sampler_chain_init(sparams);

    llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    return reinterpret_cast<jlong>(chain);
}

JNIEXPORT void JNICALL
Java_org_example_test_LlamaBridge_freeSampler(JNIEnv *, jobject, jlong samplerPtr) {
    llama_sampler_free(reinterpret_cast<llama_sampler *>(samplerPtr));
}

// ---------------------------------------------------------------------
// Tokenizer
// ---------------------------------------------------------------------

JNIEXPORT jintArray JNICALL
Java_org_example_test_LlamaBridge_tokenize(
        JNIEnv *env, jobject, jlong modelPtr, jstring jText, jboolean addBos) {
    auto *model = reinterpret_cast<llama_model *>(modelPtr);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const char *text = env->GetStringUTFChars(jText, nullptr);
    int textLen = (int) strlen(text);

    int nTokensMax = textLen + (addBos ? 1 : 0) + 8;
    std::vector<llama_token> tokens(nTokensMax);

    int n = llama_tokenize(vocab, text, textLen, tokens.data(), nTokensMax,
                            addBos, /*parse_special=*/true);
    env->ReleaseStringUTFChars(jText, text);

    if (n < 0) {
        // buffer was too small — n is the negated required size
        tokens.resize(-n);
        n = llama_tokenize(vocab, text, textLen, tokens.data(), -n, addBos, true);
    }

    jintArray result = env->NewIntArray(n);
    env->SetIntArrayRegion(result, 0, n, reinterpret_cast<const jint *>(tokens.data()));
    return result;
}

JNIEXPORT jstring JNICALL
Java_org_example_test_LlamaBridge_tokenToPiece(
        JNIEnv *env, jobject, jlong modelPtr, jint token) {
    auto *model = reinterpret_cast<llama_model *>(modelPtr);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    char buf[128];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n < 0) return env->NewStringUTF("");
    return env->NewStringUTF(std::string(buf, n).c_str());
}

JNIEXPORT jboolean JNICALL
Java_org_example_test_LlamaBridge_isEndOfGeneration(
        JNIEnv *, jobject, jlong modelPtr, jint token) {
    auto *model = reinterpret_cast<llama_model *>(modelPtr);
    const llama_vocab *vocab = llama_model_get_vocab(model);
    return llama_vocab_is_eog(vocab, token) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------
// Decode step: feed `tokens` starting at KV position `pastCount`, then
// sample the next token. Returns the sampled token id, or -1 on failure.
// This is called in a loop from Kotlin (LlamaSession) — one token per
// call — so the caller can stream pieces to the UI as they're produced
// and check for cancellation between calls.
// ---------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_org_example_test_LlamaBridge_decodeAndSample(
        JNIEnv *env, jobject, jlong ctxPtr, jlong batchPtr, jlong samplerPtr,
        jintArray jTokens, jint pastCount) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    auto *batch = reinterpret_cast<llama_batch *>(batchPtr);
    auto *sampler = reinterpret_cast<llama_sampler *>(samplerPtr);

    jsize nNew = env->GetArrayLength(jTokens);
    jint *tokens = env->GetIntArrayElements(jTokens, nullptr);

    batch->n_tokens = nNew;
    for (jsize i = 0; i < nNew; i++) {
        batch->token[i] = tokens[i];
        batch->pos[i] = pastCount + i;
        batch->n_seq_id[i] = 1;
        batch->seq_id[i][0] = 0;
        // Only the very last token of the batch needs logits computed.
        batch->logits[i] = (i == nNew - 1);
    }
    env->ReleaseIntArrayElements(jTokens, tokens, JNI_ABORT);

    if (llama_decode(ctx, *batch) != 0) {
        LOGe("llama_decode failed");
        return -1;
    }

    llama_token next = llama_sampler_sample(sampler, ctx, batch->n_tokens - 1);
    llama_sampler_accept(sampler, next);
    return next;
}

} // extern "C"
