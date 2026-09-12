package com.cricketcareer.presentation

import com.cricketcareer.engine.career.Selection
import com.cricketcareer.engine.career.SelectionContext
import com.cricketcareer.engine.career.SelectorPersonality
import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The selection screen.
 *
 * A player is never told "not selected" and left to guess. The panel's own
 * terms are shown, weighted, so "sixth-best on ability and the side needed a
 * fourth seamer" is readable rather than inferred.
 */
class SelectionStateTest {

    private val tuning = CareerTuning.DEFAULT.selection

    private fun squad(): List<Player> =
        Fixtures.averageXI("A").plus(Fixtures.averageXI("B").take(7)).mapIndexed { i, player ->
            player.copy(id = PlayerId("P$i"), attributes = Attributes.uniform(40 + i * 3))
        }

    private fun ranked(candidates: List<Player> = squad()) = Selection.score(
        candidates = candidates,
        context = SelectionContext(Fixtures.T20, Pitch.AVERAGE),
        personality = SelectorPersonality.AVERAGE,
        random = SimRandom.fromSeed(3),
        tuning = tuning,
    )

    private fun picked(candidates: List<Player> = squad()) = Selection.pickXI(
        candidates = candidates,
        context = SelectionContext(Fixtures.T20, Pitch.AVERAGE),
        personality = SelectorPersonality.AVERAGE,
        random = SimRandom.fromSeed(3),
        tuning = tuning,
    ).map { it.player.id }.toSet()

    @Test
    fun `every candidate gets a row, ranked`() {
        val state = selectionState(ranked(), picked())
        assertEquals(18, state.rows.size)
        assertEquals((1..18).toList(), state.rows.map { it.rank })
    }

    @Test
    fun `the rows agree with the team sheet`() {
        // Nothing here re-decides anything. A screen that recomputed the scores
        // with its own weights would eventually disagree with the XI, and the
        // disagreement would be invisible.
        val chosen = picked()
        val state = selectionState(ranked(), chosen)

        assertEquals(11, state.rows.count { it.picked })
        assertEquals(chosen, state.rows.filter { it.picked }.map { it.player }.toSet())
    }

    @Test
    fun `the reasons are the panel's own terms, in the order that decided it`() {
        val row = selectionState(ranked(), picked()).rows.first()
        assertEquals("Ability for this format", row.reasons.first().label)
        // Risk subtracts, and a reader scanning down should meet it last. It is
        // shown even at zero: "no fitness concern" is an answer a player wants,
        // and a line that vanishes when it is fine reads as an omission.
        assertEquals("Fitness and sharpness", row.reasons.last().label)
    }

    @Test
    fun `a fit player is still told his fitness is not the problem`() {
        val state = selectionState(ranked(), picked())
        state.rows.forEach { row ->
            assertTrue(row.reasons.any { it.label == "Fitness and sharpness" }) { "${row.name} has no fitness line" }
        }
    }

    @Test
    fun `a term that helped reads as a plus and one that hurt as a minus`() {
        val reasons = selectionState(ranked(), picked()).rows.first().reasons
        reasons.forEach { reason ->
            assertEquals(reason.value >= 0.0, reason.helps)
            assertEquals(reason.value >= 0.0, reason.display.startsWith("+"))
        }
    }

    @Test
    fun `conditions are measured against the other candidates, not against a half`() {
        // A batter's suitability is how well *he* plays what the pitch will do,
        // which carries his ability in it. Showing it against a flat 0.5
        // labelled every good player as suited to every surface, and the
        // conditions line became a second, quieter ability line.
        val state = selectionState(ranked(), picked())
        val conditions = state.rows.mapNotNull { row ->
            row.reasons.firstOrNull { it.label == "Suits these conditions" }?.value
        }

        assertTrue(conditions.isNotEmpty())
        assertTrue(kotlin.math.abs(conditions.average()) < 1e-9) {
            "across the whole squad the conditions should cancel, got %.3f".format(conditions.average())
        }
        assertTrue(conditions.any { it > 0 } && conditions.any { it < 0 }) {
            "somebody has to be better suited than average and somebody worse"
        }
    }

    @Test
    fun `a player is told where he came and whether he is in`() {
        val list = ranked()
        val chosen = picked()
        val inSide = list.first { it.player.id in chosen }.player.id
        val out = list.last { it.player.id !in chosen }.player.id

        assertTrue(checkNotNull(selectionState(list, chosen, inSide).verdict).startsWith("Picked"))
        assertTrue(checkNotNull(selectionState(list, chosen, out).verdict).startsWith("Left out"))
    }

    @Test
    fun `a man ranked inside the XI and left out is told it was the balance`() {
        // The only honest explanation for it, and the one a player most wants.
        val list = ranked()
        val chosen = picked()
        val squeezed = list.take(11).firstOrNull { it.player.id !in chosen }?.player?.id
        if (squeezed != null) {
            assertTrue(checkNotNull(selectionState(list, chosen, squeezed).verdict).contains("balance"))
        }
    }

    @Test
    fun `somebody not under consideration gets no verdict`() {
        // "You were ranked nowhere" is not an explanation.
        assertNull(selectionState(ranked(), picked(), PlayerId("NOBODY")).verdict)
    }

    @Test
    fun `the unluckiest man is the best one left out`() {
        val state = selectionState(ranked(), picked())
        val unlucky = checkNotNull(state.unluckiest)

        assertTrue(!unlucky.picked)
        assertTrue(state.rows.filter { !it.picked }.all { it.rank >= unlucky.rank })
    }
}
