// :engine - the product.
//
// Hard rule: this module has NO Android dependency, no I/O, no clock, no
// logging framework and no global mutable state. It must be runnable 10,000
// times in a JVM loop. Everything it needs comes in as a parameter; everything
// it produces comes out as an immutable value. See CLAUDE.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    // Fixtures (average players, average pitch, standard fields) are shared by
    // the engine's own tests, :sim-harness and later :data. They live in
    // testFixtures so they can never be shipped in the release artifact.
    testFixturesImplementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
