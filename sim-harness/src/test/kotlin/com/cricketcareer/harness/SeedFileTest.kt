package com.cricketcareer.harness

import com.cricketcareer.engine.career.FixtureList
import com.cricketcareer.engine.career.Ladder
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.rng.SimRandom
import com.cricketcareer.engine.seed.SeedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The database that actually ships.
 *
 * `:engine` cannot read a file — it parses text it is handed — so the check
 * that the *shipped* database is a valid one has to live somewhere with a
 * filesystem. Here, where the generator that wrote it lives.
 *
 * If someone edits `seed/world.json` by hand and breaks a reference, this test
 * is what tells them, rather than a crash three seasons into a career.
 */
class SeedFileTest {

    private val seedDirectory = File(System.getProperty("cricket.seedDir") ?: "seed")

    private val database: SeedDatabase by lazy {
        SeedDatabase.load(
            world = File(seedDirectory, "world.json").readText(),
            players = File(seedDirectory, "players.json").readText(),
        )
    }

    @Test
    fun `the shipped database exists and loads`() {
        assertTrue(seedDirectory.isDirectory) { "no seed directory at ${seedDirectory.absolutePath}" }
        assertTrue(database.teams.isNotEmpty())
    }

    @Test
    fun `the ladder is populated from the bottom to the top`() {
        // A rung with no teams on it is a rung a career cannot climb.
        val expected = listOf(
            LadderLevel.DISTRICT_CLUB,
            LadderLevel.STATE_FIRST_CLASS,
            LadderLevel.STATE_WHITE_BALL,
            LadderLevel.ZONAL,
            LadderLevel.FRANCHISE_T20,
            LadderLevel.INTERNATIONAL,
        )
        expected.forEach { level ->
            assertTrue(database.teamsAt(level).isNotEmpty()) { "no teams at $level" }
        }
    }

    @Test
    fun `every level with teams has something for them to play in`() {
        val levelsWithTeams = database.teams.map { it.level }.toSet()
        val levelsWithCompetitions = database.competitions.map { it.level }.toSet()
        assertEquals(emptySet<LadderLevel>(), levelsWithTeams - levelsWithCompetitions) {
            "these levels have teams but no competition: ${levelsWithTeams - levelsWithCompetitions}"
        }
    }

    @Test
    fun `all three international formats are played`() {
        val formats = database.competitions
            .filter { it.level == LadderLevel.INTERNATIONAL }
            .map { it.format }
            .toSet()
        assertEquals(setOf("T20", "OD50", "TEST"), formats)
    }

    @Test
    fun `every team can field a side`() {
        // A squad of nine is a data bug that surfaces as a selection crash.
        database.teams.filter { it.squad.isNotEmpty() }.forEach { team ->
            assertTrue(team.squad.size >= 11) { "team '${team.id}' has only ${team.squad.size} players" }
        }
    }

    @Test
    fun `every squad resolves to real players`() {
        database.teams.filter { it.squad.isNotEmpty() }.forEach { team ->
            assertEquals(team.squad.size, database.squadOf(team.id).size) { "team '${team.id}'" }
        }
    }

    @Test
    fun `the home country has a full pyramid and the rest do not`() {
        // Q2: one country in full, the others at international level only.
        val india = database.teams.filter { it.country == "IND" }.map { it.level }.toSet()
        assertTrue(india.size >= 5) { "India has only $india" }

        database.countries.filter { it.id != "IND" }.forEach { country ->
            val levels = database.teams.filter { it.country == country.id }.map { it.level }.toSet()
            assertEquals(setOf(LadderLevel.INTERNATIONAL), levels) { "${country.id} has $levels" }
        }
    }

    // ---- the world as a career actually meets it ---------------------------

    @Test
    fun `every team in the shipped database gets a season`() {
        // A team on the ladder with no fixtures is a dead end: a career that
        // reaches it stops, and nothing else in the project would say why.
        database.teams.forEach { team ->
            val season = FixtureList.seasonFor(team.id, SEASON, database, SimRandom.fromSeed(SEED))
            assertTrue(season.isNotEmpty()) { "team '${team.id}' (${team.level}) has no fixtures" }
            assertEquals(season.size, season.map { it.id }.distinct().size) { "'${team.id}' has duplicate fixture ids" }
            assertEquals(season.size, season.map { it.date }.distinct().size) { "'${team.id}' plays twice in a day" }
            assertTrue(season.none { it.opponent == team.id }) { "'${team.id}' is playing itself" }
        }
    }

    @Test
    fun `a state season is the shape a state season should be`() {
        // A state association fields two sides: a red-ball one and a white-ball
        // one, with different squads. That is why a player can be a fixture in
        // the Ranji side and never get a white-ball game.
        val redBall = FixtureList.seasonFor("IND-MAHARASHTRA", SEASON, database, SimRandom.fromSeed(SEED))
        val whiteBall = FixtureList.seasonFor("IND-MAHARASHTRA-WB", SEASON, database, SimRandom.fromSeed(SEED))

        // Twenty-four sides in two groups of twelve: eleven group matches each.
        assertEquals(11, redBall.size)
        assertEquals(11, whiteBall.size)
        assertEquals(setOf("FC4"), redBall.map { it.format.id }.toSet())
        assertEquals(setOf("T20"), whiteBall.map { it.format.id }.toSet())
        listOf(redBall, whiteBall).forEach { season ->
            assertTrue(season.any { it.atHome } && season.any { !it.atHome }) { "all played at one end" }
        }
    }

    @Test
    fun `an international season spans all three formats`() {
        val season = FixtureList.seasonFor("IND-INTL", SEASON, database, SimRandom.fromSeed(SEED))

        assertEquals(setOf("T20", "OD50", "TEST"), season.map { it.format.id }.toSet())
        assertTrue(season.all { it.level == LadderLevel.INTERNATIONAL })
        // Nine matches a format: three series of three, from the rotation.
        assertEquals(27, season.size)
    }

    @Test
    fun `the opposition is never a placeholder`() {
        // A blank opponent means the rotation handed out a bye and it leaked
        // into a fixture, which would simulate a match against nobody.
        database.teams.forEach { team ->
            FixtureList.seasonFor(team.id, SEASON, database, SimRandom.fromSeed(SEED)).forEach { fixture ->
                assertTrue(fixture.opponent.isNotBlank()) { "'${team.id}' has a fixture against nobody" }
                assertTrue(fixture.opponent in database.teamsById) { "unknown opponent '${fixture.opponent}'" }
            }
        }
    }

    @Test
    fun `the shipped world produces the same fixtures every time`() {
        val once = FixtureList.seasonFor("IND-T20-CHENNAI", SEASON, database, SimRandom.fromSeed(SEED))
        val twice = FixtureList.seasonFor("IND-T20-CHENNAI", SEASON, database, SimRandom.fromSeed(SEED))
        assertEquals(once, twice)
    }

    // ---- the ladder a player can actually climb ---------------------------

    @Test
    fun `every zone a region feeds is a side that exists`() {
        val zones = database.teamsAt(LadderLevel.ZONAL).map { it.id }.toSet()
        database.countries.flatMap { it.regions }
            .filter { it.zone.isNotBlank() }
            .forEach { region ->
                assertTrue(region.zone in zones) { "region '${region.id}' feeds '${region.zone}', which is not a side" }
            }
    }

    @Test
    fun `every cricketer in the world can see the top of his own ladder`() {
        // A player with no route to an international cap is a career that
        // cannot be played, and the only thing that would report it is this.
        database.players.forEach { player ->
            val rungs = Ladder.forPlayer(player, database)
            assertTrue(rungs.isNotEmpty()) { "'${player.id}' (${player.region}) is eligible for nothing" }
            assertEquals(LadderLevel.INTERNATIONAL, rungs.last().level) {
                "'${player.id}' (${player.region}) tops out at ${rungs.last().level}"
            }
        }
    }

    @Test
    fun `a home-country cricketer climbs the full pyramid`() {
        val player = database.players.first { it.region == "Maharashtra" }

        assertEquals(
            listOf(
                LadderLevel.DISTRICT_CLUB,
                LadderLevel.STATE_FIRST_CLASS,
                LadderLevel.STATE_WHITE_BALL,
                LadderLevel.ZONAL,
                LadderLevel.FRANCHISE_T20,
                LadderLevel.INTERNATIONAL,
            ),
            Ladder.forPlayer(player, database).map { it.level },
        )
        assertEquals("IND-ZONE-WEST", Ladder.zoneOf(player, database))
    }

    @Test
    fun `a cricketer from a country modelled at international level only has one rung`() {
        val australian = database.players.first { it.country == "AUS" }
        val rungs = Ladder.forPlayer(australian, database)

        assertEquals(listOf(LadderLevel.INTERNATIONAL), rungs.map { it.level })
        assertEquals(listOf("AUS-INTL"), rungs.single().teams.map { it.id })
    }

    @Test
    fun `every rung a cricketer can reach has cricket on it`() {
        val player = database.players.first { it.region == "Maharashtra" }

        Ladder.forPlayer(player, database).forEach { rung ->
            val playable = rung.teams.filter { team ->
                FixtureList.seasonFor(team.id, SEASON, database, SimRandom.fromSeed(SEED)).isNotEmpty()
            }
            assertEquals(rung.teams.size, playable.size) { "${rung.level} has sides with no fixtures" }
        }
    }

    @Test
    fun `the structure file is small enough for a person to open`() {
        // The roster is generated and nobody hand-edits three thousand
        // cricketers; the structure is the part that is meant to be edited,
        // and it stops being editable somewhere around a megabyte.
        val world = File(seedDirectory, "world.json")
        assertTrue(world.length() < 1_000_000) { "world.json is ${world.length() / 1024} KB" }
    }

    private companion object {
        /** Any season; the world is not year-dependent beyond the rotation. */
        const val SEASON = 2026
        const val SEED = 77L
    }
}
