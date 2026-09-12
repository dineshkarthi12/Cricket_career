package com.cricketcareer.harness

import com.cricketcareer.engine.model.world.LadderLevel
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

    @Test
    fun `the structure file is small enough for a person to open`() {
        // The roster is generated and nobody hand-edits three thousand
        // cricketers; the structure is the part that is meant to be edited,
        // and it stops being editable somewhere around a megabyte.
        val world = File(seedDirectory, "world.json")
        assertTrue(world.length() < 1_000_000) { "world.json is ${world.length() / 1024} KB" }
    }
}
