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
// The Android plugins proper (AGP, KSP, Hilt) are NOT declared here, and the
// reason is a hard constraint rather than a preference: a machine with no
// Android SDK cannot resolve AGP at all, and declaring it here — even
// `apply false` — is enough to take the whole build down on such a machine,
// including `:engine:test`. That is the 99% case (CLAUDE.md §7) and the one
// CI runs on every push.
//
// They were added here while getting the first real Android sync to pass,
// in the same commit that moved the Gradle wrapper from 8.14.3 to 8.14.5.
// Separating the two showed the wrapper bump was the whole fix: with the
// declarations removed and 8.14.5 kept, the Android sync still passes on a
// machine with an SDK and `check` still passes on one without.
//
// Kotlin's own plugins DO belong here. `kotlin.jvm` and `kotlin.android` are
// two ids on one artifact, so declaring only the first leaves that artifact on
// the root classpath with no version attached and :app's request for the
// second fails. Declaring both pins the version once.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
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
