package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.Country
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.Region
import com.cricketcareer.engine.model.world.Team
import com.cricketcareer.engine.rng.SimRandom
import com.cricketcareer.engine.seed.SeedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

/**
 * A year happening to everybody else.
 *
 * Without this the world is a photograph: a career ends against the men it
 * started against, and a place in a side never opens up through anything but
 * the player's own improvement. What is being defended is that the world keeps
 * its *shape* while its people change — same number of cricketers, same squads,
 * same standard of cricket on each rung.
 */
class WorldAgeingTest {

    private val tuning = CareerTuning.DEFAULT
    private val start = LocalDate.of(2026, 4, 1)

    private fun player(id: String, age: Int, rating: Int = 55): Player =
        Fixtures.averagePlayer(id).copy(
            id = PlayerId(id),
            country = "IND",
            region = "Maharashtra",
            dateOfBirth = start.minusYears(age.toLong()),
            attributes = Attributes.uniform(rating),
        )

    private fun team(id: String, level: LadderLevel, squad: List<Player>) = Team(
        id = id,
        name = id,
        shortName = id.takeLast(3),
        country = "IND",
        region = "Maharashtra",
        level = level,
        homeVenue = Fixtures.AVERAGE_VENUE.id,
        squad = squad.map { it.id },
    )

    /** One state association, fielding a red-ball and a white-ball side from the same men. */
    private fun world(ages: List<Int>): SeedDatabase {
        val squad = ages.mapIndexed { i, age -> player("P$i", age) }
        return SeedDatabase(
            countries = listOf(
                Country(id = "IND", name = "India", regions = listOf(Region("Maharashtra", "Maharashtra"))),
            ),
            venues = listOf(Fixtures.AVERAGE_VENUE),
            teams = listOf(
                team("IND-MH", LadderLevel.STATE_FIRST_CLASS, squad),
                team("IND-MH-WB", LadderLevel.STATE_WHITE_BALL, squad),
            ),
            players = squad,
        )
    }

    private fun advance(database: SeedDatabase, year: Int = 0, seed: Long = 9) =
        WorldAgeing.advanceSeason(
            database = database,
            from = start.plusYears(year.toLong()),
            to = start.plusYears(year + 1L),
            random = CareerRandom(seed + year),
            tuning = tuning,
        )

    // ---- shape -------------------------------------------------------------

    @Test
    fun `the world keeps the same number of cricketers in it`() {
        var database = world(List(18) { 22 + it % 14 })
        repeat(15) { year -> database = advance(database, year).first }

        assertEquals(18, database.players.size)
    }

    @Test
    fun `a retirement costs one place, not one place per squad he was in`() {
        // A state association fields a red-ball side and a white-ball side from
        // the same eighteen men, so one retirement empties two slots. Replacing
        // each slot separately grew the shipped world by a sixth over twenty
        // seasons and quietly diluted every side it touched.
        var database = world(List(18) { 34 + it % 6 })
        var debutants = 0
        var retired = 0
        repeat(6) { year ->
            val (next, report) = advance(database, year)
            database = next
            debutants += report.debutants.size
            retired += report.retired.size
        }

        assertTrue(retired > 0) { "nobody retired in six seasons of a squad in its late thirties" }
        assertEquals(retired, debutants)
        assertEquals(18, database.players.size)
    }

    @Test
    fun `every squad stays full and resolves to real players`() {
        var database = world(List(18) { 24 + it % 16 })
        repeat(12) { year -> database = advance(database, year).first }

        database.teams.forEach { team ->
            assertEquals(18, team.squad.size) { "${team.id} has ${team.squad.size}" }
            assertEquals(team.squad.size, database.squadOf(team.id).size) { "${team.id} has a dangling player" }
        }
    }

    @Test
    fun `a replacement inherits every side the retiring man was in`() {
        // Otherwise the white-ball side quietly shrinks while the red-ball one
        // stays full, and nothing says why.
        var database = world(List(18) { 38 })
        repeat(3) { year -> database = advance(database, year).first }

        val red = database.teams.single { it.id == "IND-MH" }.squad.toSet()
        val white = database.teams.single { it.id == "IND-MH-WB" }.squad.toSet()
        assertEquals(red, white)
    }

    // ---- people ------------------------------------------------------------

    @Test
    fun `everybody gets a year older`() {
        val database = world(List(18) { 25 })
        val clock = CareerClock(tuning)
        val after = advance(database).first

        after.players.forEach { player ->
            assertTrue(clock.ageOn(player, start.plusYears(1)) >= 25) { "${player.id} went backwards" }
        }
    }

    @Test
    fun `nobody retires young`() {
        var database = world(List(18) { 22 })
        repeat(5) { year ->
            val (next, report) = advance(database, year)
            database = next
            assertTrue(report.retired.isEmpty()) { "a 2${2 + year}-year-old retired" }
        }
    }

    @Test
    fun `everybody is gone by the outside age`() {
        val ancient = tuning.worldAgeing.retirementByAge
        val database = world(List(18) { ancient })
        val (_, report) = advance(database)

        assertEquals(18, report.retired.size)
    }

    @Test
    fun `a player whose game has gone retires sooner than one whose has not`() {
        // The difference between a thirty-five-year-old still worth his place
        // and one who is being carried. Without it retirement is a birthday.
        fun rate(rating: Int): Double {
            val level = LadderLevel.STATE_FIRST_CLASS
            return (1..4000).count { seed ->
                WorldAgeing.retires(player("P", 35, rating), level, 35, SimRandom.fromSeed(seed.toLong()), tuning)
            } / 4000.0
        }
        assertTrue(rate(30) > rate(75) * 1.5) { "declined %.3f, still good %.3f".format(rate(30), rate(75)) }
    }

    @Test
    fun `retirement becomes more likely every year`() {
        val level = LadderLevel.STATE_FIRST_CLASS
        fun rate(age: Int) = (1..4000).count { seed ->
            WorldAgeing.retires(player("P", age, 60), level, age, SimRandom.fromSeed(seed.toLong()), tuning)
        } / 4000.0

        val curve = listOf(32, 34, 36, 38).map { rate(it) }
        curve.zipWithNext { younger, older -> assertTrue(older > younger) { "$curve" } }
    }

    // ---- what the career layer holds on to -----------------------------------

    @Test
    fun `the user's own player passes through untouched`() {
        // He is advanced by the career clock, day by day, through matches he
        // actually played. Running him through here as well would age him twice
        // and develop him for cricket he never had.
        val database = world(List(18) { 25 })
        val before = database.players.first()

        val after = WorldAgeing.advanceSeason(
            database = database,
            from = start,
            to = start.plusYears(1),
            random = CareerRandom(9),
            tuning = tuning,
            exclude = setOf(before.id),
        ).first.players.first { it.id == before.id }

        assertEquals(before, after)
        // And everybody else did age, so the exclusion is doing something.
        assertTrue(
            WorldAgeing.advanceSeason(database, start, start.plusYears(1), CareerRandom(9), tuning, exclude = setOf(before.id))
                .first.players.any { it.id != before.id && it !in database.players },
        )
    }

    @Test
    fun `the world cannot be advanced backwards`() {
        assertThrows<IllegalArgumentException> {
            WorldAgeing.advanceSeason(
                database = world(List(18) { 25 }),
                from = start,
                to = start.minusDays(1),
                random = CareerRandom(1),
            )
        }
    }

    @Test
    fun `the same seed ages the world the same way`() {
        val database = world(List(18) { 24 + it % 16 })
        val one = WorldAgeing.advanceSeason(database, start, start.plusYears(1), CareerRandom(77))
        val two = WorldAgeing.advanceSeason(database, start, start.plusYears(1), CareerRandom(77))

        assertEquals(one.first.players, two.first.players)
        assertEquals(one.second.retired.map { it.id }, two.second.retired.map { it.id })
    }
}
