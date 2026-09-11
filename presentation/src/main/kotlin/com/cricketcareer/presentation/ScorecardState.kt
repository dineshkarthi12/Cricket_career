package com.cricketcareer.presentation

import com.cricketcareer.engine.match.state.InningsScorecard
import com.cricketcareer.engine.model.world.MatchFormat

/** One line of the batting card. */
data class BattingRow(
    val name: String,
    val howOut: String,
    val runs: Int,
    val balls: Int,
    val fours: Int,
    val sixes: Int,
    val notOut: Boolean,
    val batted: Boolean,
) {
    /** "42*" for a batter still there. */
    val runsDisplay: String get() = if (notOut && batted) "$runs*" else "$runs"

    /**
     * Blank rather than "0.0" for a batter who has not faced a ball. A strike
     * rate off no balls is not zero, it does not exist, and printing 0.0 reads
     * as a man who blocked fourteen.
     */
    val strikeRate: String get() = if (balls == 0) "-" else "%.1f".format(runs * 100.0 / balls)
}

/** One line of the bowling card. */
data class BowlingRow(
    val name: String,
    val overs: String,
    val maidens: Int,
    val runs: Int,
    val wickets: Int,
) {
    val figures: String get() = "$wickets-$runs"

    val economy: String
        get() {
            val balls = overs.substringBefore('.').toInt() * MatchFormat.BALLS_PER_OVER +
                overs.substringAfter('.', "0").toInt()
            return if (balls == 0) "-" else "%.2f".format(runs * MatchFormat.BALLS_PER_OVER.toDouble() / balls)
        }
}

/** Everything the scorecard screen draws for one innings. */
data class ScorecardState(
    val battingTeam: String,
    val bowlingTeam: String,
    val total: String,
    val batting: List<BattingRow>,
    val bowling: List<BowlingRow>,
    val extrasTotal: Int,
    /** "(b 4, lb 7, w 9, nb 1)", omitting whatever was zero. */
    val extrasBreakdown: String,
    val fallOfWickets: List<String>,
)

/**
 * Turning an innings into a scorecard.
 *
 * There is no arithmetic here that the engine has not already done — the
 * engine's `InningsScorecard` balances its own books and is tested to. This
 * function names people and formats numbers, and that is deliberately all it
 * does: two places computing a total is two places to disagree about one.
 */
fun scorecardState(innings: InningsScorecard, names: Names): ScorecardState = ScorecardState(
    battingTeam = innings.battingTeam,
    bowlingTeam = innings.bowlingTeam,
    total = innings.display,
    batting = innings.batting
        // Batting order, always. Sorting a card by runs is a table of figures,
        // not a scorecard, and it loses the thing a card is for: who came in
        // when, and what the innings looked like from the top.
        .sortedBy { it.position }
        .map { line ->
            val batted = line.balls > 0 || line.isOut
            BattingRow(
                name = names.short(line.player),
                howOut = HowOut.describe(line.dismissal, names, faced = batted),
                runs = line.runs,
                balls = line.balls,
                fours = line.fours,
                sixes = line.sixes,
                notOut = !line.isOut,
                batted = batted,
            )
        },
    bowling = innings.bowling
        // Bowlers appear in the order they were used, which is how a card is
        // read: the new-ball pair at the top.
        .map { figures ->
            BowlingRow(
                name = names.short(figures.player),
                overs = figures.overs,
                maidens = figures.maidens,
                runs = figures.runsConceded,
                wickets = figures.wickets,
            )
        },
    extrasTotal = innings.extras,
    extrasBreakdown = extrasBreakdown(innings),
    fallOfWickets = innings.fallOfWickets.map { "${it.display} ${names.short(it.batterOut)}" },
)

/**
 * "(b 4, lb 7, w 9, nb 1)" — and "" when there were none.
 *
 * Zero categories are dropped rather than printed as "b 0", which is how every
 * scorecard in the world does it and is the difference between a card that
 * looks typeset and one that looks generated.
 */
private fun extrasBreakdown(innings: InningsScorecard): String {
    val parts = buildList {
        if (innings.byes > 0) add("b ${innings.byes}")
        if (innings.legByes > 0) add("lb ${innings.legByes}")
        if (innings.wides > 0) add("w ${innings.wides}")
        if (innings.noBalls > 0) add("nb ${innings.noBalls}")
        if (innings.penaltyRuns > 0) add("pen ${innings.penaltyRuns}")
    }
    return if (parts.isEmpty()) "" else parts.joinToString(", ", prefix = "(", postfix = ")")
}
