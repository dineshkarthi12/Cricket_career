pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cricket-career"

// ---------------------------------------------------------------------------
// Pure-JVM modules. These are ALWAYS in the build: the engine, the
// presentation layer and the calibration harness must be buildable and
// testable on a bare JDK with no Android SDK, because that is where 99% of the
// work happens (and it is what CI runs on every push).
//
// :presentation is here rather than inside :app on purpose - see docs/UI.md.
// It keeps the part of the product that needs an Android SDK to compile as
// small as it can be. See CLAUDE.md - "Module boundaries".
// ---------------------------------------------------------------------------
include(":engine")
include(":presentation")
include(":sim-harness")

// ---------------------------------------------------------------------------
// Android modules. Included only when an Android SDK is actually present,
// otherwise the Android Gradle Plugin fails configuration and takes the whole
// build (including :engine:test) down with it.
//
// Override explicitly with -Pcricket.includeAndroid=true|false.
// ---------------------------------------------------------------------------
val androidSdkPresent: Boolean = run {
    val fromProperty = providers.gradleProperty("cricket.includeAndroid").orNull
    if (fromProperty != null) return@run fromProperty.toBoolean()

    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!fromEnv.isNullOrBlank() && file(fromEnv).isDirectory) return@run true

    val localProperties = file("local.properties")
    if (localProperties.isFile) {
        val props = java.util.Properties()
        localProperties.inputStream().use(props::load)
        val sdkDir = props.getProperty("sdk.dir")
        if (!sdkDir.isNullOrBlank() && file(sdkDir).isDirectory) return@run true
    }
    false
}

if (androidSdkPresent) {
    include(":data")
    include(":app")
} else {
    logger.lifecycle(
        "No Android SDK detected - configuring JVM-only build (:engine, :sim-harness). " +
            "Set ANDROID_HOME or sdk.dir in local.properties to include :data and :app."
    )
}
