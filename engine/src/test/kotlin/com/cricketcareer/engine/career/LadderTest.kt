package com.cricketcareer.engine.career

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.Country
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.Region
import com.cricketcareer.engine.model.world.Team
import com.cricketcareer.engine.seed.SeedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Who a player is allowed to be picked for.
 *
 * Geography only — whether he is good enough is `Selection`'s question. The
 * thing being defended here is that the ladder is *seed data*: this test builds
 * a two-country world by hand and the same code reads a pyramid out of it that
 * it would read out of the shipped database.
 */
class LadderTest {

    private val venue = Fixtures.AVERAGE_VENUE

    private fun team(id: String, level: LadderLevel, region: String, country: String = "IND") = Team(
        id = id,
        name = id,
        shortName = id.takeLast(3),
        country = country,
        region = region,
        level = level,
        homeVenue = venue.id,
    )

    private fun player(id: String, region: String, country: String = "IND"): Player =
        Fixtures.averagePlayer(id).copy(country = country, region = region)

    private val india = Country(
        id = "IND",
        name = "India",
        regions = listOf(
            Region(id = "Maharashtra", name = "Maharashtra", zone = "IND-ZONE-WEST"),
            Region(id = "Bengal", name = "Bengal", zone = "IND-ZONE-EAST"),
            // A region with no zone: a world need not have a zonal rung at all.
            Region(id = "Mumbai", name = "Mumbai"),
        ),
    )

    private val australia = Country(id = "AUS", name = "Australia", regions = listOf(Region("Victoria", "Victoria")))

    private val world = SeedDatabase(
        countries = listOf(india, australia),
        venues = listOf(venue),
        teams = listOf(
            team("IND-MH-PUNE", LadderLevel.DISTRICT_CLUB, "Maharashtra"),
            team("IND-MH-NASHIK", LadderLevel.DISTRICT_CLUB, "Maharashtra"),
            team("IND-BEN-KOLKATA", LadderLevel.DISTRICT_CLUB, "Bengal"),
            team("IND-MH", LadderLevel.STATE_FIRST_CLASS, "Maharashtra"),
            team("IND-BEN", LadderLevel.STATE_FIRST_CLASS, "Bengal"),
            team("IND-MH-WB", LadderLevel.STATE_WHITE_BALL, "Maharashtra"),
            team("IND-ZONE-WEST", LadderLevel.ZONAL, "Maharashtra"),
            team("IND-ZONE-EAST", LadderLevel.ZONAL, "Bengal"),
            team("IND-T20-CHENNAI", LadderLevel.FRANCHISE_T20, "Chennai"),
            team("IND-T20-KOLKATA", LadderLevel.FRANCHISE_T20, "Kolkata"),
            team("IND-INTL", LadderLevel.INTERNATIONAL, "India"),
            team("AUS-INTL", LadderLevel.INTERNATIONAL, "Australia", country = "AUS"),
        ),
    )

    private fun rungs(player: Player) = Ladder.forPlayer(player, world)

    @Test
    fun `a player climbs his own district, his own state, his own zone, then the country`() {
        val ladder = rungs(player("MH", "Maharashtra"))

        assertEquals(
            listOf(
                LadderLevel.DISTRICT_CLUB,
                LadderLevel.STATE_FIRST_CLASS,
                LadderLevel.STATE_WHITE_BALL,
                LadderLevel.ZONAL,
                LadderLevel.FRANCHISE_T20,
                LadderLevel.INTERNATIONAL,
            ),
            ladder.map { it.level },
        )
    }

    @Test
    fun `a regional side picks only from its region`() {
        val ladder = rungs(player("MH", "Maharashtra")).associateBy { it.level }

        assertEquals(
            listOf("IND-MH-PUNE", "IND-MH-NASHIK"),
            checkNotNull(ladder[LadderLevel.DISTRICT_CLUB]).teams.map { it.id },
        )
        assertEquals(listOf("IND-MH"), checkNotNull(ladder[LadderLevel.STATE_FIRST_CLASS]).teams.map { it.id })
    }

    @Test
    fun `a zonal side picks from the regions that feed it`() {
        val west = rungs(player("MH", "Maharashtra")).single { it.level == LadderLevel.ZONAL }
        val east = rungs(player("BEN", "Bengal")).single { it.level == LadderLevel.ZONAL }

        assertEquals(listOf("IND-ZONE-WEST"), west.teams.map { it.id })
        assertEquals(listOf("IND-ZONE-EAST"), east.teams.map { it.id })
    }

    @Test
    fun `a region with no zone has no zonal rung, and the ladder goes on without it`() {
        // The state sides here are Maharashtra's and Bengal's, so a Mumbai
        // player has neither — what is left is the part that picks nationally.
        val ladder = rungs(player("MUM", "Mumbai"))

        assertEquals(listOf(LadderLevel.FRANCHISE_T20, LadderLevel.INTERNATIONAL), ladder.map { it.level })
    }

    @Test
    fun `a franchise can bid for anyone in the country`() {
        listOf("Maharashtra", "Bengal", "Mumbai").forEach { region ->
            val franchises = rungs(player("P-$region", region)).single { it.level == LadderLevel.FRANCHISE_T20 }
            assertEquals(2, franchises.teams.size, "a $region player should be open to both franchises")
        }
    }

    @Test
    fun `nobody plays for another country`() {
        val indian = rungs(player("IND", "Maharashtra"))
        assertTrue(indian.none { rung -> rung.teams.any { it.country != "IND" } })

        val australian = rungs(player("AUS", "Victoria", country = "AUS"))
        assertEquals(listOf(LadderLevel.INTERNATIONAL), australian.map { it.level })
        assertEquals(listOf("AUS-INTL"), australian.single().teams.map { it.id })
    }

    @Test
    fun `a zone in the right country but the wrong zone is not an option`() {
        val bengali = player("BEN", "Bengal")
        val west = world.teams.single { it.id == "IND-ZONE-WEST" }
        assertFalse(Ladder.eligible(bengali, west, world))
    }

    // ---- moving between rungs ---------------------------------------------

    @Test
    fun `the next rung up skips levels the world has no teams on`() {
        // Nothing here plays age-group cricket, so the step above a district
        // side is the state side, not a stall against an empty level.
        val player = player("MH", "Maharashtra")
        val next = Ladder.nextRung(player, LadderLevel.DISTRICT_CLUB, world)

        assertEquals(LadderLevel.STATE_FIRST_CLASS, checkNotNull(next).level)
    }

    @Test
    fun `there is nothing above an international`() {
        assertNull(Ladder.nextRung(player("MH", "Maharashtra"), LadderLevel.INTERNATIONAL, world))
    }

    @Test
    fun `a dropped player falls to the rung below, not to the bottom`() {
        val player = player("MH", "Maharashtra")
        val below = Ladder.previousRung(player, LadderLevel.ZONAL, world)

        assertEquals(LadderLevel.STATE_WHITE_BALL, checkNotNull(below).level)
    }

    @Test
    fun `there is nothing below a district cricketer`() {
        assertNull(Ladder.previousRung(player("MH", "Maharashtra"), LadderLevel.DISTRICT_CLUB, world))
    }

    @Test
    fun `every step up is a step up`() {
        // Walk the whole ladder and check it only ever goes one way.
        val player = player("MH", "Maharashtra")
        var level = LadderLevel.DISTRICT_CLUB
        val climbed = mutableListOf(level)
        while (true) {
            val next = Ladder.nextRung(player, level, world) ?: break
            assertTrue(next.level.ordinal > level.ordinal)
            level = next.level
            climbed += level
        }
        assertEquals(LadderLevel.INTERNATIONAL, climbed.last())
        assertEquals(climbed, rungs(player).map { it.level })
    }
}
