package com.cricketcareer.engine.career

import com.cricketcareer.engine.rng.SimRandom

/**
 * The random streams a career runs on.
 *
 * Same contract as [com.cricketcareer.engine.rng.MatchRandom], one level up: a
 * career gets one seed, that seed fans out into independent named streams, and
 * each part of the career layer draws only from its own.
 *
 * The payoff is the same and it matters more here, because a career is twenty
 * simulated years long. Retuning the injury model must not move a single
 * selection decision, or every regression baseline in the project dies with the
 * change and there is no way to tell a tuning improvement from a tuning
 * accident.
 *
 * See docs/CAREER_MODEL.md §10.
 */
class CareerRandom(val seed: Long) {

    // LinkedHashMap: insertion-ordered iteration, per DeterminismConventionsTest.
    private val streams = LinkedHashMap<String, SimRandom>()

    /**
     * The generator for a named stream, created on first use and cached.
     * Calling this twice with the same name returns the same advancing
     * generator, not a rewound copy.
     */
    fun stream(name: String): SimRandom =
        streams.getOrPut(name) { SimRandom.fromSeed(SimRandom.deriveSeed(seed, name)) }

    /**
     * The seed for one match within this career.
     *
     * Derived rather than drawn, so a match replays identically whether it is
     * reached by simulating the career forward or loaded on its own from a bug
     * report. Drawing it from a stream would make the match depend on how many
     * matches had been simulated before it.
     */
    fun matchSeed(matchId: String): Long = SimRandom.deriveSeed(seed, "match:$matchId")

    /** Names of the streams that have actually been used, in first-use order. */
    fun activeStreams(): List<String> = streams.keys.toList()
}

/**
 * Canonical career stream names.
 *
 * Add a constant here rather than passing a string literal at a call site — a
 * typo would silently create a second, unrelated stream, and the bug would
 * present as "the injury rate changed when I edited the selection code".
 */
object CareerStreams {
    /** Year-on-year attribute movement noise, drawn on a birthday. */
    const val AGEING = "career.ageing"

    /** Form and confidence noise on top of the deterministic surprise term. */
    const val FORM = "career.form"

    /** Injury occurrence, body part and severity. */
    const val INJURY = "career.injury"

    /** Training outcome noise. */
    const val TRAINING = "career.training"

    /** Selector judgement noise: the difference between a panel and a spreadsheet. */
    const val SELECTION = "career.selection"

    /** Contract offers and auction bidding. */
    const val CONTRACTS = "career.contracts"

    /** Tier-2 and tier-3 world results. */
    const val WORLD = "career.world"

    /** Players entering the world at the start of each season. */
    const val GENERATION = "career.generation"
}
