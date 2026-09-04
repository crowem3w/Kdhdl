
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // Order matters: kapt then ObjectBox, both after the application plugin.
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.objectbox)
}

android {
    namespace = "org.example.syncora"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.example.syncora"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // Pin where ObjectBox's annotation processor generates MyObjectBox
        // (defaults to inferring a package from @Entity classes, which is
        // fragile once entities exist in more than one package - see
        // ObjectBoxStore.kt, which imports MyObjectBox from this package).
        javaCompileOptions {
            annotationProcessorOptions {
                arguments.put("objectbox.myObjectBoxPackage", "org.example.syncora.bitget")
            }
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
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.security.crypto)
}
