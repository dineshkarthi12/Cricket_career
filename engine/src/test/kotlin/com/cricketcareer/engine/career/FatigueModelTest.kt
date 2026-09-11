package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.FatigueTuning
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.PlayerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FatigueModelTest {

    private val tuning = FatigueTuning()
    private val average = Attributes.uniform(50)

    @Test
    fun `a seamer's long day costs more than a spinner's`() {
        val seam = FatigueModel.afterMatch(PlayerState.FRESH, MatchWorkload(oversBowledPace = 25.0), tuning)
        val spin = FatigueModel.afterMatch(PlayerState.FRESH, MatchWorkload(oversBowledSpin = 25.0), tuning)
        assertTrue(seam.fatigue > spin.fatigue) { "seam ${seam.fatigue} vs spin ${spin.fatigue}" }
    }

    @Test
    fun `turning up costs something even doing nothing`() {
        // Otherwise a congested calendar does not bite a specialist batter who
        // keeps getting out for a duck, which is the wrong incentive entirely.
        val after = FatigueModel.afterMatch(PlayerState.FRESH, MatchWorkload(), tuning)
        assertTrue(after.fatigue > 0.0) { "fatigue was ${after.fatigue}" }
        assertEquals(tuning.fatiguePerMatchAppearance, after.fatigue, 1e-9)
    }

    @Test
    fun `a Test seamer's day is a real cost and a T20 opener's is not`() {
        val testDay = FatigueModel.afterMatch(
            PlayerState.FRESH,
            MatchWorkload(oversBowledPace = 24.0, ballsFaced = 12, fieldingHours = 5.0),
            tuning,
        )
        val t20Day = FatigueModel.afterMatch(
            PlayerState.FRESH,
            MatchWorkload(ballsFaced = 40, fieldingHours = 1.6),
            tuning,
        )
        assertTrue(testDay.fatigue > 0.25) { "a 24-over day should bite: ${testDay.fatigue}" }
        assertTrue(t20Day.fatigue < 0.1) { "a T20 knock should not: ${t20Day.fatigue}" }
    }

    @Test
    fun `a long innings tires a batter`() {
        val long = FatigueModel.afterMatch(PlayerState.FRESH, MatchWorkload(ballsFaced = 250), tuning)
        val short = FatigueModel.afterMatch(PlayerState.FRESH, MatchWorkload(ballsFaced = 10), tuning)
        assertTrue(long.fatigue > short.fatigue) { "${long.fatigue} vs ${short.fatigue}" }
    }

    @Test
    fun `rest recovers fatigue and more rest recovers more`() {
        val tired = PlayerState.FRESH.withFatigueChange(0.7)
        val threeDays = FatigueModel.rest(tired, 3, average, tuning)
        val tenDays = FatigueModel.rest(tired, 10, average, tuning)
        assertTrue(threeDays.fatigue < tired.fatigue)
        assertTrue(tenDays.fatigue < threeDays.fatigue)
    }

    @Test
    fun `recovery is exponential, so the last of it is the slow part`() {
        val tired = PlayerState.FRESH.withFatigueChange(0.8)
        val first = tired.fatigue - FatigueModel.rest(tired, 1, average, tuning).fatigue
        val after5 = FatigueModel.rest(tired, 5, average, tuning)
        val sixth = after5.fatigue - FatigueModel.rest(tired, 6, average, tuning).fatigue
        assertTrue(first > sixth) { "day 1 cleared $first, day 6 cleared $sixth" }
    }

    @Test
    fun `a fit player clears a congested week and an unfit one carries it`() {
        val fit = Attributes.uniform(50)
            .with(Attribute.FITNESS, 90).with(Attribute.STAMINA, 90)
        val unfit = Attributes.uniform(50)
            .with(Attribute.FITNESS, 20).with(Attribute.STAMINA, 20)
        val tired = PlayerState.FRESH.withFatigueChange(0.6)
        val fitAfter = FatigueModel.rest(tired, 4, fit, tuning)
        val unfitAfter = FatigueModel.rest(tired, 4, unfit, tuning)
        assertTrue(fitAfter.fatigue < unfitAfter.fatigue) {
            "fit ${fitAfter.fatigue} should beat unfit ${unfitAfter.fatigue}"
        }
    }

    @Test
    fun `zero rest days change nothing`() {
        val tired = PlayerState.FRESH.withFatigueChange(0.4)
        assertEquals(tired, FatigueModel.rest(tired, 0, average, tuning))
    }

    @Test
    fun `fatigue is bounded however brutal the schedule`() {
        var state = PlayerState.FRESH
        repeat(200) {
            state = FatigueModel.afterMatch(state, MatchWorkload(oversBowledPace = 30.0, fieldingHours = 6.0), tuning)
        }
        assertEquals(1.0, state.fatigue, 1e-9)
    }

    @Test
    fun `fatigue never goes negative however long the rest`() {
        val state = FatigueModel.rest(PlayerState.FRESH.withFatigueChange(0.3), 5000, average, tuning)
        assertTrue(state.fatigue >= 0.0) { "fatigue ${state.fatigue}" }
    }
}
