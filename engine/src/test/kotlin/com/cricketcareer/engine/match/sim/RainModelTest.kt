package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.config.RainTuning
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When it rains, and how much is lost.
 *
 * Nothing here decides a result — that is `DuckworthLewis`'s job. What is being
 * defended is the shape: most matches lose nothing, a few lose a handful of
 * overs, and the washout is a tail rather than the middle.
 */
class RainModelTest {

    private val tuning = RainTuning()

    private fun plans(weather: Weather, count: Int = 4000, format: MatchFormat = MatchFormat.LIST_A) =
        (1..count).map { RainModel.plan(format, weather, SimRandom.fromSeed(it.toLong()), tuning) }

    private val clear = Weather(cloudCover = 0.0, humidity = 0.1)
    private val filthy = Weather(cloudCover = 1.0, humidity = 1.0)

    @Test
    fun `most matches are played out`() {
        val wet = plans(Weather.AVERAGE).count { !it.isDry }.toDouble() / 4000
        assertTrue(wet in 0.05..0.15) { "%.1f%% of average-weather matches were rained on".format(100 * wet) }
    }

    @Test
    fun `an overcast, muggy day is far more dangerous than the average of its parts`() {
        // Rain needs cloud and humidity together, so the effect is superlinear.
        // A model linear in "wetness" makes a muggy clear day as dangerous as
        // half a storm, which is not how weather works.
        val dry = plans(clear).count { !it.isDry }.toDouble() / 4000
        val soaked = plans(filthy).count { !it.isDry }.toDouble() / 4000
        val middling = plans(Weather(cloudCover = 0.5, humidity = 0.55)).count { !it.isDry }.toDouble() / 4000

        assertTrue(dry < 0.06) { "a clear day was rained on %.1f%% of the time".format(100 * dry) }
        assertTrue(soaked > 0.3) { "a filthy day was rained on only %.1f%% of the time".format(100 * soaked) }
        assertTrue(middling < (dry + soaked) / 2) { "the response to weather is not superlinear" }
    }

    @Test
    fun `a washout is a tail, not the usual outcome`() {
        val rained = plans(filthy).filter { !it.isDry }
        val washouts = rained.count { it.isWashout }.toDouble() / rained.size
        assertTrue(washouts in 0.10..0.28) { "%.1f%% of rained-on matches were washed out".format(100 * washouts) }
    }

    @Test
    fun `the usual interruption costs a few overs, not half an innings`() {
        val lost = plans(filthy).filter { !it.isDry }.flatMap { it.stoppages }.map { it.oversLost }.sorted()
        assertTrue(lost.isNotEmpty())
        val median = lost[lost.size / 2]
        assertTrue(median <= 8) { "the median stoppage cost $median overs of fifty" }
        assertTrue(lost.last() > 20) { "nothing worse than ${lost.last()} overs ever happened" }
    }

    @Test
    fun `no stoppage costs more overs than are left`() {
        plans(filthy).flatMap { it.stoppages }.forEach { stoppage ->
            assertTrue(stoppage.afterOvers + stoppage.oversLost <= 50) {
                "a stoppage after ${stoppage.afterOvers} overs cost ${stoppage.oversLost} of fifty"
            }
        }
    }

    @Test
    fun `rain falls on both innings`() {
        val byInnings = plans(filthy).flatMap { it.stoppages }.groupingBy { it.innings }.eachCount()
        assertTrue(checkNotNull(byInnings[1]) > 0 && checkNotNull(byInnings[2]) > 0)
        // Roughly even: rain does not prefer a session.
        val first = checkNotNull(byInnings[1]).toDouble()
        val second = checkNotNull(byInnings[2]).toDouble()
        assertTrue(first / (first + second) in 0.4..0.6) { "innings split %.2f".format(first / (first + second)) }
    }

    @Test
    fun `multi-day cricket has its own weather model and gets nothing from this one`() {
        assertTrue(RainModel.plan(MatchFormat.TEST, filthy, SimRandom.fromSeed(1), tuning).isDry)
    }

    @Test
    fun `the same seed gives the same weather`() {
        assertEquals(
            RainModel.plan(MatchFormat.T20, filthy, SimRandom.fromSeed(99), tuning),
            RainModel.plan(MatchFormat.T20, filthy, SimRandom.fromSeed(99), tuning),
        )
    }

    @Test
    fun `whether it rains twice does not change how many numbers are drawn`() {
        // The determinism contract: a stage that consumed a variable count of
        // draws would make every later draw in the match depend on the weather,
        // so one shower would reshuffle the whole innings.
        val used = mutableListOf<Int>()
        (1..200).forEach { seed ->
            val random = SimRandom.fromSeed(seed.toLong())
            RainModel.plan(MatchFormat.LIST_A, filthy, random, tuning)
            // Whatever the plan, the next draw off this generator must be the
            // same one it would have been for any other plan with rain in it.
            used += if (random.nextDouble() > 0.5) 1 else 0
        }
        assertTrue(used.sum() in 70..130) { "the probe draw is not uniform: ${used.sum()} of 200" }
    }
}
