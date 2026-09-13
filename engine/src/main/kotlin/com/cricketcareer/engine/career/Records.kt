package com.cricketcareer.engine.career

import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import java.time.LocalDate

/**
 * One line of a career record: what a player did in one format, or in all of
 * them.
 *
 * Every figure here follows the scorer's conventions rather than the
 * arithmetically convenient ones, because a career game is read by people who
 * know the difference:
 *
 * - **Average is runs per dismissal**, not runs per innings, and a player who
 *   has never been out has no average at all rather than an infinite one.
 * - **A not-out highest score carries the asterisk.** 72* and 72 are different
 *   innings and a records screen that loses that is wrong.
 * - **An innings a batter did not bat in is not an innings.** A tail-ender who
 *   was in the XI twelve times and padded up four is 4 innings, 12 matches.
 * - **Best bowling is most wickets, then fewest runs.** 5/40 beats 5/62 and
 *   both beat 4/12.
 */
data class BattingRecord(
    val matches: Int,
    val innings: Int,
    val notOuts: Int,
    val runs: Int,
    val balls: Int,
    val highestScore: Int,
    val highestScoreNotOut: Boolean,
    val fifties: Int,
    val hundreds: Int,
    val doubleHundreds: Int,
    val ducks: Int,
    val fours: Int = 0,
    val sixes: Int = 0,
) {
    val dismissals: Int get() = innings - notOuts

    /** Runs per dismissal, or null for a player never yet out. */
    val average: Double? get() = if (dismissals == 0) null else runs.toDouble() / dismissals

    /** Runs per hundred balls, or null before he has faced one. */
    val strikeRate: Double? get() = if (balls == 0) null else runs * 100.0 / balls

    /** "143*" or "143", or "-" before he has batted. */
    val highestScoreText: String get() = when {
        innings == 0 -> "-"
        highestScoreNotOut -> "$highestScore*"
        else -> "$highestScore"
    }

    companion object {
        val EMPTY = BattingRecord(0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0)
    }
}

/** The bowling half of the same line. */
data class BowlingRecord(
    val matches: Int,
    val balls: Int,
    val runs: Int,
    val wickets: Int,
    val bestWickets: Int,
    val bestRuns: Int,
    val fiveWicketHauls: Int,
    val tenWicketMatches: Int = 0,
) {
    /** Runs per wicket, or null before he has taken one. */
    val average: Double? get() = if (wickets == 0) null else runs.toDouble() / wickets

    /** Runs per over. Six balls to the over everywhere in this game. */
    val economy: Double? get() = if (balls == 0) null else runs * 6.0 / balls

    /** Balls per wicket. */
    val strikeRate: Double? get() = if (wickets == 0) null else balls.toDouble() / wickets

    /** "5/40", or "-" before he has bowled. */
    val bestText: String get() = if (balls == 0) "-" else "$bestWickets/$bestRuns"

    companion object {
        val EMPTY = BowlingRecord(0, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * A performance worth remembering, in the order it happened.
 *
 * This is the part of a records screen a player actually reads — not the
 * aggregate, which only ever creeps, but the day something happened for the
 * first time. Each entry names the match it happened in, so it reads like a
 * career rather than a table.
 */
data class Milestone(
    val date: LocalDate,
    val fixture: String,
    val format: MatchFormat,
    val level: LadderLevel,
    val description: String,
)

/** A whole career, in the shape a records screen wants it. */
data class RecordBook(
    val batting: BattingRecord,
    val bowling: BowlingRecord,
    val battingByFormat: Map<String, BattingRecord>,
    val bowlingByFormat: Map<String, BowlingRecord>,
    val battingByLevel: Map<LadderLevel, BattingRecord>,
    val milestones: List<Milestone>,
)

/**
 * The career record, built from what actually happened.
 *
 * Derived, never stored: a save file holds the appearances and this is
 * recomputed, so a records screen can never disagree with the scorecards behind
 * it. That matters more here than the recomputation costs — the one bug a
 * records screen must not have is a total that nothing supports.
 */
object Records {

    /** Scores at or above this are a milestone in every format. */
    private const val FIFTY = 50
    private const val HUNDRED = 100
    private const val DOUBLE_HUNDRED = 200

    /** Wickets in an innings that make a haul worth naming. */
    private const val FIVE_FOR = 5

    /** Career run totals worth marking as they go past. */
    private val RUN_MILESTONES = listOf(1_000, 2_500, 5_000, 10_000, 15_000, 20_000)

    /** Career wicket totals, the same. */
    private val WICKET_MILESTONES = listOf(50, 100, 250, 500, 750, 1_000)

    fun of(appearances: List<Appearance>): RecordBook {
        // Chronological, with the fixture id breaking ties so that two matches
        // on the same day always order the same way. Sorting by date alone
        // would leave the milestone list dependent on the order the caller
        // happened to accumulate appearances in, which is exactly the kind of
        // thing that makes a save reload show a different career.
        val played = appearances.sortedWith(compareBy({ it.date }, { it.fixture }))

        return RecordBook(
            batting = batting(played),
            bowling = bowling(played),
            battingByFormat = played.groupBy { it.format.id }.mapValues { batting(it.value) },
            bowlingByFormat = played.groupBy { it.format.id }.mapValues { bowling(it.value) },
            battingByLevel = played.groupBy { it.level }.mapValues { batting(it.value) },
            milestones = milestones(played),
        )
    }

    /** One format's batting line. */
    fun batting(appearances: List<Appearance>): BattingRecord {
        // He batted if he faced a ball or was dismissed. A batter run out
        // without facing has had an innings; a number eleven who never went in
        // has not.
        val innings = appearances.filter { it.ballsFaced > 0 || it.out }
        var best = -1
        var bestNotOut = false
        innings.forEach { a ->
            // A higher score always wins; equal scores go to the not-out one,
            // because 72* is the better innings and the one a player claims.
            if (a.runs > best || (a.runs == best && !a.out && bestNotOut.not())) {
                best = a.runs
                bestNotOut = !a.out
            }
        }
        return BattingRecord(
            matches = appearances.size,
            innings = innings.size,
            notOuts = innings.count { !it.out },
            runs = innings.sumOf { it.runs },
            balls = innings.sumOf { it.ballsFaced },
            highestScore = if (best < 0) 0 else best,
            highestScoreNotOut = best >= 0 && bestNotOut,
            fifties = innings.count { it.runs in FIFTY until HUNDRED },
            hundreds = innings.count { it.runs >= HUNDRED },
            doubleHundreds = innings.count { it.runs >= DOUBLE_HUNDRED },
            // A duck is nought *out*. Nought not out is not a duck.
            ducks = innings.count { it.runs == 0 && it.out },
        )
    }

    /** One format's bowling line. */
    fun bowling(appearances: List<Appearance>): BowlingRecord {
        val bowled = appearances.filter { it.ballsBowled > 0 }
        var bestWickets = -1
        var bestRuns = 0
        bowled.forEach { a ->
            // More wickets first, then fewer runs: 5/40 beats 5/62, and both
            // beat 4/12.
            if (a.wickets > bestWickets || (a.wickets == bestWickets && a.runsConceded < bestRuns)) {
                bestWickets = a.wickets
                bestRuns = a.runsConceded
            }
        }
        return BowlingRecord(
            matches = appearances.size,
            balls = bowled.sumOf { it.ballsBowled },
            runs = bowled.sumOf { it.runsConceded },
            wickets = bowled.sumOf { it.wickets },
            bestWickets = if (bestWickets < 0) 0 else bestWickets,
            bestRuns = if (bestWickets < 0) 0 else bestRuns,
            fiveWicketHauls = bowled.count { it.wickets >= FIVE_FOR },
        )
    }

    /**
     * The days something happened for the first time, in order.
     *
     * A milestone is only a milestone once. Passing a thousand career runs is
     * an event; being past a thousand for the next ten years is not, and a list
     * that says so every match is a list nobody reads.
     */
    private fun milestones(played: List<Appearance>): List<Milestone> {
        val out = mutableListOf<Milestone>()
        var runs = 0
        var wickets = 0
        var bestScore = -1
        var bestWickets = -1
        var bestFigureRuns = 0
        var debuted = false
        val formatDebuts = mutableSetOf<String>()

        played.forEach { a ->
            fun mark(text: String) = out.add(Milestone(a.date, a.fixture, a.format, a.level, text))

            if (!debuted) {
                debuted = true
                mark("First-team debut")
            }
            if (formatDebuts.add(a.format.id)) mark("${a.format.displayName} debut")

            val batted = a.ballsFaced > 0 || a.out
            if (batted) {
                if (a.runs >= DOUBLE_HUNDRED) {
                    mark("Double hundred: ${score(a)}")
                } else if (a.runs >= HUNDRED) {
                    mark("Hundred: ${score(a)}")
                }
                if (a.runs > bestScore) {
                    // Only worth announcing once he has something to beat.
                    // "Career-best 3" on debut is noise.
                    if (bestScore >= 0) mark("Career-best score: ${score(a)}")
                    bestScore = a.runs
                }
            }

            if (a.ballsBowled > 0 && a.wickets >= FIVE_FOR) {
                mark("Five wickets: ${a.wickets}/${a.runsConceded}")
            }
            if (a.ballsBowled > 0 &&
                (a.wickets > bestWickets || (a.wickets == bestWickets && a.runsConceded < bestFigureRuns))
            ) {
                if (bestWickets >= 0 && a.wickets > 0) {
                    mark("Career-best bowling: ${a.wickets}/${a.runsConceded}")
                }
                bestWickets = a.wickets
                bestFigureRuns = a.runsConceded
            }

            // Aggregate milestones are stamped on the match that carried the
            // total past the mark, which is where a career actually records
            // them.
            val runsBefore = runs
            runs += a.runs
            RUN_MILESTONES.filter { it in (runsBefore + 1)..runs }
                .forEach { mark("$it career runs") }

            val wicketsBefore = wickets
            wickets += a.wickets
            WICKET_MILESTONES.filter { it in (wicketsBefore + 1)..wickets }
                .forEach { mark("$it career wickets") }
        }
        return out
    }

    private fun score(a: Appearance): String = if (a.out) "${a.runs}" else "${a.runs}*"
}
