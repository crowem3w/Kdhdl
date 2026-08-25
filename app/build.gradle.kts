
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
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

        // AndroidJUnitRunner drives the Task 12 resilience harness (and any
        // other instrumented tests) via `./gradlew connectedDebugAndroidTest`.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Gates org.example.syncora.resilience's androidTest harness -
            // see that package's kdoc. Debug-only so it can never ship in a
            // release build; the harness's own @Before also hard-asserts on
            // this flag as a second line of defense.
            buildConfigField("boolean", "ENABLE_RESILIENCE_TEST_HARNESS", "true")
        }
        release {
            buildConfigField("boolean", "ENABLE_RESILIENCE_TEST_HARNESS", "false")
        }
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
    implementation(libs.tensorflow.lite)
    implementation(libs.androidx.work.runtime.ktx)

    // Task 12 End-to-End Pipeline Resilience Test (Paper Mode) harness only
    // - see app/src/androidTest/kotlin/org/example/syncora/resilience/.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.uiautomator) // for process kill simulation
}
