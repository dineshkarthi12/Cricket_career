package com.cricketcareer.app

import android.app.Application

/**
 * The application object.
 *
 * Bare on purpose. It carried a `@HiltAndroidApp` annotation from Phase 0, which
 * bought a Gradle plugin, an annotation processor and a version-compatibility
 * problem in exchange for a dependency graph that did not exist — there was not
 * one `@Inject` anywhere in the module. Hilt returns in Phase 7 with the
 * repositories that need it.
 */
class CricketCareerApplication : Application()
