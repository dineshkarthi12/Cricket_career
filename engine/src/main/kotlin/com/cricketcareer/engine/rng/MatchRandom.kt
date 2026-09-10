package com.cricketcareer.engine.rng

/**
 * The set of independent random streams a single simulation runs on.
 *
 * A match is given one seed. That seed fans out into one long-lived [SimRandom]
 * per named stream, and each simulation stage only ever draws from its own.
 * The streams are derived from the match seed alone, so they are stable
 * regardless of the order in which stages first ask for them.
 *
 * Why this matters more than it looks: it means "tune the swing model" is a
 * change that leaves the fielding, running and umpiring streams bit-identical.
 * Regression baselines stay meaningful, and a diff in a calibration report
 * points at the thing you actually changed.
 */
class MatchRandom(val seed: Long) {

    // LinkedHashMap: insertion-ordered iteration. Nothing in the engine may
    // depend on hash order (see DeterminismConventionsTest).
    private val streams = LinkedHashMap<String, SimRandom>()

    /**
     * The generator for a named stream, created on first use and cached.
     * Calling this twice with the same name returns the same advancing
     * generator, not a rewound copy.
     */
    fun stream(name: String): SimRandom =
        streams.getOrPut(name) { SimRandom.fromSeed(SimRandom.deriveSeed(seed, name)) }

    /** Names of the streams that have actually been used, in first-use order. */
    fun activeStreams(): List<String> = streams.keys.toList()
}

/**
 * Canonical stream names, one per stage of the delivery pipeline plus the
 * cross-cutting ones. Documented in docs/SIMULATION_MODEL.md.
 *
 * Add a constant here rather than passing a string literal at the call site —
 * a typo would silently create a second, unrelated stream.
 */
object RngStreams {
    /** Stage 1: bowler length/line/delivery-type choice and plan drift. */
    const val BOWLER_INTENT = "stage1.bowler-intent"

    /** Stage 2: execution error around the intended ball, wides, no balls. */
    const val EXECUTION = "stage2.execution"

    /** Stage 3: swing, reverse swing, seam deviation, turn, bounce variation. */
    const val BALL_MOVEMENT = "stage3.movement"

    /** Stage 4: the batter's perception error and shot choice. */
    const val BATTER_READ = "stage4.batter-read"

    /** Stage 5: contact quality and contact point. */
    const val CONTACT = "stage5.contact"

    /** Stage 6a: exit trajectory off the bat. */
    const val TRAJECTORY = "stage6.trajectory"

    /** Stage 6b: catching, ground fielding, misfields, overthrows. */
    const val FIELDING = "stage6.fielding"

    /** Running between the wickets and run-out judgement. */
    const val RUNNING = "running"

    /** Umpiring error and DRS decisions. */
    const val UMPIRING = "umpiring"

    /** Toss, weather, light and rain interruptions. */
    const val CONDITIONS = "conditions"

    /** Injuries and niggles occurring during play. */
    const val INJURY = "injury"

    /** AI captaincy: field settings and bowling changes. */
    const val CAPTAINCY = "captaincy"
}
