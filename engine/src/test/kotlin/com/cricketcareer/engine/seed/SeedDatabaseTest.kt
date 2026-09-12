package com.cricketcareer.engine.seed

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.PersonName
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.Country
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.Region
import com.cricketcareer.engine.model.world.Team
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SeedDatabaseTest {

    private val venue = Fixtures.AVERAGE_VENUE.copy(id = "GROUND-1")

    private val country = Country(
        id = "IND",
        name = "India",
        regions = listOf(Region(id = "MH", name = "Maharashtra"), Region(id = "TN", name = "Tamil Nadu")),
    )

    private fun team(id: String, name: String, region: String = "MH", level: LadderLevel = LadderLevel.STATE_WHITE_BALL) =
        Team(
            id = id,
            name = name,
            shortName = id.takeLast(2),
            country = "IND",
            region = region,
            level = level,
            homeVenue = venue.id,
        )

    private fun competition(
        teams: List<String>,
        level: LadderLevel = LadderLevel.STATE_WHITE_BALL,
        format: String = "T20",
        structure: CompetitionStructure = CompetitionStructure.SINGLE_ROUND_ROBIN,
    ) = Competition(
        id = "IND-STATE-T20",
        name = "State T20 Cup",
        country = "IND",
        level = level,
        format = format,
        structure = structure,
        teams = teams,
        startMonth = 10,
        weeks = 7,
    )

    private fun database(
        teams: List<Team> = listOf(team("IND-MH", "Maharashtra"), team("IND-TN", "Tamil Nadu", region = "TN")),
        competitions: List<Competition> = listOf(competition(teams.map { it.id })),
        players: List<com.cricketcareer.engine.model.player.Player> = emptyList(),
    ) = SeedDatabase(
        countries = listOf(country),
        venues = listOf(venue),
        teams = teams,
        competitions = competitions,
        players = players,
    )

    // ---- Round trip -----------------------------------------------------

    @Test
    fun `a database survives being written out and read back`() {
        val original = database()
        assertEquals(original, SeedDatabase.load(SeedDatabase.encode(original)))
    }

    @Test
    fun `an empty database is legal`() {
        // A player who deletes everything and starts again should be able to.
        assertEquals(SeedDatabase(), SeedDatabase.load(SeedDatabase.encode(SeedDatabase())))
    }

    @Test
    fun `an unknown key is ignored rather than fatal`() {
        // The file is meant to be edited by hand, and a later version of the
        // game writing an extra key should not brick an older one.
        val json = """{"countries":[],"venues":[],"teams":[],"competitions":[],"notes":"mine"}"""
        assertEquals(0, SeedDatabase.load(json).teams.size)
    }

    // ---- Referential integrity ------------------------------------------

    @Test
    fun `a team with a home venue that does not exist is refused`() {
        // The class of bug that otherwise appears in season three.
        val broken = database(
            teams = listOf(team("IND-MH", "Maharashtra").copy(homeVenue = "NOWHERE")),
            competitions = emptyList(),
        )
        val error = assertThrows<SeedValidationException> { SeedDatabase.load(SeedDatabase.encode(broken)) }
        assertTrue(error.problems.any { it.contains("NOWHERE") }) { error.problems.toString() }
    }

    @Test
    fun `a competition naming a team that does not exist is refused`() {
        val broken = database(competitions = listOf(competition(listOf("IND-MH", "IND-GHOST"))))
        val error = assertThrows<SeedValidationException> { SeedDatabase.load(SeedDatabase.encode(broken)) }
        assertTrue(error.problems.any { it.contains("IND-GHOST") }) { error.problems.toString() }
    }

    @Test
    fun `a squad naming a player who does not exist is refused`() {
        val broken = database(
            teams = listOf(team("IND-MH", "Maharashtra").copy(squad = listOf(PlayerId("NOBODY")))),
            competitions = emptyList(),
        )
        val error = assertThrows<SeedValidationException> { SeedDatabase.load(SeedDatabase.encode(broken)) }
        assertTrue(error.problems.any { it.contains("NOBODY") }) { error.problems.toString() }
    }

    @Test
    fun `a duplicate id is refused`() {
        val broken = database(
            teams = listOf(team("IND-MH", "Maharashtra"), team("IND-MH", "Mumbai")),
            competitions = emptyList(),
        )
        val error = assertThrows<SeedValidationException> { SeedDatabase.load(SeedDatabase.encode(broken)) }
        assertTrue(error.problems.any { it.contains("appears 2 times") }) { error.problems.toString() }
    }

    @Test
    fun `every problem is reported, not just the first`() {
        // Fixing a hand-edited file one error per run is miserable, and the
        // problems are usually related - one renamed team breaks four things.
        val broken = database(
            teams = listOf(team("IND-MH", "Maharashtra").copy(homeVenue = "NOWHERE", country = "XYZ")),
            competitions = listOf(competition(listOf("IND-MH", "IND-GHOST"))),
        )
        val error = assertThrows<SeedValidationException> { SeedDatabase.load(SeedDatabase.encode(broken)) }
        assertTrue(error.problems.size >= 3) { "only found ${error.problems}" }
    }

    // ---- Competition sanity ---------------------------------------------

    @Test
    fun `a district side in a national tournament is refused`() {
        val broken = database(
            teams = listOf(
                team("IND-MH", "Maharashtra"),
                team("IND-PUNE", "Pune", level = LadderLevel.DISTRICT_CLUB),
            ),
        )
        val error = assertThrows<SeedValidationException> { SeedDatabase.load(SeedDatabase.encode(broken)) }
        assertTrue(error.problems.any { it.contains("DISTRICT_CLUB") }) { error.problems.toString() }
    }

    @Test
    fun `a competition using a format that does not exist is refused`() {
        val broken = database(competitions = listOf(competition(listOf("IND-MH", "IND-TN"), format = "HUNDRED")))
        val error = assertThrows<SeedValidationException> { SeedDatabase.load(SeedDatabase.encode(broken)) }
        assertTrue(error.problems.any { it.contains("HUNDRED") }) { error.problems.toString() }
    }

    @Test
    fun `a competition too small to produce a fixture list is rejected on construction`() {
        assertThrows<IllegalArgumentException> { competition(listOf("IND-MH")) }
        assertThrows<IllegalArgumentException> {
            competition(listOf("A", "B"), structure = CompetitionStructure.GROUPS_THEN_KNOCKOUT)
        }
    }

    @Test
    fun `the fixture count matches the structure`() {
        val six = listOf("A", "B", "C", "D", "E", "F")
        assertEquals(15, competition(six, structure = CompetitionStructure.SINGLE_ROUND_ROBIN).leagueMatches())
        assertEquals(30, competition(six, structure = CompetitionStructure.DOUBLE_ROUND_ROBIN).leagueMatches())
        // Two groups of three, three matches each.
        assertEquals(6, competition(six, structure = CompetitionStructure.GROUPS_THEN_KNOCKOUT).leagueMatches())
        assertEquals(0, competition(six, structure = CompetitionStructure.KNOCKOUT).leagueMatches())
    }

    // ---- Licensing, non-negotiable 6 ------------------------------------

    @Test
    fun `a city is fine and a franchise is not`() {
        // The exact line the brief draws.
        assertTrue(!RejectedNames.isFranchiseName("Chennai"))
        assertTrue(!RejectedNames.isFranchiseName("Mumbai"))
        assertTrue(RejectedNames.isFranchiseName("Chennai Super Kings"))
        assertTrue(RejectedNames.isFranchiseName("Mumbai Indians"))
        assertTrue(RejectedNames.isFranchiseName("Rajasthan Royals"))
    }

    @Test
    fun `a franchise name in the database is refused, and the error says which word`() {
        val broken = database(
            teams = listOf(team("IND-MH", "Maharashtra"), team("IND-TN", "Chennai Super Kings", region = "TN")),
        )
        val error = assertThrows<SeedValidationException> { SeedDatabase.load(SeedDatabase.encode(broken)) }
        assertTrue(error.problems.any { it.contains("super") || it.contains("kings") }) { error.problems.toString() }
    }

    @Test
    fun `a real cricketer pasted into the file is refused`() {
        val broken = database(
            players = listOf(Fixtures.averagePlayer("P1").copy(name = PersonName("Sachin", "Tendulkar"))),
            competitions = emptyList(),
        )
        val error = assertThrows<SeedValidationException> { SeedDatabase.load(SeedDatabase.encode(broken)) }
        assertTrue(error.problems.any { it.contains("real cricketer") }) { error.problems.toString() }
    }

    @Test
    fun `a common surname on its own is not a rejection`() {
        // Sharma, Khan, Patel and Singh belong to millions of people. Blocking
        // them would empty the name pools and protect nobody.
        assertTrue(!RejectedNames.isRealPlayer("Anil Sharma"))
        assertTrue(!RejectedNames.isRealPlayer("Imran Khan Jr"))
        assertTrue(!RejectedNames.isRealPlayer("Root"))
        assertTrue(RejectedNames.isRealPlayer("Joe Root"))
    }

    @Test
    fun `the check ignores punctuation and case`() {
        assertTrue(RejectedNames.isRealPlayer("VIRAT  KOHLI"))
        assertTrue(RejectedNames.isFranchiseName("Delhi-Capitals"))
    }

    // ---- Lookups --------------------------------------------------------

    @Test
    fun `lookups resolve and keep the file's own order`() {
        val db = database()
        assertEquals(listOf("IND-MH", "IND-TN"), db.teamsById.keys.toList())
        assertEquals("Maharashtra", db.teamsById.getValue("IND-MH").name)
        assertEquals(2, db.teamsAt(LadderLevel.STATE_WHITE_BALL).size)
        assertEquals(1, db.competitionsIn("IND").size)
    }

    @Test
    fun `a squad resolves to real players`() {
        val player = Fixtures.averagePlayer("P1").copy(country = "IND", region = "MH")
        val db = database(
            teams = listOf(team("IND-MH", "Maharashtra").copy(squad = listOf(player.id))),
            competitions = emptyList(),
            players = listOf(player),
        )
        val loaded = SeedDatabase.load(SeedDatabase.encode(db))
        assertEquals(listOf(player.id), loaded.squadOf("IND-MH").map { it.id })
        assertTrue(loaded.squadOf("NOBODY").isEmpty())
    }
}
