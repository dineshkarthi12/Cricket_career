// Root build file. Deliberately thin: it declares plugins without applying them
// so subprojects can opt in, and applies the handful of conventions that must
// hold everywhere (JVM target, test engine, compiler strictness).
//
// Every plugin that ships in the *Kotlin Gradle Plugin artifact* has to be
// declared here, even the ones only :app and :data use. `kotlin.jvm` and
// `kotlin.android` are two ids on one artifact, so declaring only the first
// puts that artifact on the root build classpath with no version attached, and
// :app's later request for the second fails with
//
//     The request for this plugin could not be satisfied because the plugin is
//     already on the classpath with an unknown version
//
// Declaring both here pins the version once and the conflict disappears.
//
// All plugins used across subprojects (Kotlin, AGP, KSP, Hilt) are declared
// here with `apply false` to ensure version pinning and unified classloader
// resolutions across modules.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        // Java 17 bytecode, compiled by whatever JDK (>=17) is on the machine.
        // 17 rather than 21 because :app has to dex these classes and Android's
        // D8 tops out at Java 17 class files.
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                // Warnings are bugs waiting to happen in a maths-heavy codebase.
                allWarningsAsErrors.set(true)
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
            // Calibration suites simulate large samples; give them room and cores.
            maxHeapSize = "2g"
            maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        }
    }
}
