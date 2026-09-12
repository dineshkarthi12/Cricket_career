// :sim-harness - the calibration instrument.
//
// A first-class deliverable, not a debug tool. Runs bulk simulations on the JVM
// and prints the reports that Section 3 of the brief judges the engine by:
//
//   ./gradlew :sim-harness:run --args="--format=T20 --matches=5000 --report=calibration"
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":engine"))
    implementation(testFixtures(project(":engine")))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.cricketcareer.harness.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

// Reports that write files — the seed generator, the innings export — should
// land at the repository root rather than inside this module, because that is
// where `seed/` lives and where a person looking for the output will look.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// SeedFileTest checks the database that actually ships, which lives at the
// repository root. Tests run from the module directory, so point them at it
// explicitly rather than leaving the path to depend on where Gradle was run.
tasks.withType<Test>().configureEach {
    systemProperty("cricket.seedDir", rootProject.file("seed").absolutePath)
}
