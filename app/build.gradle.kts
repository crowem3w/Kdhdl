
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt) // Required for ObjectBox's entity-model annotation processor.
    alias(libs.plugins.objectbox)   // Apply after kotlin-kapt; generates MyObjectBox + KlineEntity_.
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

// ObjectBox's annotation processor infers a single "MyObjectBox" package
// from all @Entity classes it finds. Pinned explicitly here since it's
// currently just the one entity, in org.example.syncora.storage - keeps
// the generated class's location stable if more entities get added
// elsewhere in the tree later.
objectbox {
    myObjectBoxPackage = "org.example.syncora.storage"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    // objectbox-android + the Kotlin extensions artifact are added
    // automatically by the io.objectbox Gradle plugin - no explicit
    // implementation(...) line needed for either.
}
