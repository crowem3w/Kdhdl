/*
 * App module. Converted from the plain-JVM 'application' plugin to the
 * Android application plugin. No separate Kotlin plugin is applied:
 * AGP 9's built-in Kotlin support handles Kotlin sources automatically,
 * and actually errors out if org.jetbrains.kotlin.android is applied
 * alongside it.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.example.test"
    compileSdk = 37

    // Pin the NDK: llama.cpp / ggml's Vulkan backend needs a reasonably
    // recent NDK (r26+) for its Vulkan headers and C++20 support.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "org.example.test"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // Ship arm64 only for now — that's effectively every real Android
        // device running local LLMs. Add x86_64 if you need emulator builds.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // -DGGML_VULKAN=ON is also set inside CMakeLists.txt, but
                // setting it here too makes the intent explicit at the
                // Gradle level and lets you override per build type.
                arguments += listOf(
                    "-DGGML_VULKAN=ON",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                cppFlags += listOf("-std=c++20")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // GGUF files and the native .so are large; don't let AGP recompress
    // the .so into the APK (recompression can break mmap-based loading).
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AUTHORS,LICENSE,NOTICE,README.md}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
}
