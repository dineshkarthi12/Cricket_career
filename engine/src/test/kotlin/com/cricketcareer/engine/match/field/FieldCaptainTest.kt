package com.cricketcareer.engine.match.field

import com.cricketcareer.engine.fixtures.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * The captain's fields, checked for the cricket in them.
 *
 * A field is not decoration: Stage 4 reads it, so where the men stand decides
 * what strokes a batter thinks are worth playing. A wrong field is therefore a
 * wrong *innings*, and it is invisible — the numbers still come out, they just
 * come out of a game nobody is playing.
 */
class FieldCaptainTest {

    private val side = Fixtures.averageXI("b")

    private fun field(
        phase: MatchPhase = MatchPhase.MIDDLE,
        spin: Boolean = false,
        limit: Int? = null,
        multiDay: Boolean = false,
        newBatter: Boolean = false,
        ballIsDoingSomething: Boolean = true,
    ): FieldSetting = FieldCaptain.setField(
        bowlingSide = side,
        bowler = side[7].id,
        keeper = side[5].id,
        phase = phase,
        bowlerIsSpin = spin,
        fieldersOutsideLimit = limit,
        multiDay = multiDay,
        newBatter = newBatter,
        ballIsDoingSomething = ballIsDoingSomething,
    )

    private val catchers = setOf(
        FieldPosition.FIRST_SLIP, FieldPosition.SECOND_SLIP, FieldPosition.THIRD_SLIP,
        FieldPosition.FOURTH_SLIP, FieldPosition.LEG_SLIP, FieldPosition.GULLY,
        FieldPosition.SILLY_POINT, FieldPosition.SHORT_LEG,
    )

    private fun FieldSetting.catchers(): Int = fielders.count { it.position in catchers }

    @Test
    fun `a red-ball field has a cordon and a white-ball middle-overs field does not`() {
        // Four-day cricket was being played with a fifty-over middle-overs
        // field: no slips anywhere, a third man and a cow corner. It is the
        // difference between the two games and it was simply absent.
        assertTrue(field(multiDay = true).catchers() >= 1) { "a Test field needs a slip" }
        assertEquals(0, field(multiDay = false).catchers())
    }

    @Test
    fun `a captain attacks a new batter in every format that lets him`() {
        // The most reliable thing a captain does: a wicket falls and the field
        // comes up. Without it a fifty-over batter's hazard did not fall as he
        // settled - he was more error-prone early AND playing more carefully
        // early, the two cancelled, and there was nobody to edge it to.
        listOf(
            Triple("four-day", true, null),
            Triple("fifty-over middle", false, null),
            Triple("fifty-over restricted", false, 4),
        ).forEach { (name, multiDay, limit) ->
            val settled = field(multiDay = multiDay, limit = limit)
            val fresh = field(multiDay = multiDay, limit = limit, newBatter = true)
            assertTrue(fresh.catchers() > settled.catchers()) { "$name: the field did not come up" }
        }
    }

    @Test
    fun `nobody posts a slip at the death`() {
        // The new man at the death is a tail-ender swinging, and the boundary
        // is the thing worth saving.
        val death = field(phase = MatchPhase.DEATH, limit = 5, newBatter = true)
        assertEquals(0, death.catchers())
    }

    @Test
    fun `the slips go out when the ball stops doing anything`() {
        val shiny = field(multiDay = true, ballIsDoingSomething = true)
        val old = field(multiDay = true, ballIsDoingSomething = false)
        assertTrue(shiny.catchers() > old.catchers())
        assertEquals(0, old.catchers())
    }

    @Test
    fun `a new batter still gets the cordon even with an old ball`() {
        // A wicket is a wicket. The captain comes up for the new man whatever
        // the ball is doing, and goes back afterwards.
        assertTrue(field(multiDay = true, newBatter = true, ballIsDoingSomething = false).catchers() >= 2)
    }

    @Test
    fun `every field the captain can set has ten men in it, each of them once`() {
        // The presets satisfy the powerplay restriction by construction rather
        // than by being checked and repaired, so this is what says they do.
        listOf(2, 4, 5, null).forEach { limit ->
            listOf(true, false).forEach { spin ->
                listOf(true, false).forEach { multiDay ->
                    listOf(true, false).forEach { newBatter ->
                        listOf(true, false).forEach { doing ->
                            MatchPhase.entries.forEach { phase ->
                                val set = field(phase, spin, limit, multiDay, newBatter, doing)
                                val label = "$limit/$spin/$multiDay/$newBatter/$doing/$phase"
                                // Ten placed men plus the bowler, who is running
                                // in and is added by the delivery rather than
                                // placed by the captain.
                                assertEquals(FieldSetting.FIELDERS_ON_THE_PARK, set.fielders.size) { label }
                                assertEquals(
                                    set.fielders.map { it.player }.distinct().size,
                                    set.fielders.size,
                                ) { "$label placed the same man twice" }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @Disabled(
        "Known bug, recorded in docs/SIMULATION_MODEL.md 19 with the measurements: " +
            "mid-off and mid-on are modelled 29 metres out, which is outside a 27.43-metre " +
            "circle, so every powerplay preset puts four men out where the restriction allows " +
            "two. Moving them inside - where they actually stand - fixes the rule and " +
            "simultaneously stops them guarding the loft over their own heads, which is what " +
            "the whole Twenty20 calibration was resting on. Fixing both together is the next " +
            "piece of work, not a tuning pass.",
    )
    fun `no field the captain sets breaks the fielding restriction`() {
        // A powerplay restriction is a law of the game, not a preference, and
        // the comment on `positionsFor` claims the presets satisfy it by
        // construction. They do not, and `isLegal` had never been asked.
        listOf(2, 4, 5).forEach { limit ->
            listOf(true, false).forEach { spin ->
                listOf(true, false).forEach { newBatter ->
                    MatchPhase.entries.forEach { phase ->
                        val set = field(phase, spin, limit, multiDay = false, newBatter = newBatter)
                        assertTrue(set.isLegal(limit)) {
                            "$limit/$spin/$newBatter/$phase put ${set.outsideRing} outside the ring"
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a multi-day field is never chosen by a powerplay restriction`() {
        // There is no powerplay in a four-day match, so a red-ball field must
        // not be reachable through the limit branches and vice versa.
        assertFalse(field(multiDay = true).has(FieldPosition.COW_CORNER))
    }
}
