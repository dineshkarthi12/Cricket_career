// :app - Compose UI, ViewModels, navigation. A window onto the engine.
//
// Contains no cricket logic. If a rule about cricket is being decided in this
// module, it is in the wrong place (see CLAUDE.md - "Module boundaries").
//
// NOTE: not yet compiled - see the note in data/build.gradle.kts.
// No Hilt and no KSP. There is nothing to inject yet - not one @Inject in the
// module - and a dependency-injection framework with no dependency graph is a
// Gradle plugin, an annotation processor and a version-compatibility problem in
// exchange for nothing. Hilt's plugin is also the thing that broke the first
// real sync: it reaches for com.android.build.gradle.api.BaseVariant, which
// newer AGP has removed.
//
// Both come back in Phase 7, when :data has a database and there is something
// worth injecting. See docs/UI.md.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.cricketcareer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cricketcareer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
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
    implementation(project(":data"))
    implementation(project(":engine"))
    // Everything this module draws is a state object built over there. If a
    // decision about cricket is being made in a @Composable, it belongs here
    // instead. See docs/UI.md.
    implementation(project(":presentation"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
