# Running llama.cpp natively on-device

This project runs GGUF models **directly on device hardware** through JNI —
no Termux, no shell subprocess. llama.cpp/ggml compile straight into a
`libllama-android.so` that's linked into the APK, and Kotlin calls into it
like any other native library.

## One-time setup

1. **Android Studio**: install NDK (r27+) and CMake via
   `Tools → SDK Manager → SDK Tools`. The project pins NDK `27.2.12479018`
   in `app/build.gradle.kts` — adjust if you install a different version.

2. **Vendor llama.cpp as a submodule** (this repo does not bundle it —
   it's a separate, large, independently-updated project):

   ```bash
   git submodule add https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
   git submodule update --init --recursive
   ```

3. **Vulkan**: the NDK ships the Vulkan headers/loader stub needed to build
   `GGML_VULKAN=ON` — nothing extra to install. At *runtime*, ggml probes
   the device's Vulkan driver and falls back to the CPU backend
   automatically if none is usable, so this build works on every device,
   just faster on ones with a real Vulkan ICD (most phones from the last
   ~6 years).

4. Open the project in Android Studio and let Gradle sync — the first
   native build will compile all of ggml + llama.cpp, which takes a few
   minutes.

## What's implemented

- `app/src/main/cpp/CMakeLists.txt` — builds llama.cpp + a JNI shim as one
  shared library, Vulkan enabled.
- `app/src/main/cpp/llama-android.cpp` — the JNI bridge (model load,
  context, tokenizer, decode/sample loop).
- `LlamaBridge.kt` — raw `external fun` declarations mirroring the JNI file.
- `LlamaSession.kt` — owns a single dedicated thread (required — llama.cpp
  contexts aren't thread-safe) and exposes a `Flow<String>` of generated
  text pieces.
- `ModelManager.kt` — **bring your own model**:
  - `importFromUri()` — user picks an already-downloaded `.gguf` via the
    system file picker (Storage Access Framework).
  - `downloadFromUrl()` — user pastes a direct Hugging Face file URL
    (e.g. a quant from `Qwen/Qwen2.5-Coder-7B-Instruct-GGUF`) and it
    streams to app-private storage with progress.
- `MainActivity.kt` — minimal UI tying it together, plus a CPU/GPU toggle.

## Picking a model that fits on a phone

RAM is the real constraint, not the app. Roughly: a Q4_K_M quant needs
about `(parameters × 0.6) GB` of RAM for weights alone, plus KV cache.
For Qwen2.5-Coder on an 8 GB phone:

| Variant | Q4_K_M size | Fits comfortably? |
|---|---|---|
| Qwen2.5-Coder 1.5B | ~1.0 GB | Yes, easily |
| Qwen2.5-Coder 3B | ~1.9 GB | Yes |
| Qwen2.5-Coder 7B | ~4.5 GB | Tight — needs a high-RAM device (12 GB+) |

Get the direct download link from the model's "Files" tab on Hugging Face
(right-click the `.gguf` file → copy link) and paste it into the app.

## Known limitations of this scaffold

- Single-turn prompting only — for real chat, apply the model's chat
  template (`llama_chat_apply_template` in llama.cpp) instead of raw
  string concatenation in `MainActivity.onSendClicked()`.
- No prompt caching across turns beyond what's in the same KV cache
  session — each `complete()` call clears the KV cache first.
- Context window is hardcoded to 4096 tokens in `LlamaSession` — raise it
  if your device has RAM to spare (KV cache scales with context size).
- `llama.h`'s exact function names shift between llama.cpp releases (the
  project has a documented API changelog). If a submodule update breaks
  the build, diff `llama-android.cpp`'s calls against the current
  `include/llama.h`.

## This could not be compiled/tested in the sandbox that generated it

This code was written to match llama.cpp's current public C API based on
the real repository structure, but it was not built or run against an
actual NDK/Vulkan toolchain — I don't have network or an Android SDK
available here. Treat it as a solid, realistic starting point rather than
a verified-working build; the first thing to do locally is `./gradlew
assembleDebug` and fix whatever the compiler flags.
