package com.cricketcareer.harness

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.sim.MatchSimulator
import com.cricketcareer.engine.match.state.MatchResult
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How much of a career the weather ruins.
 *
 * Bands rather than exact numbers: what matters is that a player meets DLS
 * every so often and not every other week, and that the occasional match is
 * abandoned. A career with no rain in it is missing something a real one
 * remembers; a career that is half rain is a different game.
 *
 * Measured on the reference weather, which is mild and mostly clear — a wetter
 * venue is meant to come out worse, and `RainModelTest` checks that separately.
 */
class RainCalibrationTest {

    private companion object {
        const val MATCHES = 1500
    }

    private class Tally {
        var dls = 0
        var noResult = 0
        var tied = 0
    }

    private fun sample(format: MatchFormat): Tally {
        val (home, away) = Fixtures.averageTeams()
        val tally = Tally()
        repeat(MATCHES) { i ->
            val state = MatchSimulator(
                format = format,
                homeSide = home,
                awaySide = away,
                venue = Fixtures.AVERAGE_VENUE,
                startingPitch = Fixtures.AVERAGE_PITCH,
                weather = Weather.AVERAGE,
                level = LadderLevel.STATE_FIRST_CLASS,
                seed = 5_000L + i,
            ).simulate()
            when (val result = state.result) {
                is MatchResult.WonByRuns -> if (result.dls) tally.dls++
                is MatchResult.WonByWickets -> if (result.dls) tally.dls++
                is MatchResult.Tied -> { tally.tied++; if (result.dls) tally.dls++ }
                is MatchResult.NoResult -> tally.noResult++
                else -> {}
            }
        }
        return tally
    }

    @Test
    fun `a one-day season meets DLS about as often as a real one`() {
        val tally = sample(MatchFormat.LIST_A)
        val dls = tally.dls.toDouble() / MATCHES
        val abandoned = tally.noResult.toDouble() / MATCHES

        assertTrue(dls in 0.02..0.12) { "%.1f%% of matches were decided under a revised target".format(100 * dls) }
        assertTrue(abandoned in 0.005..0.05) { "%.1f%% of matches were abandoned".format(100 * abandoned) }
    }

    @Test
    fun `a Twenty20 season does too`() {
        val tally = sample(MatchFormat.T20)
        val dls = tally.dls.toDouble() / MATCHES
        val abandoned = tally.noResult.toDouble() / MATCHES

        assertTrue(dls in 0.02..0.15) { "%.1f%% of matches were decided under a revised target".format(100 * dls) }
        assertTrue(abandoned in 0.0..0.05) { "%.1f%% of matches were abandoned".format(100 * abandoned) }
    }
}
