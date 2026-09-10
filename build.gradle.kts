// Root build file. Deliberately thin: it declares plugins without applying them
// so subprojects can opt in, and applies the handful of conventions that must
// hold everywhere (JVM target, test engine, compiler strictness).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
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
