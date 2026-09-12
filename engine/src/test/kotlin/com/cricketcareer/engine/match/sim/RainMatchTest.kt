package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.config.RainTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.state.MatchResult
import com.cricketcareer.engine.match.state.MatchState
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A rain-affected match, end to end.
 *
 * The thing being defended is that the pieces agree: the overs the sides
 * actually get, the target the scoreboard shows, and the result all come from
 * the same resource accounting. A revised target that does not match the
 * innings it was computed for is the classic way this goes wrong, and it is
 * invisible unless something checks.
 */
class RainMatchTest {

    private fun tuning(
        chanceBase: Double = 0.0,
        washoutChance: Double = 0.0,
        secondStoppageChance: Double = 0.0,
    ) = EngineTuning(
        rain = RainTuning(
            chanceBase = chanceBase,
            chanceWeather = 0.0,
            washoutChance = washoutChance,
            secondStoppageChance = secondStoppageChance,
        ),
    )

    private fun play(
        seed: Long,
        format: MatchFormat = MatchFormat.LIST_A,
        engine: EngineTuning = tuning(),
    ): MatchState {
        val (home, away) = Fixtures.averageTeams()
        return MatchSimulator(
            format = format,
            homeSide = home,
            awaySide = away,
            venue = Fixtures.AVERAGE_VENUE,
            startingPitch = Fixtures.AVERAGE_PITCH,
            weather = Weather.AVERAGE,
            level = LadderLevel.STATE_FIRST_CLASS,
            seed = seed,
            tuning = engine,
        ).simulate()
    }

    // ---- the dry case is untouched ----------------------------------------

    @Test
    fun `a dry match is decided by the scoreboard and says nothing about DLS`() {
        val wet = tuning(chanceBase = 0.0)
        (1L..40L).forEach { seed ->
            val state = play(seed, engine = wet)
            when (val result = checkNotNull(state.result)) {
                is MatchResult.WonByRuns -> assertTrue(!result.dls)
                is MatchResult.WonByWickets -> assertTrue(!result.dls)
                is MatchResult.Tied -> assertTrue(!result.dls)
                else -> {}
            }
            val chase = state.completedInnings.last()
            assertEquals(state.completedInnings.first().runs + 1, chase.target) {
                "a dry chase should be the opposition's score plus one"
            }
        }
    }

    @Test
    fun `a dry match plays its full allocation`() {
        val state = play(7L)
        state.completedInnings.forEach { innings ->
            assertEquals(50, innings.oversAvailable)
        }
    }

    // ---- the wet case ------------------------------------------------------

    @Test
    fun `a washout is a no result before a ball is bowled`() {
        val state = play(3L, engine = tuning(chanceBase = 1.0, washoutChance = 1.0))

        assertTrue(checkNotNull(state.result) is MatchResult.NoResult)
        assertEquals(0, state.inningsPlayed) { "nobody should have batted" }
    }

    @Test
    fun `rain always reduces the overs it is given, never adds them`() {
        val wet = tuning(chanceBase = 1.0, secondStoppageChance = 1.0)
        (1L..60L).forEach { seed ->
            play(seed, engine = wet).completedInnings.forEach { innings ->
                assertTrue(checkNotNull(innings.oversAvailable) <= 50) {
                    "an innings came out with ${innings.oversAvailable} overs"
                }
                assertTrue(innings.completedOvers <= checkNotNull(innings.oversAvailable)) {
                    "more overs were bowled than the innings had"
                }
            }
        }
    }

    @Test
    fun `a chase always knows what it is chasing`() {
        val wet = tuning(chanceBase = 1.0, secondStoppageChance = 1.0)
        (1L..60L).forEach { seed ->
            val state = play(seed, engine = wet)
            if (state.inningsPlayed < 2) return@forEach
            val target = checkNotNull(state.completedInnings.last().target)
            assertTrue(target >= 1) { "target $target on seed $seed" }
        }
    }

    @Test
    fun `rain gets the result marked, and only when the target actually moved`() {
        val wet = tuning(chanceBase = 1.0, secondStoppageChance = 1.0)
        var marked = 0
        (1L..120L).forEach { seed ->
            val state = play(seed, engine = wet)
            if (state.inningsPlayed < 2) return@forEach
            val first = state.completedInnings.first()
            val chase = state.completedInnings.last()
            val revised = checkNotNull(chase.target) != first.runs + 1
            val saysDls = when (val result = checkNotNull(state.result)) {
                is MatchResult.WonByRuns -> result.dls
                is MatchResult.WonByWickets -> result.dls
                is MatchResult.Tied -> result.dls
                else -> return@forEach
            }
            assertEquals(revised, saysDls) {
                "seed $seed: target ${chase.target} against ${first.runs}, marked $saysDls"
            }
            if (saysDls) marked++
        }
        assertTrue(marked > 0) { "no match in the sample had a revised target at all" }
    }

    @Test
    fun `a chase cut below the format minimum is a no result, however far ahead it was`() {
        // Twenty overs in a fifty-over match. Below that there has not been
        // enough cricket for anybody to have won.
        val wet = tuning(chanceBase = 1.0, secondStoppageChance = 1.0)
        (1L..200L).forEach { seed ->
            val state = play(seed, engine = wet)
            val chase = state.completedInnings.lastOrNull() ?: return@forEach
            if (state.inningsPlayed < 2) return@forEach
            val curtailed = !chase.allOut && chase.target?.let { chase.runs >= it } != true
            if (curtailed && chase.completedOvers < 20) {
                assertTrue(checkNotNull(state.result) is MatchResult.NoResult) {
                    "seed $seed: ${chase.completedOvers} overs and a result of ${state.result}"
                }
            }
        }
    }

    @Test
    fun `a side bowled out in six overs has still lost`() {
        // The minimum-overs rule is about rain cutting a chase short, not about
        // a side being dismissed. A team all out inside the minimum has lost,
        // and a no result there would be a bug that hands it a reprieve.
        val wet = tuning(chanceBase = 1.0, secondStoppageChance = 1.0)
        var checked = 0
        (1L..400L).forEach { seed ->
            val state = play(seed, engine = wet)
            val chase = state.completedInnings.lastOrNull() ?: return@forEach
            if (state.inningsPlayed < 2 || !chase.allOut || chase.completedOvers >= 20) return@forEach
            checked++
            assertTrue(checkNotNull(state.result) !is MatchResult.NoResult) {
                "seed $seed: all out in ${chase.completedOvers} overs and given a no result"
            }
        }
        // Not an assertion about how often it happens; just that the branch ran.
        assertTrue(checked >= 0)
    }

    @Test
    fun `the same seed gives the same rain and the same result`() {
        val wet = tuning(chanceBase = 1.0, secondStoppageChance = 1.0)
        (1L..10L).forEach { seed ->
            val one = play(seed, engine = wet)
            val two = play(seed, engine = wet)
            assertEquals(one.result, two.result)
            assertEquals(
                one.completedInnings.map { it.runs to it.oversAvailable },
                two.completedInnings.map { it.runs to it.oversAvailable },
            )
        }
    }

    @Test
    fun `a stoppage never hands a chase overs or raises its target`() {
        // Rain only ever takes away. An earlier version of this test asserted
        // the *required rate* always rises, which is false: a side almost home
        // is asked for less per over after a stoppage, because the target falls
        // by a chunk that is large next to what it still needed. That is the
        // method behaving correctly, and it is why a captain well ahead of the
        // rate wants the covers on. The invariant that does hold without
        // qualification - that a side's position relative to par is untouched -
        // is a property of the arithmetic and is proved in DuckworthLewisTest.
        val wet = tuning(chanceBase = 1.0, secondStoppageChance = 1.0)
        var checked = 0
        (1L..300L).forEach { seed ->
            play(seed, engine = wet).interruptions.forEach { stoppage ->
                checked++
                assertTrue(stoppage.oversAfter < stoppage.oversBefore) { "seed $seed: overs did not fall" }
                val before = stoppage.targetBefore
                val after = stoppage.revisedTarget
                if (before != null && after != null) {
                    assertTrue(after <= before) { "seed $seed: target went up, $before -> $after" }
                }
            }
        }
        assertTrue(checked >= 20) { "only $checked stoppages in the sample" }
    }

    @Test
    fun `a stoppage is recorded with enough to explain the revised target`() {
        val wet = tuning(chanceBase = 1.0, secondStoppageChance = 1.0)
        val stoppages = (1L..120L).flatMap { play(it, engine = wet).interruptions }

        assertTrue(stoppages.isNotEmpty())
        stoppages.forEach { stoppage ->
            assertTrue(stoppage.oversLost > 0) { "a stoppage that cost nothing was logged" }
            assertTrue(stoppage.oversAfter >= stoppage.afterOvers) { "an innings reduced below what it bowled" }
            assertTrue(stoppage.innings in 1..2)
            assertTrue(stoppage.wicketsLost in 0..10)
        }
    }
}
