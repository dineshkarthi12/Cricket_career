package com.cricketcareer.engine.fixtures

import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.world.MatchFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the definition of "average".
 *
 * Every calibration band in docs/CALIBRATION.md is measured against these
 * objects. If someone quietly makes the fixture XI a bit better, every band in
 * the log silently stops meaning what it says — and nothing else in the build
 * would notice.
 */
class FixturesTest {

    @Test
    fun `the average player is exactly average at everything`() {
        val player = Fixtures.averagePlayer("t1")
        Attribute.ALL.forEach { attribute ->
            assertEquals(
                Fixtures.AVERAGE_RATING,
                player.attributes[attribute],
                "${attribute.name} is not ${Fixtures.AVERAGE_RATING}",
            )
        }
        assertEquals(Fixtures.AVERAGE_RATING, player.hidden.potential)
    }

    @Test
    fun `the average pitch is at the midpoint of every parameter`() {
        val pitch = Fixtures.AVERAGE_PITCH
        assertEquals(0.5, pitch.hardness)
        assertEquals(0.5, pitch.turn)
        assertEquals(0.5, pitch.bounce)
        assertEquals(0.0, pitch.deterioration, "an average pitch starts unworn")
    }

    @Test
    fun `the average XI can field a real side`() {
        val xi = Fixtures.averageXI("HOME")
        assertEquals(11, xi.size)
        assertEquals(11, xi.map { it.id }.distinct().size)
        assertEquals(1, xi.count { it.keeps }, "an XI needs exactly one keeper")
        assertTrue(xi.count { it.bowls && it.bowlingStyle.isPace } >= 3, "not enough seamers")
        assertTrue(xi.count { it.bowls && it.bowlingStyle.isSpin } >= 1, "no spinner")
        assertTrue(
            xi.count { it.bowls } >= 5,
            "an XI needs five bowling options to get through ${MatchFormat.LIST_A.oversPerInnings} overs",
        )
    }

    @Test
    fun `the two average sides are distinct players`() {
        val (home, away) = Fixtures.averageTeams()
        assertTrue(home.map { it.id }.none { it in away.map { p -> p.id } })
    }

    @Test
    fun `the average ground is symmetric`() {
        val boundary = Fixtures.AVERAGE_VENUE.boundary
        assertEquals(boundary.squareOffMetres, boundary.squareLegMetres)
    }
}
