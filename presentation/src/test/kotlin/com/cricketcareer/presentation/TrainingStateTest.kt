package com.cricketcareer.presentation

import com.cricketcareer.engine.career.TrainingFocus
import com.cricketcareer.engine.career.TrainingWeek
import com.cricketcareer.engine.config.TrainingTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.HiddenAttributes
import com.cricketcareer.engine.model.player.Injury
import com.cricketcareer.engine.model.player.InjurySeverity
import com.cricketcareer.engine.model.player.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The training screen.
 *
 * Projections rather than promises, and the same arithmetic the model runs — a
 * screen with its own copy of the maths eventually disagrees with the save file
 * and the disagreement is invisible.
 */
class TrainingStateTest {

    private val tuning = TrainingTuning()

    private fun player(potential: Int = 80, fatigue: Double = 0.0): Player =
        Fixtures.averagePlayer("P").let {
            it.copy(
                hidden = HiddenAttributes.uniform(60).copy(potential = potential),
                state = it.state.copy(fatigue = fatigue),
            )
        }

    private fun state(
        week: TrainingWeek = TrainingWeek.allOn(TrainingFocus.BATTING_TECHNIQUE),
        subject: Player = player(),
        weeks: Int = 12,
    ) = trainingState(subject, week, coaching = 0.7, weeks = weeks, tuning = tuning)

    @Test
    fun `every focus gets a slider, whether or not it is being used`() {
        val sliders = state().sliders
        assertEquals(TrainingFocus.entries.size, sliders.size)
        assertEquals(TrainingWeek.TOTAL_POINTS, sliders.sumOf { it.points })
    }

    @Test
    fun `a slider says what it buys`() {
        val slider = state().sliders.single { it.focus == TrainingFocus.BATTING_TECHNIQUE }
        assertTrue(slider.covers.contains(Attribute.TECHNIQUE.displayName))
        assertTrue(slider.covers.isNotEmpty())
    }

    @Test
    fun `only the attributes this plan touches are projected`() {
        // A screen listing all forty attributes, thirty-six of them projecting
        // no change, is a screen nobody reads.
        val projections = state().projections
        assertTrue(projections.isNotEmpty())
        assertEquals(
            TrainingFocus.BATTING_TECHNIQUE.attributes.toSet(),
            projections.map { it.attribute }.toSet(),
        )
    }

    @Test
    fun `a block of weeks projects a gain a player can see`() {
        // One week is about a fifth of a point, which on a screen looks broken.
        val gains = state(weeks = 16).projections.map { it.gain }
        assertTrue(gains.any { it > 0 }) { "sixteen weeks on one area projected $gains" }
    }

    @Test
    fun `longer projects further`() {
        val short = state(weeks = 4).projections.first().projected
        val long = state(weeks = 40).projections.first().projected
        assertTrue(long >= short) { "four weeks $short, forty weeks $long" }
    }

    @Test
    fun `a player at his ceiling is told the work will not show`() {
        val maxed = player(potential = 50)
        val projection = state(subject = maxed).projections.first { it.attribute == Attribute.TECHNIQUE }

        assertTrue(projection.atCeiling) { "now ${projection.now}, ceiling ${projection.ceiling}" }
        assertEquals(0, projection.gain)
    }

    @Test
    fun `the allocation line says what is spent`() {
        assertEquals("100 of 100 points allocated", state().allocation)
        assertTrue(state().isLegal)
    }

    @Test
    fun `a rest week has no intensity and a hard one has all of it`() {
        assertEquals(0.0, state(week = TrainingWeek.allOn(TrainingFocus.REST)).intensity, 1e-9)
        assertEquals(1.0, state().intensity, 1e-9)
    }

    // ---- the warning --------------------------------------------------------

    @Test
    fun `a fresh player is not nagged`() {
        assertNull(state().overloadWarning)
    }

    @Test
    fun `a tired player training hard is told`() {
        val tired = player(fatigue = 0.8)
        assertNotNull(state(subject = tired).overloadWarning)
    }

    @Test
    fun `a tired player resting is not`() {
        val tired = player(fatigue = 0.8)
        assertNull(state(week = TrainingWeek.allOn(TrainingFocus.REST), subject = tired).overloadWarning)
    }

    @Test
    fun `an injured player is told before anything else`() {
        val hurt = player().let { it.copy(state = it.state.copy(injury = Injury("Hamstring", InjurySeverity.MODERATE, 21))) }
        assertTrue(checkNotNull(state(subject = hurt).overloadWarning).startsWith("Injured"))
    }
}
