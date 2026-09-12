package com.cricketcareer.engine.seed

import com.cricketcareer.engine.model.world.LadderLevel
import kotlinx.serialization.Serializable

/**
 * How a competition decides a winner.
 *
 * The shape matters to the career layer because it decides how many matches a
 * player gets and how uneven they are: a league gives everyone the same number,
 * a knockout gives the good sides more, and it is the second that makes a young
 * player's season depend on the eleven around him.
 */
@Serializable
enum class CompetitionStructure(val displayName: String) {
    /** Everyone plays everyone once. */
    SINGLE_ROUND_ROBIN("League"),

    /** Everyone plays everyone home and away. */
    DOUBLE_ROUND_ROBIN("League, home and away"),

    /** Groups, then the top sides meet in a knockout. */
    GROUPS_THEN_KNOCKOUT("Groups and knockout"),

    /** Straight elimination. */
    KNOCKOUT("Knockout"),

    /** A series of matches between two sides. */
    BILATERAL_SERIES("Series"),
}

/**
 * A tournament: who plays, in what format, at what standard, and when.
 *
 * This is where fixtures come from, and fixtures are what the career layer's
 * `Season` consumes. Everything above `LadderLevel` — which rung of the ladder
 * this is — is already an engine concept; a competition is the thing that puts
 * real teams on a real rung at a real time of year.
 */
@Serializable
data class Competition(
    val id: String,
    val name: String,
    /** Country id, or the empty string for a competition open to several. */
    val country: String,
    val level: LadderLevel,
    /** `MatchFormat.id` — "T20", "LIST_A", "FC4", "TEST". */
    val format: String,
    val structure: CompetitionStructure,
    val teams: List<String>,
    /** 1-12. Seasons cross the new year, so 10 means it starts in October. */
    val startMonth: Int,
    /** How long it runs. Used to space fixtures across the calendar. */
    val weeks: Int,
    /**
     * How much attention it gets, 0 to 1, over and above its level's.
     *
     * A franchise league and a national A tour can sit on similar rungs and
     * feel completely different to play in, and that difference is pressure,
     * reputation and money rather than standard.
     */
    val prestige: Double = 0.5,
) {
    init {
        require(id.isNotBlank()) { "competition id must not be blank" }
        require(name.isNotBlank()) { "competition $id has a blank name" }
        require(format.isNotBlank()) { "competition $id has no format" }
        require(startMonth in 1..12) { "competition $id starts in month $startMonth" }
        require(weeks in 1..52) { "competition $id runs for $weeks weeks" }
        require(prestige.isFinite() && prestige in 0.0..1.0) { "competition $id prestige $prestige" }
        require(teams.size == teams.distinct().size) { "competition $id lists a team twice" }
        require(teams.size >= minimumTeams) {
            "competition $id is a ${structure.displayName} with ${teams.size} teams; it needs $minimumTeams"
        }
    }

    /** Below this a competition cannot produce a fixture list at all. */
    val minimumTeams: Int
        get() = when (structure) {
            CompetitionStructure.BILATERAL_SERIES -> 2
            CompetitionStructure.KNOCKOUT -> 2
            CompetitionStructure.SINGLE_ROUND_ROBIN -> 2
            CompetitionStructure.DOUBLE_ROUND_ROBIN -> 2
            CompetitionStructure.GROUPS_THEN_KNOCKOUT -> 4
        }

    /**
     * Matches in one edition, before any knockout.
     *
     * The group stage of a groups-and-knockout competition is modelled as two
     * balanced groups, which is what almost every real one is and what keeps
     * the fixture count from depending on a number nobody has decided yet.
     */
    fun leagueMatches(): Int = when (structure) {
        CompetitionStructure.SINGLE_ROUND_ROBIN -> teams.size * (teams.size - 1) / 2
        CompetitionStructure.DOUBLE_ROUND_ROBIN -> teams.size * (teams.size - 1)
        CompetitionStructure.GROUPS_THEN_KNOCKOUT -> {
            val perGroup = teams.size / 2
            2 * perGroup * (perGroup - 1) / 2
        }
        CompetitionStructure.KNOCKOUT -> 0
        CompetitionStructure.BILATERAL_SERIES -> teams.size - 1
    }
}
