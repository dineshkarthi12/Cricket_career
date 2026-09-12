package com.cricketcareer.engine.model.world

import kotlinx.serialization.Serializable

/**
 * The laws that vary between formats.
 *
 * A data class with named instances rather than a bare enum, because the brief
 * needs eight formats (T20 through to a five-day Test) that differ only in
 * their parameters, and because the ladder lives in the seed database — a
 * competition can define its own variant (a 40-over domestic cup with different
 * powerplays) without a code change.
 *
 * Rules that never vary — six balls to an over, ten wickets to an innings —
 * are constants here rather than fields, so nobody can configure a nonsense
 * game.
 */
@Serializable
data class MatchFormat(
    val id: String,
    val displayName: String,
    val kind: FormatKind,

    /** Overs per innings for a limited-overs game; null for multi-day cricket. */
    val oversPerInnings: Int? = null,

    /** Scheduled days for a multi-day game; null for limited overs. */
    val days: Int? = null,

    /** Innings per side. Two for multi-day cricket, one for limited overs. */
    val inningsPerSide: Int,

    /** Cap on any one bowler's overs. Null in multi-day cricket, where there is none. */
    val maxOversPerBowler: Int? = null,

    /** Fielding restriction phases, in order. Empty in multi-day cricket. */
    val powerplays: List<Powerplay> = emptyList(),

    /** Overs before a new ball may be taken. 80 in Tests; null where it does not apply. */
    val newBallAfterOvers: Int? = null,

    /** Runs of first-innings lead needed to enforce the follow-on. Null where it does not apply. */
    val followOnDeficit: Int? = null,

    /** Whether a tie is broken by a Super Over. */
    val hasSuperOver: Boolean = false,

    /** Whether rain-shortened targets use DLS. */
    val usesDls: Boolean = false,

    /**
     * Overs the side batting second must face before a rain-hit match can have
     * a result at all. Null where the question does not arise.
     *
     * A playing condition rather than a tuning knob: twenty overs in a fifty-
     * over game, five in a Twenty20. Below it there has not been enough cricket
     * to say anybody won, and the match is a no result however far ahead of par
     * a side happens to be.
     */
    val minimumOversForResult: Int? = null,

    /**
     * Unsuccessful reviews each side may take per innings, where the technology
     * exists at all.
     *
     * A playing condition. Two is the modern standard everywhere; older
     * conditions allowed one, and domestic competitions below the top rung
     * often have no review system at all — which `DrsTuning` gates separately,
     * because whether the cameras are there is about the level, not the format.
     */
    val reviewsPerInnings: Int = 2,
) {
    init {
        require(id.isNotBlank()) { "format id must not be blank" }
        require(inningsPerSide in 1..2) { "inningsPerSide must be 1 or 2, was $inningsPerSide" }
        when (kind) {
            FormatKind.LIMITED_OVERS -> {
                requireNotNull(oversPerInnings) { "$id is limited overs but has no oversPerInnings" }
                require(oversPerInnings in 1..60) { "$id has $oversPerInnings overs per innings" }
                require(days == null) { "$id is limited overs but also declares days" }
                require(inningsPerSide == 1) { "$id is limited overs but has $inningsPerSide innings a side" }
            }
            FormatKind.MULTI_DAY -> {
                requireNotNull(days) { "$id is multi-day but has no day count" }
                require(days in 1..6) { "$id has $days days" }
                require(oversPerInnings == null) { "$id is multi-day but also caps overs per innings" }
            }
        }
        powerplays.zipWithNext().forEach { (a, b) ->
            require(a.endOverExclusive <= b.startOver) {
                "$id has overlapping powerplays: $a and $b"
            }
        }
        oversPerInnings?.let { total ->
            powerplays.lastOrNull()?.let { last ->
                require(last.endOverExclusive <= total) { "$id powerplay $last runs past over $total" }
            }
            maxOversPerBowler?.let { cap ->
                // Five bowlers must be able to get through the innings between them.
                require(cap * 5 >= total) { "$id caps bowlers at $cap overs, which cannot cover $total" }
            }
        }
    }

    val isLimitedOvers: Boolean get() = kind == FormatKind.LIMITED_OVERS
    val isMultiDay: Boolean get() = kind == FormatKind.MULTI_DAY

    /** Legal balls in a full innings; null in multi-day cricket, where an innings ends otherwise. */
    val ballsPerInnings: Int? get() = oversPerInnings?.times(BALLS_PER_OVER)

    /** Fielders allowed outside the circle in [over] (0-based). */
    fun fieldersOutsideCircleLimit(over: Int): Int? =
        powerplays.firstOrNull { over >= it.startOver && over < it.endOverExclusive }?.fieldersOutsideCircle

    companion object {
        /** Six. Not configurable — a format that changes this is not cricket. */
        const val BALLS_PER_OVER: Int = 6

        /** Ten. An innings ends when the tenth wicket falls. */
        const val WICKETS_PER_INNINGS: Int = 10

        val T20: MatchFormat = MatchFormat(
            id = "T20",
            displayName = "Twenty20",
            kind = FormatKind.LIMITED_OVERS,
            oversPerInnings = 20,
            inningsPerSide = 1,
            maxOversPerBowler = 4,
            powerplays = listOf(
                Powerplay("Powerplay", startOver = 0, endOverExclusive = 6, fieldersOutsideCircle = 2),
                Powerplay("Middle", startOver = 6, endOverExclusive = 20, fieldersOutsideCircle = 5),
            ),
            hasSuperOver = true,
            usesDls = true,
            minimumOversForResult = 5,
        )

        val FORTY_OVER: MatchFormat = MatchFormat(
            id = "OD40",
            displayName = "40-over",
            kind = FormatKind.LIMITED_OVERS,
            oversPerInnings = 40,
            inningsPerSide = 1,
            maxOversPerBowler = 8,
            powerplays = listOf(
                Powerplay("Powerplay 1", startOver = 0, endOverExclusive = 7, fieldersOutsideCircle = 2),
                Powerplay("Powerplay 2", startOver = 7, endOverExclusive = 32, fieldersOutsideCircle = 4),
                Powerplay("Powerplay 3", startOver = 32, endOverExclusive = 40, fieldersOutsideCircle = 5),
            ),
            hasSuperOver = true,
            usesDls = true,
            minimumOversForResult = 20,
        )

        val LIST_A: MatchFormat = MatchFormat(
            id = "OD50",
            displayName = "50-over",
            kind = FormatKind.LIMITED_OVERS,
            oversPerInnings = 50,
            inningsPerSide = 1,
            maxOversPerBowler = 10,
            powerplays = listOf(
                Powerplay("Powerplay 1", startOver = 0, endOverExclusive = 10, fieldersOutsideCircle = 2),
                Powerplay("Powerplay 2", startOver = 10, endOverExclusive = 40, fieldersOutsideCircle = 4),
                Powerplay("Powerplay 3", startOver = 40, endOverExclusive = 50, fieldersOutsideCircle = 5),
            ),
            hasSuperOver = true,
            usesDls = true,
            minimumOversForResult = 20,
        )

        /** Two-day cricket: age-group and early-season fixtures. */
        val TWO_DAY: MatchFormat = multiDay("FC2", "Two-day", days = 2, followOnDeficit = 100)

        val THREE_DAY: MatchFormat = multiDay("FC3", "Three-day", days = 3, followOnDeficit = 150)

        /** The standard domestic first-class match. */
        val FOUR_DAY: MatchFormat = multiDay("FC4", "Four-day", days = 4, followOnDeficit = 150)

        /** Five-day Test cricket. */
        val TEST: MatchFormat = multiDay("TEST", "Test", days = 5, followOnDeficit = 200)

        val ALL: List<MatchFormat> = listOf(T20, FORTY_OVER, LIST_A, TWO_DAY, THREE_DAY, FOUR_DAY, TEST)

        private fun multiDay(id: String, name: String, days: Int, followOnDeficit: Int) = MatchFormat(
            id = id,
            displayName = name,
            kind = FormatKind.MULTI_DAY,
            days = days,
            inningsPerSide = 2,
            newBallAfterOvers = 80,
            followOnDeficit = followOnDeficit,
        )
    }
}

@Serializable
enum class FormatKind { LIMITED_OVERS, MULTI_DAY }

/** A fielding-restriction phase: [fieldersOutsideCircle] allowed from [startOver] until [endOverExclusive]. */
@Serializable
data class Powerplay(
    val name: String,
    val startOver: Int,
    val endOverExclusive: Int,
    val fieldersOutsideCircle: Int,
) {
    init {
        require(startOver >= 0) { "powerplay '$name' starts at over $startOver" }
        require(endOverExclusive > startOver) { "powerplay '$name' ends before it starts" }
        require(fieldersOutsideCircle in 0..9) {
            "powerplay '$name' allows $fieldersOutsideCircle outside the circle; only 9 fielders are available"
        }
    }
}
