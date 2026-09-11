package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.state.MatchResult
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Whole multi-day matches: the sequence of innings, the clock, and the result. */
class MultiDayMatchTest {

    private fun play(seed: Long, format: MatchFormat = MatchFormat.TEST) = MatchSimulator(
        format = format,
        homeSide = Fixtures.averageXI("HOME"),
        awaySide = Fixtures.averageXI("AWAY"),
        venue = Fixtures.AVERAGE_VENUE,
        startingPitch = Fixtures.AVERAGE_PITCH,
        weather = Weather.AVERAGE,
        level = LadderLevel.INTERNATIONAL,
        seed = seed,
    ).simulate()

    @Test
    fun `a match always reaches a result`() {
        (1L..30L).forEach { seed ->
            val state = play(seed)
            assertTrue(state.result != null, "seed $seed produced no result at all")
        }
    }

    @Test
    fun `results are of a kind a five-day match can produce`() {
        val results = (1L..40L).map { play(it).result!! }
        results.forEach { result ->
            assertTrue(
                result is MatchResult.WonByRuns || result is MatchResult.WonByWickets ||
                    result is MatchResult.WonByInnings || result is MatchResult.Drawn ||
                    result is MatchResult.Tied,
                "a Test produced $result",
            )
        }
        assertTrue(
            results.any { it is MatchResult.Drawn },
            "no Test in forty was drawn, which means the match clock is not binding",
        )
        assertTrue(
            results.any { it !is MatchResult.Drawn },
            "every Test was drawn, which means nobody can bowl a side out",
        )
    }

    @Test
    fun `neither side bats more times than the format allows`() {
        (1L..25L).forEach { seed ->
            val state = play(seed)
            assertTrue(
                state.inningsPlayedBy("HOME") <= 2 && state.inningsPlayedBy("AWAY") <= 2,
                "seed $seed: a side batted more than twice in a Test",
            )
        }
    }

    @Test
    fun `every innings in every match balances its books`() {
        (1L..25L).forEach { seed ->
            play(seed).completedInnings.forEach { innings ->
                assertTrue(innings.snapshot().reconciles(), "seed $seed: an innings did not reconcile")
            }
        }
    }

    @Test
    fun `the pitch the fourth innings gets is the one the first three left`() {
        // The whole point of multi-day cricket. If the pitch reset between
        // innings, batting last would be no different from batting first.
        val simulator = MatchSimulator(
            format = MatchFormat.TEST,
            homeSide = Fixtures.averageXI("HOME"),
            awaySide = Fixtures.averageXI("AWAY"),
            venue = Fixtures.AVERAGE_VENUE,
            startingPitch = Fixtures.AVERAGE_PITCH,
            weather = Weather.AVERAGE,
            level = LadderLevel.INTERNATIONAL,
            seed = 11L,
        )
        val state = simulator.simulate()
        assertTrue(
            state.pitch.abrasion > Fixtures.AVERAGE_PITCH.abrasion,
            "the pitch finished the match as fresh as it started it",
        )
        assertTrue(state.pitch.turn >= Fixtures.AVERAGE_PITCH.turn, "the surface never started to turn")
    }

    @Test
    fun `the follow-on is sometimes enforced and sometimes declined`() {
        // Both are real captaincy. Always enforcing, or never, would be a bug.
        val followOns = (1L..60L).count { play(it).followOnEnforced }
        assertTrue(followOns > 0, "the follow-on was never enforced in sixty Tests")
        assertTrue(followOns < 60, "the follow-on was enforced in every Test it was available")
    }

    @Test
    fun `declarations happen in multi-day cricket and never in limited overs`() {
        val declared = (1L..40L).sumOf { seed ->
            play(seed).completedInnings.count { it.declared }
        }
        assertTrue(declared > 0, "nobody declared in forty Tests")

        val whiteBall = (1L..20L).sumOf { seed ->
            play(seed, MatchFormat.T20).completedInnings.count { it.declared }
        }
        assertEquals(0, whiteBall, "a side declared in a Twenty20")
    }

    @Test
    fun `a four-day match is shorter than a five-day one`() {
        val fourDay = (1L..20L).map { play(it, MatchFormat.FOUR_DAY) }
        val fiveDay = (1L..20L).map { play(it, MatchFormat.TEST) }
        val fourDayOvers = fourDay.sumOf { m -> m.completedInnings.sumOf { it.completedOvers } }
        val fiveDayOvers = fiveDay.sumOf { m -> m.completedInnings.sumOf { it.completedOvers } }
        assertTrue(
            fourDayOvers < fiveDayOvers,
            "four-day matches ($fourDayOvers overs) were not shorter than five-day ones ($fiveDayOvers)",
        )
    }
}
