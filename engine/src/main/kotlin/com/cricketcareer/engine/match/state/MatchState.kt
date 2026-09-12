package com.cricketcareer.engine.match.state

import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.model.world.Venue
import kotlinx.serialization.Serializable

/** Which side won the toss and what they chose. */
@Serializable
data class Toss(val wonBy: String, val decision: TossDecision)

@Serializable
enum class TossDecision { BAT, BOWL }

/** How a completed match finished. */
@Serializable
sealed interface MatchResult {
    /**
     * Won by [runs] — the side batting first defended its total.
     *
     * [dls] is what a scoreboard prints as "(DLS method)". It is a separate
     * field rather than a separate result type because the margin is a real
     * margin either way: a side that finishes twenty-three short of par has
     * lost by twenty-three runs, and a reader wants to be told both things.
     */
    @Serializable
    data class WonByRuns(
        val winner: String,
        val loser: String,
        val runs: Int,
        val dls: Boolean = false,
    ) : MatchResult

    /** Won by [wickets] — the side batting last chased it down. */
    @Serializable
    data class WonByWickets(
        val winner: String,
        val loser: String,
        val wickets: Int,
        val dls: Boolean = false,
    ) : MatchResult

    /** Won by an innings and [runs], in multi-day cricket. */
    @Serializable
    data class WonByInnings(val winner: String, val loser: String, val runs: Int) : MatchResult

    /** Scores level with the last innings complete. */
    @Serializable
    data class Tied(val teamA: String, val teamB: String, val dls: Boolean = false) : MatchResult

    /** Time ran out in a multi-day match. */
    @Serializable
    data class Drawn(val teamA: String, val teamB: String) : MatchResult

    /** No result: abandoned, or too few overs bowled for a result to stand. */
    @Serializable
    data class NoResult(val reason: String) : MatchResult
}

/**
 * One stoppage, as the scoreboard reports it.
 *
 * Kept on the match rather than thrown away inside the simulator because it is
 * most of what a reader needs to understand a rain-affected result: "seven
 * overs lost, target revised to 292" is the story, and without it the
 * scoreboard shows a number nobody can account for.
 */
@Serializable
data class MatchInterruption(
    /** 1 or 2. */
    val innings: Int,
    /** Completed overs of that innings when the players went off. */
    val afterOvers: Int,
    /** Overs the innings had before, and has now. */
    val oversBefore: Int,
    val oversAfter: Int,
    /** Wickets down at the stoppage. A side nine down loses almost nothing. */
    val wicketsLost: Int,
    /** Runs on the board when the players went off. */
    val runs: Int = 0,
    /** The target before this stoppage, and after it. Null when not a chase. */
    val targetBefore: Int? = null,
    val revisedTarget: Int? = null,
) {
    val oversLost: Int get() = oversBefore - oversAfter

    /**
     * What the chasing side needed per over before the stoppage and after it.
     *
     * Both go up, always. Overs and target both come down, but the target comes
     * down by less, because the wickets in hand keep their value while the
     * overs do not — which is the whole reason rain is dangerous to a side that
     * is coasting.
     */
    fun requiredRateBefore(): Double? = rate(targetBefore, oversBefore)

    fun requiredRateAfter(): Double? = rate(revisedTarget, oversAfter)

    private fun rate(target: Int?, overs: Int): Double? {
        if (target == null) return null
        val left = overs - afterOvers
        return if (left <= 0) null else (target - runs).toDouble() / left
    }
}

/**
 * Everything fixed about a match before a ball is bowled.
 *
 * Immutable, and carries the [seed]: a match is a pure function of this object
 * plus that number, which is what makes a bug reproducible from a single line
 * in a report (docs/ARCHITECTURE.md §4).
 */
@Serializable
data class MatchSetup(
    val format: MatchFormat,
    val venue: Venue,
    val homeTeam: String,
    val awayTeam: String,
    /** Batting orders, 1 to 11, for each side. */
    val homeXI: List<PlayerId>,
    val awayXI: List<PlayerId>,
    val seed: Long,
) {
    init {
        require(homeTeam != awayTeam) { "a team cannot play itself" }
        require(homeXI.size >= 2) { "home side needs at least two players" }
        require(awayXI.size >= 2) { "away side needs at least two players" }
        require(homeXI.distinct().size == homeXI.size) { "duplicate player in the home XI" }
        require(awayXI.distinct().size == awayXI.size) { "duplicate player in the away XI" }
        require(homeXI.none { it in awayXI.toSet() }) { "a player appears in both XIs" }
    }

    fun xiFor(team: String): List<PlayerId> = when (team) {
        homeTeam -> homeXI
        awayTeam -> awayXI
        else -> throw IllegalArgumentException("$team is not playing in this match")
    }

    fun opponentOf(team: String): String = when (team) {
        homeTeam -> awayTeam
        awayTeam -> homeTeam
        else -> throw IllegalArgumentException("$team is not playing in this match")
    }
}

/**
 * A match in progress.
 *
 * Mutable for the same reason [InningsState] is: it accumulates across
 * thousands of balls. It owns the sequence of innings, the pitch as it evolves,
 * and the result.
 *
 * It knows the *shape* of a match — how many innings, who bats next, when it is
 * over — but not how to simulate one. Phase 2 drives it.
 */
class MatchState(
    val setup: MatchSetup,
    pitch: Pitch,
) {
    /** The pitch as it stands. Replaced by the (Phase 3) evolution model between sessions. */
    var pitch: Pitch = pitch
        private set

    var toss: Toss? = null
        private set

    /** Day of a multi-day match, 1-based. Always 1 in limited-overs cricket. */
    var day: Int = 1
        private set

    var result: MatchResult? = null
        private set

    var followOnEnforced: Boolean = false
        private set

    private val innings = mutableListOf<InningsState>()

    private val interruptionLog = mutableListOf<MatchInterruption>()

    /** Every stoppage, in the order they happened. Empty in a dry match. */
    val interruptions: List<MatchInterruption> get() = interruptionLog.toList()

    fun recordInterruption(interruption: MatchInterruption) {
        interruptionLog += interruption
    }

    val completedInnings: List<InningsState> get() = innings.toList()

    val currentInnings: InningsState? get() = innings.lastOrNull()?.takeIf { !it.isComplete }

    val inningsPlayed: Int get() = innings.size

    val isComplete: Boolean get() = result != null

    fun setToss(wonBy: String, decision: TossDecision) {
        check(toss == null) { "the toss has already happened" }
        require(wonBy == setup.homeTeam || wonBy == setup.awayTeam) { "$wonBy is not playing" }
        toss = Toss(wonBy, decision)
    }

    /** Advance to the next day of a multi-day match. */
    fun advanceDay() {
        check(setup.format.isMultiDay) { "${setup.format.displayName} is a one-day match" }
        val scheduled = checkNotNull(setup.format.days)
        check(day < scheduled) { "day $day is the last day of this match" }
        day++
    }

    fun updatePitch(updated: Pitch) {
        pitch = updated
    }

    /**
     * Set a result that the scoreboard alone cannot work out.
     *
     * Only rain needs this. Every other result in cricket is a comparison of
     * two totals, which [concludeIfFinished] does; a match abandoned mid-chase
     * is decided against a par score, and par is not on the scoreboard.
     */
    fun concludeByDls(decided: MatchResult) {
        check(result == null) { "the match already has a result" }
        result = decided
    }

    /**
     * Note that the result standing was reached under a revised target.
     *
     * The margin is already right — a side twenty-three short of a DLS target
     * has lost by twenty-three runs — so this only adds what a scoreboard
     * prints as "(DLS method)", which is the part a reader needs in order to
     * know why the target was not the opposition's score plus one.
     */
    fun markResultDls() {
        result = when (val current = result) {
            is MatchResult.WonByRuns -> current.copy(dls = true)
            is MatchResult.WonByWickets -> current.copy(dls = true)
            is MatchResult.Tied -> current.copy(dls = true)
            // A no result, a draw or an innings win is never a DLS decision.
            else -> current
        }
    }

    /** Runs [team] has scored across all its completed and current innings. */
    fun runsFor(team: String): Int = innings.filter { it.battingTeam == team }.sumOf { it.runs }

    /** How many innings [team] has begun. */
    fun inningsPlayedBy(team: String): Int = innings.count { it.battingTeam == team }

    /**
     * Whether the side that batted first may enforce the follow-on: both sides
     * have completed a first innings and the deficit is large enough.
     */
    fun followOnAvailable(): Boolean {
        val format = setup.format
        val deficit = format.followOnDeficit ?: return false
        if (!format.isMultiDay || innings.size != 2) return false
        if (innings.any { !it.isComplete }) return false
        val first = innings[0]
        val second = innings[1]
        return first.runs - second.runs >= deficit
    }

    /**
     * Open an innings for [battingTeam].
     *
     * The target is computed here rather than passed in, because getting it
     * wrong by one is the classic off-by-one in cricket software: a side
     * chasing needs to *pass* the opposition, so the target is the lead plus
     * one.
     */
    /**
     * Open an innings.
     *
     * [target] overrides the computed one. Only rain needs that: a revised
     * target is not the opposition's score plus one, it is what the resource
     * table says the innings is worth, and the two must never be allowed to
     * disagree about the number on the scoreboard.
     */
    fun startInnings(
        battingTeam: String,
        oversAvailable: Int? = null,
        enforcingFollowOn: Boolean = false,
        target: Int? = null,
    ): InningsState {
        check(!isComplete) { "the match is over" }
        check(currentInnings == null) { "an innings is already in progress" }
        val format = setup.format
        check(inningsPlayedBy(battingTeam) < format.inningsPerSide) {
            "$battingTeam has already batted ${format.inningsPerSide} time(s)"
        }
        if (enforcingFollowOn) {
            require(followOnAvailable()) { "the follow-on is not available" }
            followOnEnforced = true
        }

        val state = InningsState(
            format = format,
            battingTeam = battingTeam,
            bowlingTeam = setup.opponentOf(battingTeam),
            battingOrder = setup.xiFor(battingTeam),
            oversAvailable = oversAvailable ?: format.oversPerInnings,
            target = target ?: computeTarget(battingTeam),
        )
        innings += state
        return state
    }

    /**
     * The target for [battingTeam], or null when this is not the final innings.
     *
     * Only the last scheduled innings chases: a side batting third in a Test is
     * building a lead, not chasing a number.
     */
    private fun computeTarget(battingTeam: String): Int? {
        val format = setup.format
        val totalInnings = format.inningsPerSide * 2
        val isFinalInnings = innings.size == totalInnings - 1
        if (!isFinalInnings) return null
        val opponent = setup.opponentOf(battingTeam)
        val lead = runsFor(opponent) - runsFor(battingTeam)
        return (lead + 1).coerceAtLeast(1)
    }

    /**
     * Work out the result, if there is one yet.
     *
     * Returns null while the match is still alive. Once it returns a result,
     * [result] is set and the match is over.
     */
    fun concludeIfFinished(timeExpired: Boolean = false): MatchResult? {
        result?.let { return it }
        val format = setup.format
        val totalInnings = format.inningsPerSide * 2
        val current = currentInnings

        // An innings win: one side has completed all its innings and still
        // trails, so the other never has to bat again.
        if (format.isMultiDay && current == null && innings.size in 2 until totalInnings) {
            val home = setup.homeTeam
            val away = setup.awayTeam
            for ((side, other) in listOf(home to away, away to home)) {
                if (inningsPlayedBy(side) == format.inningsPerSide && innings.filter { it.battingTeam == side }.all { it.isComplete }) {
                    val margin = runsFor(other) - runsFor(side)
                    if (margin > 0 && inningsPlayedBy(other) < format.inningsPerSide) {
                        return finish(MatchResult.WonByInnings(other, side, margin))
                    }
                }
            }
        }

        if (innings.size == totalInnings && current == null) {
            val last = innings.last()
            val chasing = last.battingTeam
            val defending = setup.opponentOf(chasing)
            val chasingTotal = runsFor(chasing)
            val defendingTotal = runsFor(defending)
            return when {
                chasingTotal > defendingTotal ->
                    finish(MatchResult.WonByWickets(chasing, defending, MatchFormat.WICKETS_PER_INNINGS - last.wickets))
                defendingTotal > chasingTotal ->
                    finish(MatchResult.WonByRuns(defending, chasing, defendingTotal - chasingTotal))
                else -> finish(MatchResult.Tied(setup.homeTeam, setup.awayTeam))
            }
        }

        if (timeExpired) {
            return if (format.isMultiDay) {
                finish(MatchResult.Drawn(setup.homeTeam, setup.awayTeam))
            } else {
                finish(MatchResult.NoResult("time expired"))
            }
        }
        return null
    }

    /** End the match without a result — abandoned, or washed out. */
    fun abandon(reason: String) {
        check(result == null) { "the match already has a result" }
        finish(MatchResult.NoResult(reason))
    }

    private fun finish(outcome: MatchResult): MatchResult {
        result = outcome
        return outcome
    }
}
