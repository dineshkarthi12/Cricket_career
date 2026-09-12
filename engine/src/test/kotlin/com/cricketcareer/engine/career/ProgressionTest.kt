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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Going up and going down.
 *
 * The model being defended is that a call-up is the selection panel at the rung
 * above scoring a player against the men already there — never a threshold on
 * an average. That is what makes a good season in a weak competition worth less
 * than a modest one in a strong competition without a line saying so.
 */
class ProgressionTest {

    private val tuning = CareerTuning.DEFAULT
    private val venue = Fixtures.AVERAGE_VENUE

    private fun player(id: String, rating: Int, region: String = "Maharashtra"): Player =
        Fixtures.averagePlayer(id).copy(
            id = PlayerId(id),
            country = "IND",
            region = region,
            attributes = Attributes.uniform(rating),
        )

    private fun squad(prefix: String, rating: Int): List<Player> =
        Fixtures.averageXI(prefix).plus(Fixtures.averageXI("$prefix-B").take(7)).mapIndexed { i, p ->
            p.copy(id = PlayerId("$prefix-$i"), country = "IND", region = "Maharashtra", attributes = Attributes.uniform(rating))
        }

    private fun team(id: String, level: LadderLevel, squad: List<Player>) = Team(
        id = id,
        name = id,
        shortName = id.takeLast(3),
        country = "IND",
        region = "Maharashtra",
        level = level,
        homeVenue = venue.id,
        squad = squad.map { it.id },
    )

    /** A district side and a state side, with the state side's quality settable. */
    private fun world(stateRating: Int, districtRating: Int = 40): SeedDatabase {
        val district = squad("DIS", districtRating)
        val state = squad("STA", stateRating)
        return SeedDatabase(
            countries = listOf(
                Country(
                    id = "IND",
                    name = "India",
                    regions = listOf(Region("Maharashtra", "Maharashtra", zone = "IND-ZONE-WEST")),
                ),
            ),
            venues = listOf(venue),
            teams = listOf(
                team("IND-MH-PUNE", LadderLevel.DISTRICT_CLUB, district),
                team("IND-MH", LadderLevel.STATE_FIRST_CLASS, state),
            ),
            players = district + state,
        )
    }

    private fun record(player: Player, matches: Int, omissions: Int): SeasonRecord = SeasonRecord(
        player = player,
        appearances = List(matches) {
            Appearance(
                fixture = "F$it",
                date = LocalDate.of(2026, 5, 1).plusDays(it * 7L),
                format = Fixtures.LIST_A,
                level = LadderLevel.DISTRICT_CLUB,
                runs = 50,
                ballsFaced = 60,
                out = true,
            )
        },
        omissions = omissions,
    )

    private fun review(
        player: Player,
        world: SeedDatabase,
        position: CareerPosition,
        matches: Int = 5,
        omissions: Int = 0,
        seed: Long = 11,
    ) = Progression.review(
        player = player,
        record = record(player, matches, omissions),
        position = position,
        database = world,
        personality = SelectorPersonality.AVERAGE,
        random = SimRandom.fromSeed(seed),
        tuning = tuning,
    )

    private val district = CareerPosition("IND-MH-PUNE", LadderLevel.DISTRICT_CLUB, seasonsHere = 2)

    // ---- going up ---------------------------------------------------------

    @Test
    fun `a player far better than the rung above is called up`() {
        val outcome = review(player("YOU", 90), world(stateRating = 40), district)

        assertEquals(Movement.PROMOTED, outcome.movement)
        assertEquals(LadderLevel.STATE_FIRST_CLASS, outcome.position.level)
        assertEquals("IND-MH", outcome.position.teamId)
    }

    @Test
    fun `a player well short of the rung above stays where he is`() {
        val outcome = review(player("YOU", 35), world(stateRating = 85), district)

        assertEquals(Movement.HELD, outcome.movement)
        assertEquals(LadderLevel.DISTRICT_CLUB, outcome.position.level)
    }

    @Test
    fun `the same player is worth more in a weak competition than a strong one`() {
        // The whole point of asking the panel rather than reading an average.
        val man = player("YOU", 62)

        assertEquals(Movement.PROMOTED, review(man, world(stateRating = 40), district).movement)
        assertEquals(Movement.HELD, review(man, world(stateRating = 88), district).movement)
    }

    @Test
    fun `a call-up records what he had to beat and who wanted him`() {
        val outcome = review(player("YOU", 90), world(stateRating = 40), district)

        // He had to rank inside the XI: a side calls a player up when it means
        // to play him, not to give him a squad number.
        assertEquals(Squads.XI, outcome.required)
        assertTrue(checkNotNull(outcome.claim) <= Squads.XI)
        assertEquals("IND-MH", outcome.suitor)
    }

    @Test
    fun `a promotion resets his standing at the new rung`() {
        val outcome = review(player("YOU", 90), world(stateRating = 40), district.copy(seasonsHere = 9))

        assertEquals(0, outcome.position.seasonsHere)
        assertEquals(0, outcome.position.seasonsOverlooked)
    }

    @Test
    fun `a man nobody picks is not looked at by the rung above, however good he is`() {
        // The cost of a season carrying drinks: he is not in the reckoning,
        // and being brilliant in the nets is not a case a selector can make.
        val outcome = review(player("YOU", 95), world(stateRating = 40), district, matches = 0, omissions = 10)

        assertNotEquals(Movement.PROMOTED, outcome.movement)
        assertNull(outcome.claim)
    }

    @Test
    fun `there is nothing above the top of the ladder`() {
        val world = world(stateRating = 60)
        val top = CareerPosition("IND-MH", LadderLevel.STATE_FIRST_CLASS, seasonsHere = 3)

        assertEquals(Movement.HELD, review(player("YOU", 99), world, top).movement)
    }

    // ---- going down -------------------------------------------------------

    @Test
    fun `a player who stops playing is eventually released to the rung below`() {
        val world = world(stateRating = 60)
        var position = CareerPosition("IND-MH", LadderLevel.STATE_FIRST_CLASS)
        val man = player("YOU", 30)

        // Season by season, out of the side every time. Three of them: one
        // settling-in season he cannot be dropped in, then the two it takes to
        // be forgotten.
        val movements = (1..3).map {
            val outcome = review(man, world, position, matches = 0, omissions = 11)
            position = outcome.position
            outcome.movement
        }

        assertEquals(listOf(Movement.HELD, Movement.HELD, Movement.DROPPED), movements)
        assertEquals(LadderLevel.DISTRICT_CLUB, position.level)
        assertEquals("IND-MH-PUNE", position.teamId)
    }

    @Test
    fun `a new arrival is given a season before he can be dropped`() {
        val world = world(stateRating = 60)
        val fresh = CareerPosition("IND-MH", LadderLevel.STATE_FIRST_CLASS, seasonsHere = 0, seasonsOverlooked = 9)

        assertEquals(Movement.HELD, review(player("YOU", 30), world, fresh, matches = 0, omissions = 11).movement)
    }

    @Test
    fun `a regular is not dropped, however long he has been there`() {
        val world = world(stateRating = 60)
        val settled = CareerPosition("IND-MH", LadderLevel.STATE_FIRST_CLASS, seasonsHere = 12, seasonsOverlooked = 12)

        assertEquals(Movement.HELD, review(player("YOU", 55), world, settled, matches = 9, omissions = 1).movement)
    }

    @Test
    fun `a player out of his district side has nowhere further to fall`() {
        val world = world(stateRating = 90)
        var position = district.copy(seasonsOverlooked = 9)

        repeat(3) { position = review(player("YOU", 20), world, position, matches = 0, omissions = 8).position }

        assertEquals(LadderLevel.DISTRICT_CLUB, position.level)
        assertEquals("IND-MH-PUNE", position.teamId)
    }

    // ---- where a career starts --------------------------------------------

    @Test
    fun `a cricketer already in a squad starts there`() {
        val world = world(stateRating = 60)
        val existing = checkNotNull(world.squadOf("IND-MH").firstOrNull())

        val start = checkNotNull(Progression.startingPosition(existing, world))
        assertEquals("IND-MH", start.teamId)
        assertEquals(LadderLevel.STATE_FIRST_CLASS, start.level)
    }

    @Test
    fun `a created cricketer starts on the bottom rung he is eligible for`() {
        val start = checkNotNull(Progression.startingPosition(player("YOU", 50), world(stateRating = 60)))

        assertEquals("IND-MH-PUNE", start.teamId)
        assertEquals(LadderLevel.DISTRICT_CLUB, start.level)
    }

    @Test
    fun `a cricketer with no ladder at all has no starting position`() {
        assertNull(Progression.startingPosition(player("YOU", 50, region = "Nowhere"), world(stateRating = 60)))
    }

    // ---- what is played where ---------------------------------------------

    @Test
    fun `the format a rung is judged by is read from its competitions`() {
        // Not assumed: a level that is red-ball in one database and white-ball
        // in another has to be judged by the right standard.
        val world = world(stateRating = 60)
        assertEquals(Fixtures.T20, Progression.formatAt(LadderLevel.STATE_FIRST_CLASS, world))
    }

    // ---- determinism -------------------------------------------------------

    @Test
    fun `the same seed gives the same verdict`() {
        val world = world(stateRating = 55)
        val man = player("YOU", 58)

        assertEquals(review(man, world, district, seed = 5), review(man, world, district, seed = 5))
    }
}
