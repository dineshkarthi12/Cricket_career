package com.cricketcareer.engine.match.state

import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.MatchFormat
import kotlinx.serialization.Serializable

/** One batter's innings, as it appears on a scorecard. */
@Serializable
data class BatterInnings(
    val player: PlayerId,
    /** Batting position, 1 to 11. */
    val position: Int,
    val runs: Int,
    val balls: Int,
    val fours: Int,
    val sixes: Int,
    val dismissal: Dismissal?,
) {
    /** Did not bat, or is still there. */
    val isOut: Boolean get() = dismissal != null

    val strikeRate: Double get() = if (balls == 0) 0.0 else runs * 100.0 / balls

    /** "42* " for not out. */
    val display: String get() = if (isOut) "$runs" else "$runs*"
}

/** One bowler's figures. */
@Serializable
data class BowlerFigures(
    val player: PlayerId,
    val legalBalls: Int,
    val maidens: Int,
    val runsConceded: Int,
    val wickets: Int,
    val wides: Int,
    val noBalls: Int,
) {
    /** "9.3" — completed overs and balls into the current one. */
    val overs: String
        get() = "${legalBalls / MatchFormat.BALLS_PER_OVER}.${legalBalls % MatchFormat.BALLS_PER_OVER}"

    val economy: Double
        get() = if (legalBalls == 0) 0.0 else runsConceded * MatchFormat.BALLS_PER_OVER.toDouble() / legalBalls

    /** "3-42" */
    val display: String get() = "$wickets-$runsConceded"
}

/** Where a wicket fell. */
@Serializable
data class FallOfWicket(
    val wicketNumber: Int,
    val runs: Int,
    val legalBalls: Int,
    val batterOut: PlayerId,
) {
    /** "3-47 (12.4 ov)" */
    val display: String
        get() = "$wicketNumber-$runs (${legalBalls / MatchFormat.BALLS_PER_OVER}.${legalBalls % MatchFormat.BALLS_PER_OVER} ov)"
}

/**
 * A stand between two batters.
 *
 * Partnership runs include extras scored during the stand, which is the
 * convention every scorecard uses — the pair put those runs on together.
 */
@Serializable
data class Partnership(
    val wicketNumber: Int,
    val batterA: PlayerId,
    val batterB: PlayerId,
    val runs: Int,
    val balls: Int,
    val unbroken: Boolean,
)

/** An immutable snapshot of an innings. */
@Serializable
data class InningsScorecard(
    val battingTeam: String,
    val bowlingTeam: String,
    val runs: Int,
    val wickets: Int,
    val legalBalls: Int,
    val declared: Boolean,
    val byes: Int,
    val legByes: Int,
    val wides: Int,
    val noBalls: Int,
    val penaltyRuns: Int,
    val batting: List<BatterInnings>,
    val bowling: List<BowlerFigures>,
    val fallOfWickets: List<FallOfWicket>,
    val partnerships: List<Partnership>,
) {
    val extras: Int get() = byes + legByes + wides + noBalls + penaltyRuns

    /** "247-6 (78.3 ov)", or "247-6 dec". */
    val display: String
        get() {
            val overs = "${legalBalls / MatchFormat.BALLS_PER_OVER}.${legalBalls % MatchFormat.BALLS_PER_OVER}"
            val suffix = if (declared) " dec" else ""
            return "$runs-$wickets$suffix ($overs ov)"
        }

    /**
     * The books must balance: every run the team scored is either off a bat or
     * an extra, and every run a bowler was charged plus every bye and penalty is
     * the team total. Cheap to check and the fastest way to catch a scoring bug.
     */
    fun reconciles(): Boolean {
        val fromBats = batting.sumOf { it.runs }
        val fromBowlers = bowling.sumOf { it.runsConceded }
        // Bowlers are charged runs off the bat plus wides and no-balls.
        return fromBats + extras == runs && fromBowlers + byes + legByes + penaltyRuns == runs
    }
}

// --- Mutable accumulators ----------------------------------------------------
// Internal to the scorer. They exist so the per-ball path does not rebuild an
// immutable card each time; nothing outside this package ever sees one.

internal class MutableBatterInnings(val player: PlayerId, val position: Int) {
    var runs: Int = 0
    var balls: Int = 0
    var fours: Int = 0
    var sixes: Int = 0
    var dismissal: Dismissal? = null

    fun snapshot(): BatterInnings = BatterInnings(player, position, runs, balls, fours, sixes, dismissal)
}

internal class MutableBowlerFigures(val player: PlayerId) {
    var legalBalls: Int = 0
    var maidens: Int = 0
    var runsConceded: Int = 0
    var wickets: Int = 0
    var wides: Int = 0
    var noBalls: Int = 0

    fun snapshot(): BowlerFigures =
        BowlerFigures(player, legalBalls, maidens, runsConceded, wickets, wides, noBalls)
}
