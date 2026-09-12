package com.cricketcareer.engine.career

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.Country
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.PitchArchetype
import com.cricketcareer.engine.model.world.Region
import com.cricketcareer.engine.model.world.SoilType
import com.cricketcareer.engine.model.world.Team
import com.cricketcareer.engine.model.world.Venue
import com.cricketcareer.engine.rng.SimRandom
import com.cricketcareer.engine.seed.Competition
import com.cricketcareer.engine.seed.CompetitionStructure
import com.cricketcareer.engine.seed.SeedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The join between the world and the career.
 *
 * These tests are about *shape* — who plays whom, how often, where, and when —
 * rather than about any simulated result. A fixture list that is wrong in shape
 * produces a career that is wrong everywhere downstream and looks plausible the
 * whole way, which is exactly the kind of bug a distribution test never finds.
 */
class FixtureListTest {

    // ---- a small world -----------------------------------------------------

    private fun venue(id: String, archetype: PitchArchetype, soil: SoilType = SoilType.CLAY) =
        Venue(
            id = id,
            name = "Ground $id",
            city = id,
            country = "IND",
            region = "MH",
            soilType = soil,
            archetypeWeights = mapOf(archetype to 1.0),
        )

    private fun team(id: String, venueId: String, squad: List<Player> = emptyList()) = Team(
        id = id,
        name = "Team $id",
        shortName = id.takeLast(3),
        country = "IND",
        region = "MH",
        level = LadderLevel.STATE_WHITE_BALL,
        homeVenue = venueId,
        squad = squad.map { it.id },
    )

    /** Fourteen players, all of them rated [rating] at everything. */
    private fun squad(prefix: String, rating: Int): List<Player> =
        (1..14).map { i ->
            Fixtures.averagePlayer("$prefix-$i")
                .copy(id = PlayerId("$prefix-$i"), attributes = Attributes.uniform(rating))
        }

    private fun competition(
        id: String = "COMP",
        teams: List<String>,
        structure: CompetitionStructure = CompetitionStructure.SINGLE_ROUND_ROBIN,
        format: String = "T20",
        startMonth: Int = 10,
        weeks: Int = 8,
    ) = Competition(
        id = id,
        name = "Competition $id",
        country = "IND",
        level = LadderLevel.STATE_WHITE_BALL,
        format = format,
        structure = structure,
        teams = teams,
        startMonth = startMonth,
        weeks = weeks,
    )

    private fun world(
        teamIds: List<String>,
        structure: CompetitionStructure = CompetitionStructure.SINGLE_ROUND_ROBIN,
        format: String = "T20",
        weeks: Int = 8,
        squads: Map<String, List<Player>> = emptyMap(),
    ): SeedDatabase {
        val venues = teamIds.map { venue("V-$it", PitchArchetype.BALANCED) }
        return SeedDatabase(
            countries = listOf(Country(id = "IND", name = "India", regions = listOf(Region("MH", "Maharashtra")))),
            venues = venues,
            teams = teamIds.map { team(it, "V-$it", squads[it].orEmpty()) },
            competitions = listOf(competition(teams = teamIds, structure = structure, format = format, weeks = weeks)),
            players = squads.values.flatten(),
        )
    }

    private fun random(seed: Long = 77L) = SimRandom.fromSeed(seed)

    private fun fixturesFor(
        teamId: String,
        database: SeedDatabase,
        seasonYear: Int = 2026,
        seed: Long = 77L,
    ): List<Fixture> =
        FixtureList.forTeam(database.competitions.first(), teamId, seasonYear, database, random(seed))

    // ---- who plays whom ----------------------------------------------------

    @Test
    fun `a single round robin plays every other side exactly once`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val database = world(ids)

        val fixtures = fixturesFor("A", database)

        assertEquals(4, fixtures.size)
        assertEquals(listOf("B", "C", "D", "E"), fixtures.map { it.opponent }.sorted())
    }

    @Test
    fun `a double round robin plays every other side twice, once at each ground`() {
        val ids = listOf("A", "B", "C", "D")
        val database = world(ids, structure = CompetitionStructure.DOUBLE_ROUND_ROBIN)

        val fixtures = fixturesFor("A", database)

        assertEquals(6, fixtures.size)
        fixtures.groupBy { it.opponent }.forEach { (opponent, meetings) ->
            assertEquals(2, meetings.size, "A should meet $opponent twice")
            assertEquals(
                listOf(false, true),
                meetings.map { it.atHome }.sorted(),
                "A should meet $opponent once at home and once away",
            )
        }
    }

    @Test
    fun `groups and knockout plays only inside its own group`() {
        val ids = listOf("A", "B", "C", "D", "E", "F")
        val database = world(ids, structure = CompetitionStructure.GROUPS_THEN_KNOCKOUT)

        val first = fixturesFor("A", database).map { it.opponent }.sorted()
        val second = fixturesFor("F", database).map { it.opponent }.sorted()

        assertEquals(listOf("B", "C"), first)
        assertEquals(listOf("D", "E"), second)
    }

    @Test
    fun `a knockout guarantees exactly one match`() {
        val ids = listOf("A", "B", "C", "D")
        val database = world(ids, structure = CompetitionStructure.KNOCKOUT)

        ids.forEach { id ->
            assertEquals(1, fixturesFor(id, database).size, "$id should have one guaranteed knockout match")
        }
    }

    @Test
    fun `a two-side series is three matches against that side, all at the host's grounds`() {
        val database = world(listOf("HOST", "TOUR"), structure = CompetitionStructure.BILATERAL_SERIES)

        val host = fixturesFor("HOST", database)
        val tourist = fixturesFor("TOUR", database)

        assertEquals(3, host.size)
        assertEquals(3, tourist.size)
        assertTrue(host.all { it.opponent == "TOUR" && it.atHome })
        assertTrue(tourist.all { it.opponent == "HOST" && !it.atHome })
    }

    // ---- the international rotation ---------------------------------------

    private val nations = listOf("N0", "N1", "N2", "N3", "N4", "N5", "N6", "N7", "N8", "N9")

    private fun calendar(seasonYear: Int, teamIds: List<String> = nations): Map<String, List<Fixture>> {
        val database = world(teamIds, structure = CompetitionStructure.BILATERAL_SERIES, weeks = 10)
        return teamIds.associateWith { fixturesFor(it, database, seasonYear = seasonYear) }
    }

    @Test
    fun `a ten-nation calendar gives every side the same amount of cricket`() {
        calendar(2026).forEach { (nation, fixtures) ->
            assertEquals(9, fixtures.size, "$nation should play three series of three")
            assertEquals(3, fixtures.map { it.opponent }.distinct().size, "$nation should tour three sides")
        }
    }

    @Test
    fun `every international series is agreed on by both nations`() {
        // The bug this replaces: every side playing the first-listed nation,
        // so one country hosted the entire world and played only one of them.
        val season = calendar(2026)
        season.forEach { (nation, fixtures) ->
            fixtures.map { it.opponent to it.atHome }.distinct().forEach { (opponent, atHome) ->
                val mirror = checkNotNull(season[opponent]).filter { it.opponent == nation }
                assertEquals(3, mirror.size, "$opponent should have $nation on its calendar too")
                assertTrue(
                    mirror.all { it.atHome != atHome },
                    "$nation and $opponent disagree about who is hosting",
                )
            }
        }
    }

    @Test
    fun `the rotation brings everyone round eventually`() {
        val met = nations.associateWith { mutableSetOf<String>() }
        (2026..2031).forEach { year ->
            calendar(year).forEach { (nation, fixtures) ->
                checkNotNull(met[nation]).addAll(fixtures.map { it.opponent })
            }
        }
        met.forEach { (nation, opponents) ->
            assertEquals(9, opponents.size, "$nation has not played everyone after six seasons")
        }
    }

    @Test
    fun `a nation does not host the same opponent forever`() {
        // Over a long enough run every pairing is played at both ends,
        // otherwise a player would never tour a country he plays every year.
        val venues = (2026..2043).flatMap { year ->
            checkNotNull(calendar(year)["N0"]).filter { it.opponent == "N5" }.map { it.atHome }
        }.toSet()
        assertEquals(setOf(false, true), venues, "N0 always played N5 at the same end")
    }

    @Test
    fun `an odd number of nations means someone has a quiet summer`() {
        val odd = listOf("N0", "N1", "N2", "N3", "N4")
        val season = calendar(2026, odd)
        assertTrue(
            season.values.any { it.size < 9 },
            "with five sides and three series a round, one of them must sit a round out",
        )
        season.forEach { (nation, fixtures) ->
            assertTrue(fixtures.size % 3 == 0, "$nation has a part-played series")
            assertTrue(fixtures.none { it.opponent == nation }, "$nation is playing itself")
        }
    }

    @Test
    fun `a team not in the competition cannot be scheduled`() {
        val database = world(listOf("A", "B", "C"))
        assertThrows<IllegalArgumentException> { fixturesFor("Z", database) }
    }

    // ---- when they play ----------------------------------------------------

    @Test
    fun `fixtures are spread across the competition window and never collide`() {
        val ids = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        val database = world(ids, weeks = 8)

        val fixtures = fixturesFor("A", database)

        assertEquals(fixtures.size, fixtures.map { it.date }.distinct().size, "no two matches on one day")
        assertEquals(fixtures.sortedBy { it.date }, fixtures, "fixtures come out in date order")

        val span = java.time.temporal.ChronoUnit.DAYS.between(fixtures.first().date, fixtures.last().date)
        assertEquals(8 * 7L, span, "a seven-match league should fill its eight-week window")
    }

    @Test
    fun `a crowded calendar pushes matches apart rather than stacking them`() {
        // Thirty meetings in a one-week window: even spacing alone would put
        // several on the same morning, which no board and no body can do.
        val ids = ('A'..'P').map { it.toString() }
        val database = world(ids, structure = CompetitionStructure.DOUBLE_ROUND_ROBIN, weeks = 1)

        val fixtures = fixturesFor("A", database)

        assertEquals(30, fixtures.size)
        assertEquals(30, fixtures.map { it.date }.distinct().size)
    }

    @Test
    fun `a multi-day match is never followed by one starting inside it`() {
        val ids = listOf("A", "B", "C", "D", "E", "F")
        val database = world(ids, format = "FC4", weeks = 2)

        val fixtures = fixturesFor("A", database).sortedBy { it.date }

        fixtures.zipWithNext { earlier, later ->
            val gap = java.time.temporal.ChronoUnit.DAYS.between(earlier.date, later.date)
            assertTrue(gap >= 4, "a four-day match needs four days; got a $gap-day gap")
        }
    }

    @Test
    fun `the season starts in the competition's month`() {
        val database = world(listOf("A", "B", "C"))
        val fixtures = fixturesFor("A", database, seasonYear = 2031)

        assertEquals(2031, fixtures.first().date.year)
        assertEquals(10, fixtures.first().date.monthValue)
    }

    // ---- where they play ---------------------------------------------------

    @Test
    fun `the pitch comes from the host ground, which is what home advantage is made of`() {
        // Every one of A's grounds is a rank turner and every one of B's is a
        // green seamer. Nothing adds a home bonus; the strip does the work.
        val turner = venue("V-A", PitchArchetype.RANK_TURNER)
        val seamer = venue("V-B", PitchArchetype.GREEN_SEAMER)
        val database = SeedDatabase(
            countries = listOf(Country(id = "IND", name = "India", regions = listOf(Region("MH", "Maharashtra")))),
            venues = listOf(turner, seamer),
            teams = listOf(team("A", "V-A"), team("B", "V-B")),
            competitions = listOf(
                competition(teams = listOf("A", "B"), structure = CompetitionStructure.DOUBLE_ROUND_ROBIN),
            ),
        )

        val fixtures = fixturesFor("A", database)
        val home = fixtures.single { it.atHome }
        val away = fixtures.single { !it.atHome }

        assertTrue(home.pitch.turn > away.pitch.turn, "A's own ground should turn more than B's")
        assertTrue(away.pitch.gripSeam > home.pitch.gripSeam, "B's ground should seam more than A's")
    }

    @Test
    fun `a team whose ground is missing still gets a playable strip`() {
        // A hole in the database must not take the career down with it.
        val database = SeedDatabase(
            countries = listOf(Country(id = "IND", name = "India", regions = listOf(Region("MH", "Maharashtra")))),
            venues = emptyList(),
            teams = listOf(team("A", "MISSING"), team("B", "ALSO-MISSING")),
            competitions = listOf(competition(teams = listOf("A", "B"))),
        )

        val fixtures = fixturesFor("A", database)

        assertEquals(1, fixtures.size)
        assertTrue(fixtures.single().pitch.hardness in 0.0..1.0)
    }

    // ---- how good the opposition is ---------------------------------------

    @Test
    fun `opposition standard follows the opposing squad`() {
        val database = world(
            listOf("A", "WEAK", "STRONG"),
            squads = mapOf(
                "A" to squad("A", 50),
                "WEAK" to squad("W", 25),
                "STRONG" to squad("S", 85),
            ),
        )

        val fixtures = fixturesFor("A", database).associateBy { it.opponent }

        val weak = checkNotNull(fixtures["WEAK"]).oppositionStandard
        val strong = checkNotNull(fixtures["STRONG"]).oppositionStandard
        assertTrue(strong > weak, "an 85-rated side should read stronger than a 25-rated one ($strong vs $weak)")
    }

    @Test
    fun `a side with no squad in the database reads as mid-table rather than hopeless`() {
        val database = world(listOf("A", "B"), squads = mapOf("A" to squad("A", 50)))
        assertEquals(0.5, fixturesFor("A", database).single().oppositionStandard, 1e-9)
    }

    @Test
    fun `squad strength is the best eleven, not the whole squad`() {
        // Two stars and sixteen journeymen field the two stars.
        val stars = squad("STAR", 90).take(11)
        val padding = squad("PAD", 20).take(7)

        val eleven = Squads.strength(stars, Fixtures.T20)
        val padded = Squads.strength(stars + padding, Fixtures.T20)

        assertEquals(eleven, padded, 1e-12)
    }

    @Test
    fun `an empty squad is mid-table`() {
        assertEquals(0.5, Squads.strength(emptyList(), Fixtures.T20), 1e-12)
    }

    // ---- the whole season --------------------------------------------------

    @Test
    fun `a season merges every competition a team is in, in date order`() {
        val venues = listOf(venue("V-A", PitchArchetype.BALANCED), venue("V-B", PitchArchetype.BALANCED))
        val database = SeedDatabase(
            countries = listOf(Country(id = "IND", name = "India", regions = listOf(Region("MH", "Maharashtra")))),
            venues = venues,
            teams = listOf(team("A", "V-A"), team("B", "V-B")),
            competitions = listOf(
                competition(id = "RED-BALL", teams = listOf("A", "B"), format = "FC4", startMonth = 10, weeks = 6),
                competition(id = "WHITE-BALL", teams = listOf("A", "B"), format = "T20", startMonth = 1, weeks = 4),
            ),
        )

        val season = FixtureList.seasonFor("A", 2026, database, random())

        assertEquals(2, season.size)
        assertEquals(season.sortedBy { it.date }, season)
        assertEquals(setOf("FC4", "T20"), season.map { it.format.id }.toSet())
    }

    @Test
    fun `a team in no competition has no season`() {
        val database = world(listOf("A", "B"))
        val orphan = database.copy(teams = database.teams + team("ORPHAN", "V-A"))

        assertTrue(FixtureList.seasonFor("ORPHAN", 2026, orphan, random()).isEmpty())
    }

    @Test
    fun `fixture ids are unique across a season`() {
        val ids = listOf("A", "B", "C", "D", "E", "F")
        val database = world(ids, structure = CompetitionStructure.DOUBLE_ROUND_ROBIN)

        val season = FixtureList.seasonFor("A", 2026, database, random())

        assertEquals(season.size, season.map { it.id }.distinct().size)
    }

    // ---- determinism -------------------------------------------------------

    @Test
    fun `the same seed gives the identical fixture list`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val database = world(ids)

        assertEquals(fixturesFor("A", database, seed = 4242), fixturesFor("A", database, seed = 4242))
    }

    @Test
    fun `a different seed moves the strips but not the schedule`() {
        // Who you play and when is a fixture list, not a roll of the dice.
        // Only the pitch is drawn, so only the pitch may differ.
        val spread = venue("V-A", PitchArchetype.RANK_TURNER).copy(
            archetypeWeights = mapOf(
                PitchArchetype.RANK_TURNER to 1.0,
                PitchArchetype.FLAT_ROAD to 1.0,
                PitchArchetype.GREEN_SEAMER to 1.0,
            ),
        )
        val database = SeedDatabase(
            countries = listOf(Country(id = "IND", name = "India", regions = listOf(Region("MH", "Maharashtra")))),
            venues = listOf(spread),
            teams = listOf(team("A", "V-A"), team("B", "V-A"), team("C", "V-A")),
            competitions = listOf(competition(teams = listOf("A", "B", "C"))),
        )

        val one = fixturesFor("A", database, seed = 1)
        val two = fixturesFor("A", database, seed = 999)

        assertEquals(one.map { it.id }, two.map { it.id })
        assertEquals(one.map { it.date }, two.map { it.date })
        assertEquals(one.map { it.opponent to it.atHome }, two.map { it.opponent to it.atHome })
        assertNotEquals(one.map { it.pitch }, two.map { it.pitch })
    }

    @Test
    fun `the season is the same whichever year it is generated for, shifted by a year`() {
        val ids = listOf("A", "B", "C", "D")
        val database = world(ids)

        val first = fixturesFor("A", database, seasonYear = 2026)
        val second = fixturesFor("A", database, seasonYear = 2027)

        assertEquals(first.map { it.opponent }, second.map { it.opponent })
        first.zip(second).forEach { (a, b) -> assertEquals(a.date.plusYears(1), b.date) }
    }
}
