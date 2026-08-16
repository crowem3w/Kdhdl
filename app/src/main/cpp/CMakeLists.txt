cmake_minimum_required(VERSION 3.22.1)
project(llama-android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# -----------------------------------------------------------------------
# Vendored llama.cpp source tree.
#
# This CMakeLists.txt does NOT download llama.cpp itself (no network
# access at Gradle-sync/build time is assumed). You must add it once as a
# git submodule before building:
#
#   git submodule add https://github.com/ggml-org/llama.cpp \
#       app/src/main/cpp/llama.cpp
#   git submodule update --init --recursive
#
# See README-NATIVE.md at the project root for full setup steps.
# -----------------------------------------------------------------------
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

if(NOT EXISTS ${LLAMA_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp source not found at ${LLAMA_CPP_DIR}.\n"
        "Add it as a git submodule first:\n"
        "  git submodule add https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp\n"
        "  git submodule update --init --recursive\n"
        "See README-NATIVE.md for details.")
endif()

# --- llama.cpp / ggml build options ------------------------------------
# Vulkan gives us GPU acceleration on-device (works on most Adreno/Mali/
# PowerVR GPUs via the Android Vulkan driver) while still falling back to
# the CPU (NEON) backend at runtime if a device has no usable Vulkan ICD.
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)

# No libcurl on Android — model fetching is handled on the Kotlin side
# (ModelManager.kt) instead of llama.cpp's built-in downloader.
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_CPP_DIR} llama_build)

# --- JNI bridge ----------------------------------------------------------
add_library(llama-android SHARED
    llama-android.cpp
)

target_include_directories(llama-android PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(llama-android
    llama
    ggml
    ${log-lib}
    ${android-lib}
)

# Strip symbols in release builds to keep the APK smaller — the model
# weights already dominate storage, but the .so itself is still a few MB.
target_compile_options(llama-android PRIVATE -O3)
