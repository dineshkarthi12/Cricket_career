package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.FormTuning
import com.cricketcareer.engine.model.player.PlayerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class FormModelTest {

    private val tuning = FormTuning()

    private fun innings(actual: Double, expected: Double = 32.0, sd: Double = 28.0) =
        Performance(actual = actual, expected = expected, expectedStdDev = sd)

    @Test
    fun `surprise is measured in standard deviations`() {
        assertEquals(0.0, innings(32.0).surprise, 1e-9)
        assertEquals(1.0, innings(60.0).surprise, 1e-9)
        assertEquals(-1.0, innings(4.0).surprise, 1e-9)
    }

    @Test
    fun `thirty in a low-scoring Test beats thirty in a T20`() {
        // The whole reason for dividing by the spread. Same runs, different game.
        val test = innings(actual = 30.0, expected = 22.0, sd = 20.0)
        val t20 = innings(actual = 30.0, expected = 28.0, sd = 26.0)
        assertTrue(test.surprise > t20.surprise) {
            "Test 30 (${test.surprise}) should beat T20 30 (${t20.surprise})"
        }
    }

    @Test
    fun `a good innings lifts form and confidence`() {
        val after = FormModel.afterPerformance(PlayerState.FRESH, innings(90.0), temperament = 50, tuning = tuning)
        assertTrue(after.form > 0.0) { "form was ${after.form}" }
        assertTrue(after.confidence > after.form) { "confidence ${after.confidence} should outrun form ${after.form}" }
    }

    @Test
    fun `a failure costs form`() {
        val after = FormModel.afterPerformance(PlayerState.FRESH, innings(0.0), temperament = 50, tuning = tuning)
        assertTrue(after.form < 0.0) { "form was ${after.form}" }
    }

    @Test
    fun `one enormous score does not buy a decade`() {
        // tanh saturation: the fifth standard deviation is worth almost nothing
        // on top of the second.
        val big = FormModel.afterPerformance(PlayerState.FRESH, innings(120.0), 50, tuning).form
        val absurd = FormModel.afterPerformance(PlayerState.FRESH, innings(900.0), 50, tuning).form
        assertTrue(absurd < big * 1.6) { "300* ($absurd) should not be worth much more than a hundred ($big)" }
        assertTrue(absurd <= tuning.formGainPerSigma * 1.9) { "form gain $absurd should stay bounded" }
    }

    @Test
    fun `a volatile player swings further than a metronome`() {
        val volatile = FormModel.afterPerformance(PlayerState.FRESH, innings(90.0), temperament = 10, tuning = tuning)
        val steady = FormModel.afterPerformance(PlayerState.FRESH, innings(90.0), temperament = 95, tuning = tuning)
        assertTrue(volatile.form > steady.form) {
            "low temperament (${volatile.form}) should swing further than high (${steady.form})"
        }
        // And in the other direction too, or it is a bonus rather than volatility.
        val volatileBad = FormModel.afterPerformance(PlayerState.FRESH, innings(0.0), 10, tuning)
        val steadyBad = FormModel.afterPerformance(PlayerState.FRESH, innings(0.0), 95, tuning)
        assertTrue(volatileBad.form < steadyBad.form) {
            "low temperament (${volatileBad.form}) should fall further than high (${steadyBad.form})"
        }
    }

    @Test
    fun `playing restores sharpness`() {
        val rusty = PlayerState.FRESH.copy(sharpness = 0.5)
        val after = FormModel.afterPerformance(rusty, innings(20.0), 50, tuning)
        assertTrue(after.sharpness > rusty.sharpness) { "sharpness was ${after.sharpness}" }
    }

    @Test
    fun `form ebbs toward neutral and confidence ebbs faster`() {
        var state = PlayerState.FRESH.copy(form = 0.8, confidence = 0.8)
        repeat(14) { state = FormModel.restDay(state, tuning) }
        assertTrue(state.form in 0.0..0.8) { "form ${state.form}" }
        assertTrue(state.confidence < state.form) {
            "confidence ${state.confidence} should decay faster than form ${state.form}"
        }
        // A fortnight off should not erase a purple patch.
        assertTrue(state.form > 0.4) { "a fortnight cost too much form: ${state.form}" }
    }

    @Test
    fun `two months out leaves a player visibly short of cricket`() {
        var state = PlayerState.FRESH
        repeat(60) { state = FormModel.restDay(state, tuning) }
        assertTrue(state.sharpness in 0.1..0.8) { "sharpness after two months: ${state.sharpness}" }
    }

    @Test
    fun `sharpness and form stay inside their ranges however long the layoff`() {
        var state = PlayerState.FRESH.copy(form = -1.0, confidence = 1.0)
        repeat(4000) { state = FormModel.restDay(state, tuning) }
        assertTrue(state.sharpness >= 0.0) { "sharpness ${state.sharpness}" }
        assertTrue(abs(state.form) <= 1.0 && abs(state.confidence) <= 1.0)
    }

    @Test
    fun `a performance with no spread is rejected rather than dividing by zero`() {
        assertThrows<IllegalArgumentException> { Performance(30.0, 30.0, 0.0) }
    }
}
