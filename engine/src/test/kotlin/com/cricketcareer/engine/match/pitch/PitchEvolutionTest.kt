package com.cricketcareer.engine.match.pitch

import com.cricketcareer.engine.config.PitchTuning
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.model.world.PitchArchetype
import com.cricketcareer.engine.model.world.SoilType
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The brief's requirement for a five-day pitch, as tests: day one belongs to
 * the seamers, days two and three to the batters, days four and five to the
 * spinners and to variable bounce.
 */
class PitchEvolutionTest {

    private val tuning = PitchTuning.DEFAULT

    private fun wear(pitch: Pitch, sessions: Int, seed: Long = 1L): Pitch {
        val rng = SimRandom.fromSeed(seed)
        var current = pitch
        repeat(sessions) { i ->
            current = PitchEvolution.afterSession(
                pitch = current,
                weather = Weather.AVERAGE,
                session = Session.entries[i % 3],
                oversThisSession = tuning.sessionOvers,
                uncoveredRain = 0.0,
                tuning = tuning,
                rng = rng,
            )
        }
        return current
    }

    @Test
    fun `the first morning is the seamer's and it does not last`() {
        val fresh = PitchArchetype.GREEN_SEAMER.generate(SimRandom.fromSeed(4L), SoilType.CLAY)
        val dayTwo = wear(fresh, sessions = 3)
        assertTrue(dayTwo.moisture < fresh.moisture, "the surface did not dry out overnight")
        assertTrue(dayTwo.grassCover < fresh.grassCover, "the grass did not wear")
        assertTrue(dayTwo.gripSeam < fresh.gripSeam, "the seamers kept all their help into day two")
    }

    @Test
    fun `the spinners come into it as the match goes on`() {
        val fresh = Pitch.AVERAGE
        val dayFour = wear(fresh, sessions = 9)
        assertTrue(dayFour.turn > fresh.turn, "turn did not increase: ${fresh.turn} to ${dayFour.turn}")
        assertTrue(dayFour.abrasion > fresh.abrasion + 0.15, "the surface was not scuffed up")
        assertTrue(dayFour.cracks > fresh.cracks, "no cracks opened")
    }

    @Test
    fun `bounce becomes unreliable late in a match`() {
        val fresh = Pitch.AVERAGE
        val dayFive = wear(fresh, sessions = 12)
        assertTrue(
            dayFive.evenness < fresh.evenness - 0.10,
            "the pitch stayed true all match: ${fresh.evenness} to ${dayFive.evenness}",
        )
    }

    @Test
    fun `sandy soil breaks up far faster than clay`() {
        val sandy = wear(Pitch.AVERAGE.copy(soilType = SoilType.SANDY), sessions = 9)
        val clay = wear(Pitch.AVERAGE.copy(soilType = SoilType.CLAY), sessions = 9)
        assertTrue(
            sandy.deterioration > clay.deterioration,
            "sandy soil (${sandy.deterioration}) did not break up faster than clay (${clay.deterioration})",
        )
    }

    @Test
    fun `every property stays physical however long the match runs`() {
        val worn = wear(Pitch.AVERAGE, sessions = 30)
        listOf(
            worn.hardness, worn.grassCover, worn.moisture, worn.cracks, worn.abrasion,
            worn.pace, worn.bounce, worn.evenness, worn.turn, worn.gripSeam, worn.deterioration,
        ).forEach { assertTrue(it in 0.0..1.0, "a pitch property left its range: $it") }
    }

    @Test
    fun `uncovered rain puts the moisture back`() {
        val dry = wear(Pitch.AVERAGE, sessions = 6)
        val rained = PitchEvolution.afterSession(
            dry, Weather.AVERAGE, Session.MORNING, 0.0, uncoveredRain = 0.8, tuning, SimRandom.fromSeed(2L),
        )
        assertTrue(rained.moisture > dry.moisture, "uncovered rain did not wet the square")
    }

    @Test
    fun `dew arrives late in a day-night innings and never in a day game`() {
        assertTrue(PitchEvolution.dewAt(0.1, 0.8, dayNight = true) < 0.05, "dew arrived in the first hour")
        assertTrue(PitchEvolution.dewAt(0.9, 0.8, dayNight = true) > 0.4, "no dew by the end of a day-nighter")
        assertTrue(PitchEvolution.dewAt(0.9, 0.8, dayNight = false) == 0.0, "dew in a day game")
    }
}
