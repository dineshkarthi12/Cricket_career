package com.cricketcareer.engine.career

import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.model.world.PitchArchetype
import com.cricketcareer.engine.model.world.SoilType
import com.cricketcareer.engine.model.world.Venue
import com.cricketcareer.engine.rng.SimRandom
import com.cricketcareer.engine.seed.Competition
import com.cricketcareer.engine.seed.CompetitionStructure
import com.cricketcareer.engine.seed.SeedDatabase
import java.time.LocalDate

/**
 * How good a side is, from the players in it.
 *
 * Averaged over the best eleven rather than the whole squad: a team with two
 * stars and sixteen journeymen fields the two stars, and judging it on the
 * eighteen would say it was worse than it plays.
 */
object Squads {

    /** A cricket team. Named because `11` on its own at a call site says nothing. */
    const val XI: Int = 11

    fun strength(squad: List<Player>, format: MatchFormat): Double {
        if (squad.isEmpty()) return DEFAULT_STRENGTH
        return squad
            .map { Selection.standardFor(it, format) }
            .sortedDescending()
            .take(XI)
            .average()
            .coerceIn(0.0, 1.0)
    }

    /**
     * What an unknown side is worth.
     *
     * Mid-table rather than weak: a team the database has no players for is a
     * gap in the data, and guessing it is poor would hand the user free runs.
     */
    private const val DEFAULT_STRENGTH: Double = 0.5
}

/**
 * A competition, turned into the fixtures one team actually plays.
 *
 * This is the join between the seed database and the career layer: a
 * `Competition` says who is in a tournament, a [Fixture] is a match on a date
 * at a ground, and `Season` consumes fixtures. Without this there is a world
 * and a career and nothing between them.
 *
 * See docs/SEED_DATABASE.md and docs/CAREER_MODEL.md.
 */
object FixtureList {

    /** One scheduled meeting, before it has a date or a strip. */
    private data class Meeting(val opponent: String, val atHome: Boolean)

    /**
     * Every fixture [teamId] plays in [competition] in the season starting in
     * [seasonYear], in date order.
     *
     * Dates are spread evenly across the competition's window rather than
     * bunched, because how crowded a schedule is decides what it costs — that
     * is the whole point of charging fatigue per day in the career layer.
     * [spaceOut] then makes sure no side is asked to be in two places at once.
     */
    fun forTeam(
        competition: Competition,
        teamId: String,
        seasonYear: Int,
        database: SeedDatabase,
        random: SimRandom,
    ): List<Fixture> {
        require(teamId in competition.teams) {
            "team '$teamId' is not in competition '${competition.id}'"
        }
        val format = MatchFormat.ALL.firstOrNull { it.id == competition.format }
            ?: error("competition '${competition.id}' uses unknown format '${competition.format}'")

        val meetings = meetingsFor(competition, teamId, seasonYear)
        if (meetings.isEmpty()) return emptyList()

        val start = LocalDate.of(seasonYear, competition.startMonth, 1)
        val window = (competition.weeks * DAYS_PER_WEEK).toLong()

        return spaceOut(
            meetings.mapIndexed { index, meeting ->
                val offset = if (meetings.size == 1) 0L else window * index / (meetings.size - 1)
                val host = if (meeting.atHome) teamId else meeting.opponent
                Fixture(
                    id = "${competition.id}-$seasonYear-${index + 1}-$teamId-v-${meeting.opponent}",
                    date = start.plusDays(offset),
                    format = format,
                    level = competition.level,
                    pitch = pitchAt(venueFor(host, database), random),
                    oppositionStandard = strengthOf(meeting.opponent, database, format),
                    opponent = meeting.opponent,
                    atHome = meeting.atHome,
                )
            },
        )
    }

    /**
     * Every fixture in the season for [teamId], across all its competitions.
     *
     * Competitions are scheduled independently and then merged, so two of them
     * overlapping produces a genuinely congested run of matches. That is what a
     * franchise league landing on top of a first-class season does to a player,
     * and the fatigue and injury models are there to charge him for it.
     */
    fun seasonFor(
        teamId: String,
        seasonYear: Int,
        database: SeedDatabase,
        random: SimRandom,
    ): List<Fixture> = spaceOut(
        database.competitions
            .filter { teamId in it.teams }
            .flatMap { forTeam(it, teamId, seasonYear, database, random) }
            .sortedWith(compareBy<Fixture> { it.date }.thenBy { it.id }),
    )

    /**
     * Push apart fixtures that a side could not physically play.
     *
     * Nobody plays two matches on one day, and nobody starts a match inside a
     * Test he is already three days into. Where the calendar asks for it — a
     * franchise league landing on top of a first-class season, or two
     * overlapping international tours — the later match is pushed on, even if
     * that runs past the competition's nominal window. A real board does the
     * same thing when it over-fills a calendar, and the fatigue and injury
     * models are there to charge the player for the result.
     *
     * [fixtures] must already be in date order.
     */
    private fun spaceOut(fixtures: List<Fixture>): List<Fixture> {
        var free = LocalDate.MIN
        return fixtures.map { fixture ->
            val date = maxOf(fixture.date, free)
            free = date.plusDays((fixture.format.days ?: 1).toLong())
            if (date == fixture.date) fixture else fixture.copy(date = date)
        }
    }

    /**
     * Who this team plays, how often, and where.
     *
     * A groups-and-knockout competition is modelled as two balanced groups
     * split by position in the team list, which is what almost every real one
     * is and what keeps the fixture count from depending on a number nobody has
     * decided. The knockout itself is not scheduled: who reaches it is a result,
     * not a fixture, and pencilling it in would be the career layer deciding
     * cricket.
     */
    private fun meetingsFor(competition: Competition, teamId: String, seasonYear: Int): List<Meeting> {
        val others = competition.teams.filter { it != teamId }
        return when (competition.structure) {
            CompetitionStructure.SINGLE_ROUND_ROBIN ->
                others.mapIndexed { i, other -> Meeting(other, atHome = i % 2 == 0) }

            // Home and away means exactly that: each opponent once at each
            // ground. The two halves are offset by one so a season is not all
            // home cricket followed by all away cricket.
            CompetitionStructure.DOUBLE_ROUND_ROBIN ->
                others.mapIndexed { i, other -> Meeting(other, atHome = i % 2 == 0) } +
                    others.mapIndexed { i, other -> Meeting(other, atHome = i % 2 != 0) }

            CompetitionStructure.GROUPS_THEN_KNOCKOUT -> {
                val half = competition.teams.size / 2
                val group = if (competition.teams.indexOf(teamId) < half) {
                    competition.teams.take(half)
                } else {
                    competition.teams.drop(half)
                }
                group.filter { it != teamId }
                    .mapIndexed { i, other -> Meeting(other, atHome = i % 2 == 0) }
            }

            // A knockout gives one guaranteed match. Everything after it is
            // earned, and the season plays it out rather than pencilling it in.
            CompetitionStructure.KNOCKOUT ->
                others.take(1).map { Meeting(it, atHome = hosts(competition, teamId, it)) }

            // A series is against one opponent, several times, all of them at
            // the host's grounds — a touring side does not go home midway.
            CompetitionStructure.BILATERAL_SERIES ->
                seriesOpponents(competition, teamId, seasonYear).flatMap { (opponent, atHome) ->
                    List(MATCHES_PER_SERIES) { Meeting(opponent, atHome) }
                }
        }
    }

    /**
     * Who a side tours or hosts this season, and where.
     *
     * An international calendar is not one fixed series: it is a rotation, and
     * which two sides meet this year is the whole reason a player's record
     * against a given attack is uneven. Two-team competitions are the literal
     * case — those two, every year. Anything larger is a *calendar* of
     * bilateral series, and each season every side gets
     * [SERIES_PER_SEASON] of them, drawn by the round-robin circle method with
     * the season as the offset. That gives every nation the same amount of
     * cricket, a different set of opponents each year, and a full cycle in
     * which everyone has played everyone.
     */
    private fun seriesOpponents(
        competition: Competition,
        teamId: String,
        seasonYear: Int,
    ): List<Pair<String, Boolean>> {
        val teams = competition.teams
        if (teams.size == 2) {
            val opponent = teams.first { it != teamId }
            return listOf(opponent to hosts(competition, teamId, opponent))
        }

        // With an odd number of sides one of them sits out each round, which is
        // what a bye is and what a nation with a quiet summer looks like.
        val rounds = if (teams.size % 2 == 0) teams.size - 1 else teams.size
        val perSeason = minOf(SERIES_PER_SEASON, rounds)

        return (0 until perSeason).mapNotNull { index ->
            val step = seasonYear * perSeason + index
            val round = Math.floorMod(step, rounds)
            val cycle = Math.floorDiv(step, rounds)
            val opponent = opponentInRound(teams, teamId, round) ?: return@mapNotNull null
            // Who hosts has to be agreed from both ends — generate the same
            // fixture from either side and it must happen at the same ground —
            // so it is decided by position, and flipped each time the rotation
            // comes round again so that no side hosts the same tour forever.
            val lowerListed = teams.indexOf(teamId) < teams.indexOf(opponent)
            opponent to (lowerListed == ((round + cycle) % 2 == 0))
        }.distinctBy { it.first }
    }

    /**
     * The opponent in one round of a round robin, by the circle method: the
     * first side stays put, the rest rotate around it.
     */
    private fun opponentInRound(teams: List<String>, teamId: String, round: Int): String? {
        val padded = if (teams.size % 2 == 0) teams else teams + BYE
        val size = padded.size
        val rotated = listOf(padded[0]) + (1 until size).map { padded[1 + ((it - 1 + round) % (size - 1))] }
        val opponent = rotated[size - 1 - rotated.indexOf(teamId)]
        return opponent.takeIf { it != BYE }
    }

    /**
     * Which of two sides hosts, when only one match or one series is played.
     *
     * Decided by position in the competition's own team list, so it is stable
     * across seeds and the same from either side's point of view: whoever
     * generates the fixture, the match happens at the same ground.
     */
    private fun hosts(competition: Competition, teamId: String, opponent: String): Boolean =
        competition.teams.indexOf(teamId) < competition.teams.indexOf(opponent)

    private fun venueFor(teamId: String, database: SeedDatabase): Venue? =
        database.teamsById[teamId]?.homeVenue?.let { database.venuesById[it] }

    /**
     * The strip, drawn from the host ground's own archetype weights.
     *
     * Home advantage in this engine emerges from grounds favouring particular
     * surfaces and squads being built to suit them — there is no home bonus
     * term anywhere, and this is where that emergence starts.
     */
    private fun pitchAt(venue: Venue?, random: SimRandom): Pitch =
        if (venue == null) {
            PitchArchetype.BALANCED.generate(random, SoilType.CLAY)
        } else {
            weightedArchetype(venue, random).generate(random, venue.soilType)
        }

    private fun weightedArchetype(venue: Venue, random: SimRandom): PitchArchetype {
        val total = venue.archetypeWeights.values.sum()
        var draw = random.nextDouble() * total
        // Iterated in the map's own order, which is the seed file's order —
        // nothing here may depend on hash order.
        venue.archetypeWeights.forEach { (archetype, weight) ->
            draw -= weight
            if (draw <= 0.0) return archetype
        }
        return venue.archetypeWeights.keys.last()
    }

    private fun strengthOf(teamId: String, database: SeedDatabase, format: MatchFormat): Double =
        Squads.strength(database.squadOf(teamId), format)

    private const val DAYS_PER_WEEK = 7

    /** Matches in a bilateral series. Three is the usual length of a modern one. */
    private const val MATCHES_PER_SERIES = 3

    /**
     * Series a side plays per season in a rotation.
     *
     * Three series of three is nine matches per format per year, which is about
     * what a busy international side plays in the long form and, once the three
     * format calendars are laid on top of each other, gives the roughly
     * thirty-match international year the career layer is calibrated for.
     */
    private const val SERIES_PER_SEASON = 3

    /** The placeholder a side is drawn against when the rotation gives it a bye. */
    private const val BYE = ""

}
