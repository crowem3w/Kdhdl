
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
    implementation(libs.tensorflow.lite)
    implementation(libs.androidx.work.runtime.ktx)

    // Test-only: CpcvPboValidationGateTest is pure-JVM (ValidationGate.kt/
    // CombinatorialPurgedCrossValidator.kt have no Android imports), runs via `./gradlew test`.
    testImplementation(libs.junit)
    // Test-only: ExperienceLogStoreKillRestartTest exercises the real ExperienceLogStore class
    // against Robolectric's shadowed android.database.sqlite, without needing a device/emulator.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
