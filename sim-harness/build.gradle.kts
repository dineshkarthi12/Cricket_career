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
