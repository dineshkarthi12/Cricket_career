// :presentation - what a screen shows, worked out on a bare JDK.
//
// Pure Kotlin/JVM. NO Android dependency, for the same reason :engine has none:
// this is the layer where the bugs actually live - a scorecard that says
// "b b Kadam", a chase equation off by one ball, a worm that plots the wrong
// axis - and every one of those is testable without an emulator.
//
// :app is then Compose and nothing else. That split exists so the part of the
// product that cannot be compiled without an Android SDK is as small as it can
// possibly be. See docs/UI.md.
//
// It may depend on :engine and on nothing else. It must never reach for :data:
// where a value came from is not a presentation concern.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":engine"))

    testImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project(":engine")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// JVM target, JUnit platform and allWarningsAsErrors all come from the root
// build's `subprojects` block. Repeating them here would be a second place to
// get them wrong.
