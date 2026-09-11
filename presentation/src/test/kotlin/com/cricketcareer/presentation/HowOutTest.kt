package com.cricketcareer.presentation

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.state.Dismissal
import com.cricketcareer.engine.match.state.DismissalMode
import com.cricketcareer.engine.model.player.PersonName
import com.cricketcareer.engine.model.player.PlayerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HowOutTest {

    private val batter = PlayerId("BAT")
    private val kadam = PlayerId("BOWL")
    private val rane = PlayerId("FIELD")

    private val names = Names(
        listOf(
            Fixtures.averagePlayer("BAT").copy(id = batter, name = PersonName("Suresh", "Iyer")),
            Fixtures.averagePlayer("BOWL").copy(id = kadam, name = PersonName("Manoj", "Kadam")),
            Fixtures.averagePlayer("FIELD").copy(id = rane, name = PersonName("Vikram", "Rane")),
        ),
    )

    private fun out(mode: DismissalMode, bowler: PlayerId? = kadam, fielder: PlayerId? = null) =
        HowOut.describe(Dismissal(mode, batter, bowler, fielder), names)

    @Test
    fun `bowled is b Kadam, not b b Kadam`() {
        // The bug this module exists for. The dismissal mode's own short name
        // IS the "b", so pasting one in front of the other doubles it - and it
        // is right for lbw, stumped and run out, so it survives review.
        assertEquals("b Kadam", out(DismissalMode.BOWLED))
    }

    @Test
    fun `caught names the fielder then the bowler`() {
        assertEquals("c Rane b Kadam", out(DismissalMode.CAUGHT, fielder = rane))
    }

    @Test
    fun `caught and bowled has its own notation`() {
        // Never "c Kadam b Kadam".
        assertEquals("c & b Kadam", out(DismissalMode.CAUGHT, bowler = kadam, fielder = kadam))
    }

    @Test
    fun `lbw takes a b clause`() {
        assertEquals("lbw b Kadam", out(DismissalMode.LBW))
    }

    @Test
    fun `stumped credits the keeper before the bowler`() {
        assertEquals("st Rane b Kadam", out(DismissalMode.STUMPED, fielder = rane))
    }

    @Test
    fun `hit wicket takes a b clause`() {
        assertEquals("hit wicket b Kadam", out(DismissalMode.HIT_WICKET))
    }

    @Test
    fun `a run out credits no bowler however tempting the symmetry`() {
        assertEquals("run out (Rane)", out(DismissalMode.RUN_OUT, bowler = null, fielder = rane))
        assertTrue("b " !in out(DismissalMode.RUN_OUT, bowler = null, fielder = rane))
    }

    @Test
    fun `the modes that credit nobody say only what happened`() {
        assertEquals("obstructing the field", out(DismissalMode.OBSTRUCTING_THE_FIELD, bowler = null))
        assertEquals("timed out", out(DismissalMode.TIMED_OUT, bowler = null))
        assertEquals("hit the ball twice", out(DismissalMode.HIT_THE_BALL_TWICE, bowler = null))
        assertEquals("retired out", out(DismissalMode.RETIRED_OUT, bowler = null))
    }

    @Test
    fun `an unbeaten batter and one who never batted are not the same thing`() {
        // 0* off 14 balls and a man who was padded up all afternoon both have
        // no dismissal, and a scorecard must not confuse them.
        assertEquals(HowOut.NOT_OUT, HowOut.describe(null, names, faced = true))
        assertEquals(HowOut.DID_NOT_BAT, HowOut.describe(null, names, faced = false))
    }

    @Test
    fun `a missing name is visibly wrong rather than a crash`() {
        val stranger = PlayerId("NOBODY")
        val text = HowOut.describe(Dismissal(DismissalMode.BOWLED, batter, stranger), Names.EMPTY)
        assertTrue(text.contains("NOBODY")) { "got $text" }
    }

    @Test
    fun `every dismissal mode produces something readable`() {
        // A mode added later must not silently render as an empty string.
        DismissalMode.entries.forEach { mode ->
            val text = HowOut.describe(Dismissal(mode, batter, kadam, rane), names)
            assertTrue(text.isNotBlank()) { "$mode produced nothing" }
            assertTrue(!text.contains("null")) { "$mode produced $text" }
        }
    }
}
