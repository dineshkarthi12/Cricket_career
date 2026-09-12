package com.cricketcareer.engine.match.dls

import com.cricketcareer.engine.config.DlsTuning
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How a rain-affected limited-overs match is settled.
 *
 * Two sides do not face the same task when one of them loses overs, and the
 * naive fix — scale the target by overs — is wrong in a way every cricket
 * follower can feel: a side chasing 250 in 50 asked for 125 in 25 has been
 * handed the game, because it can bat its whole innings at six an over with ten
 * wickets in hand. **Wickets are a resource too**, and this is the model that
 * prices them.
 *
 * The resource curve is the published Duckworth–Lewis exponential-decay form;
 * the numbers in it are measured from this engine's own cricket (see
 * [DlsTuning]). What you get out is a percentage: how much of a full innings'
 * run-scoring capacity a side still has.
 *
 * See docs/SIMULATION_MODEL.md §12.
 */
object DuckworthLewis {

    /** Wickets that end an innings. A side ten down has no resource left at all. */
    const val ALL_OUT: Int = 10

    /**
     * Percentage of a full innings' resources a side still holds, given
     * [oversRemaining] to bat and [wicketsLost] already gone.
     *
     * 100 at the top of a fifty-over innings by construction; 0 when either
     * resource is exhausted. Everything else in this object is arithmetic on
     * this one number.
     */
    fun resources(oversRemaining: Double, wicketsLost: Int, tuning: DlsTuning = DlsTuning.DEFAULT): Double {
        require(oversRemaining >= 0.0 && oversRemaining.isFinite()) { "oversRemaining $oversRemaining" }
        require(wicketsLost in 0..ALL_OUT) { "wicketsLost $wicketsLost" }
        if (wicketsLost == ALL_OUT || oversRemaining == 0.0) return 0.0
        return 100.0 * runsAddable(oversRemaining, wicketsLost, tuning) / fullInnings(tuning)
    }

    /**
     * Resources for an innings that ran its whole course uninterrupted:
     * [oversAvailable] overs, none down at the start.
     */
    fun resourcesForInnings(oversAvailable: Double, tuning: DlsTuning = DlsTuning.DEFAULT): Double =
        resources(oversAvailable, wicketsLost = 0, tuning = tuning)

    /**
     * Resources an innings actually had, once interruptions are accounted for.
     *
     * An interruption does not cost a side the overs it loses — it costs the
     * *difference* between what it had before the stoppage and what it has
     * after, at the wicket count it was on. That is the whole subtlety of the
     * method, and the reason a side nine down loses almost nothing to rain
     * while a side none down loses a great deal.
     *
     * [interruptions] is the state at each stoppage, in the order they happened.
     */
    fun resourcesAvailable(
        oversAtStart: Double,
        interruptions: List<Interruption> = emptyList(),
        tuning: DlsTuning = DlsTuning.DEFAULT,
    ): Double {
        var available = resourcesForInnings(oversAtStart, tuning)
        interruptions.forEach { stoppage ->
            val before = resources(stoppage.oversRemainingBefore, stoppage.wicketsLost, tuning)
            val after = resources(stoppage.oversRemainingAfter, stoppage.wicketsLost, tuning)
            available -= (before - after)
        }
        return available.coerceIn(0.0, 100.0)
    }

    /**
     * The revised target for the side batting second.
     *
     * Two branches, and they are not the same rule:
     *
     * - **Side two has less resource.** Scale side one's score in proportion.
     *   It is being asked for the same run rate over a smaller innings.
     * - **Side two has more resource** — side one lost overs to rain that side
     *   two then got back. Proportional scaling would ask side two to keep up
     *   side one's rate over a *longer* innings, which is a harder job than
     *   side one had. So the extra resource is charged at the average scoring
     *   rate instead, not side one's.
     *
     * The `+ 1` is the classic off-by-one in cricket software: a side chasing
     * must *pass* the other, so it is always the par score plus one.
     */
    fun target(
        firstInningsRuns: Int,
        resourcesFirst: Double,
        resourcesSecond: Double,
        tuning: DlsTuning = DlsTuning.DEFAULT,
    ): Int {
        require(firstInningsRuns >= 0) { "firstInningsRuns $firstInningsRuns" }
        require(resourcesFirst > 0.0) { "the side batting first had no resources at all" }
        require(resourcesSecond >= 0.0) { "resourcesSecond $resourcesSecond" }
        return par(firstInningsRuns, resourcesFirst, resourcesSecond, tuning) + 1
    }

    /**
     * The par score: what the side batting second must **match** to tie.
     *
     * One below the target, and the number a scoreboard shows ball by ball
     * during a rain-threatened chase, because a side ahead of par when the
     * covers come on has won.
     */
    fun par(
        firstInningsRuns: Int,
        resourcesFirst: Double,
        resourcesSecond: Double,
        tuning: DlsTuning = DlsTuning.DEFAULT,
    ): Int {
        val raw = if (resourcesSecond <= resourcesFirst) {
            firstInningsRuns * resourcesSecond / resourcesFirst
        } else {
            firstInningsRuns + tuning.averageFiftyOverTotal * (resourcesSecond - resourcesFirst) / 100.0
        }
        // Floor, not round: par is the score that *ties*, and rounding up would
        // hand the chasing side a run it has not scored.
        return floor(raw).roundToInt().coerceAtLeast(0)
    }

    /**
     * Par for a chase in progress: what the side needs on the board **now**,
     * having used part of its innings.
     *
     * This is the number the scoreboard carries during a chase under threat of
     * rain, and the one that decides the match if the players never come back.
     */
    fun parNow(
        firstInningsRuns: Int,
        resourcesFirst: Double,
        resourcesSecondAtStart: Double,
        oversRemaining: Double,
        wicketsLost: Int,
        tuning: DlsTuning = DlsTuning.DEFAULT,
    ): Int {
        val used = resourcesSecondAtStart - resources(oversRemaining, wicketsLost, tuning)
        return par(firstInningsRuns, resourcesFirst, used.coerceAtLeast(0.0), tuning)
    }

    /** Runs a side with [wicketsLost] down can add in [oversRemaining] overs. */
    private fun runsAddable(oversRemaining: Double, wicketsLost: Int, tuning: DlsTuning): Double {
        val asymptote = tuning.asymptote[wicketsLost]
        val decay = tuning.decay[wicketsLost]
        return asymptote * (1.0 - exp(-decay * oversRemaining))
    }

    /** The normalising constant: a full reference innings, none down. */
    private fun fullInnings(tuning: DlsTuning): Double =
        runsAddable(tuning.referenceOvers, wicketsLost = 0, tuning = tuning)
}

/**
 * One stoppage, as the method sees it.
 *
 * What matters is not how long it rained but the state of the innings when it
 * started and how much batting was left when it ended.
 */
data class Interruption(
    /** Overs the side still had to bat when the players went off. */
    val oversRemainingBefore: Double,
    /** Overs it has when they come back. Fewer, or zero if the innings is over. */
    val oversRemainingAfter: Double,
    /** Wickets down at the stoppage. A side nine down loses almost nothing to rain. */
    val wicketsLost: Int,
) {
    init {
        require(oversRemainingBefore.isFinite() && oversRemainingBefore >= 0.0) {
            "oversRemainingBefore $oversRemainingBefore"
        }
        require(oversRemainingAfter.isFinite() && oversRemainingAfter >= 0.0) {
            "oversRemainingAfter $oversRemainingAfter"
        }
        require(oversRemainingAfter <= oversRemainingBefore) {
            "an interruption cannot give overs back: $oversRemainingBefore -> $oversRemainingAfter"
        }
        require(wicketsLost in 0..DuckworthLewis.ALL_OUT) { "wicketsLost $wicketsLost" }
    }
}
