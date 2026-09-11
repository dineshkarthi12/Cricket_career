// :data - persistence and the seed database.
//
// Owns Room, the repositories the UI talks to, and the mapping between :engine's
// immutable value objects and stored rows. :engine knows nothing about this
// module; the dependency arrow only ever points :data -> :engine.
//
// This module currently has NO SOURCE FILES. Room, KSP and Hilt were wired in
// during Phase 0 as declared intent, and the first sync on a machine with an
// Android SDK showed what that costs: an annotation processor with nothing to
// process, and Hilt's Gradle plugin failing on a class newer AGP has deleted.
//
// So the wiring is stripped back to what the module actually needs to exist,
// and Room, KSP and Hilt return in Phase 7 alongside the first entity, DAO and
// repository. A plugin earns its place when there is code for it to act on.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cricketcareer.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    api(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Room and Hilt return in Phase 7, with the code that needs them.

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}
