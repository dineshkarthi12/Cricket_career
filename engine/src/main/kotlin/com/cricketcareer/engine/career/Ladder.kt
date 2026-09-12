package com.cricketcareer.engine.career

import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.Team
import com.cricketcareer.engine.seed.SeedDatabase

/**
 * One rung, and the sides on it a particular player could be picked for.
 *
 * [teams] is plural because most rungs offer more than one door: twenty-four
 * state sides exist but only one of them is his, while every franchise in the
 * country can bid for him. Which of them he actually ends up in is a selection
 * question, not a geography one — this only says which doors exist.
 */
data class Rung(
    val level: LadderLevel,
    val teams: List<Team>,
) {
    init {
        require(teams.isNotEmpty()) { "a rung with no teams on it is not a rung" }
    }
}

/**
 * The ladder one player can climb, in the world he was born into.
 *
 * The point of this object is that **the ladder is seed data, not Kotlin**
 * (Q2). Nothing here knows that India has zones or that a franchise league
 * exists; it reads the pyramid out of the database by asking who is eligible
 * for what, so a database with a different shape gives a different ladder
 * without a line changing here.
 *
 * Eligibility is geography and nothing else. Whether he is good enough is
 * `Selection`'s question, and it is asked separately at every rung.
 *
 * See docs/CAREER_MODEL.md §8 and docs/SEED_DATABASE.md.
 */
object Ladder {

    /**
     * Every side [player] is eligible for, weakest rung first.
     *
     * Ordered by `LadderLevel`'s own declaration order, which is documented as
     * ladder order — deliberately not by [LadderLevel.standard], because a
     * national A side is a step *towards* something in a way a franchise
     * contract is not, however good the cricket in each happens to be.
     */
    fun forPlayer(player: Player, database: SeedDatabase): List<Rung> =
        LadderLevel.ALL.mapNotNull { level ->
            val teams = database.teamsAt(level).filter { eligible(player, it, database) }
            if (teams.isEmpty()) null else Rung(level, teams)
        }

    /**
     * The rung above [level] that this player has somewhere to go on.
     *
     * Skipping empty rungs matters: a world with no age-group cricket in it
     * must promote a district player straight to his state side rather than
     * stalling him against a level that has no teams.
     */
    fun nextRung(player: Player, level: LadderLevel, database: SeedDatabase): Rung? =
        forPlayer(player, database).firstOrNull { it.level.ordinal > level.ordinal }

    /** The rung below, for a player who has been dropped out of his side. */
    fun previousRung(player: Player, level: LadderLevel, database: SeedDatabase): Rung? =
        forPlayer(player, database).lastOrNull { it.level.ordinal < level.ordinal }

    /**
     * Whether [player] could be picked for [team] on grounds of where he is from.
     *
     * Three rules, in widening circles:
     *
     * - A **regional** side picks from its region. That is the district league
     *   and the state sides: a Maharashtra cricketer plays for Maharashtra.
     * - A **zonal** side picks from the regions that feed it, which the seed
     *   database records on each region.
     * - Any **other** side in his country can pick him. A franchise bids for
     *   whoever it wants and a national selector picks from the whole country,
     *   and neither is bound by which state a player happens to come from.
     */
    fun eligible(player: Player, team: Team, database: SeedDatabase): Boolean {
        if (team.country != player.country) return false
        return when {
            team.level == LadderLevel.ZONAL -> zoneOf(player, database) == team.id
            regional(team.level) -> team.region == player.region
            else -> true
        }
    }

    /** The zonal side a player's region feeds, or null where there is none. */
    fun zoneOf(player: Player, database: SeedDatabase): String? =
        database.countriesById[player.country]
            ?.regions
            ?.firstOrNull { it.id == player.region }
            ?.zone
            ?.takeIf { it.isNotBlank() }

    /**
     * Levels at which a side represents one region and picks only from it.
     *
     * Everything below zonal cricket is a place: you play for where you are
     * from. Everything at or above it is a selection, and a selector is not
     * restricted to one state.
     */
    private fun regional(level: LadderLevel): Boolean = when (level) {
        LadderLevel.COLLEGE,
        LadderLevel.DISTRICT_CLUB,
        LadderLevel.STATE_AGE_GROUP,
        LadderLevel.STATE_FIRST_CLASS,
        LadderLevel.STATE_WHITE_BALL,
        -> true

        LadderLevel.ZONAL,
        LadderLevel.FRANCHISE_T20,
        LadderLevel.NATIONAL_A,
        LadderLevel.INTERNATIONAL,
        -> false
    }
}
