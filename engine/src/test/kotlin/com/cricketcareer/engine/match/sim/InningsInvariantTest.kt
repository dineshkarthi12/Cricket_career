package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.state.DismissalMode
import com.cricketcareer.engine.match.state.InningsState
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.MatchRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Things that must hold for every simulated innings, ever.
 *
 * A distribution test can be perfectly happy while the engine quietly lets a
 * dismissed batter keep batting or loses a run somewhere. These are the checks
 * that catch that, run over a few hundred seeds.
 */
class InningsInvariantTest {

    private fun simulate(seed: Long, format: MatchFormat = MatchFormat.T20): Pair<InningsState, List<BallEvent>> {
        val events = mutableListOf<BallEvent>()
        val (batting, bowling) = Fixtures.averageTeams()
        val state = InningsSimulator(
            format = format,
            battingSide = batting,
            bowlingSide = bowling,
            venue = Fixtures.AVERAGE_VENUE,
            pitch = Fixtures.AVERAGE_PITCH,
            weather = Weather.AVERAGE,
            level = LadderLevel.STATE_FIRST_CLASS,
            random = MatchRandom(seed),
            sink = BallEventSink { events += it },
        ).simulate()
        return state to events
    }

    @Test
    fun `the books balance in every innings`() {
        (1L..SEEDS).forEach { seed ->
            val (state, _) = simulate(seed)
            val card = state.snapshot()
            assertTrue(card.reconciles(), "seed $seed did not reconcile: $card")
            assertEquals(
                state.runs,
                card.batting.sumOf { it.runs } + state.extras,
                "seed $seed: runs off the bat plus extras did not make the total",
            )
        }
    }

    @Test
    fun `an innings never exceeds its wickets or its overs`() {
        (1L..SEEDS).forEach { seed ->
            val (state, _) = simulate(seed)
            assertTrue(state.wickets <= MatchFormat.WICKETS_PER_INNINGS, "seed $seed lost ${state.wickets} wickets")
            assertTrue(state.legalBalls <= 120, "seed $seed bowled ${state.legalBalls} legal balls in a T20")
        }
    }

    @Test
    fun `nobody faces a ball after being dismissed`() {
        (1L..SEEDS).forEach { seed ->
            val (_, events) = simulate(seed)
            val dismissed = mutableSetOf<String>()
            events.forEach { event ->
                assertTrue(
                    event.striker.value !in dismissed,
                    "seed $seed: ${event.striker} faced a ball after being dismissed",
                )
                assertTrue(
                    event.nonStriker.value !in dismissed,
                    "seed $seed: ${event.nonStriker} was at the crease after being dismissed",
                )
                event.outcome.dismissal?.let { dismissed += it.batterOut.value }
            }
        }
    }

    @Test
    fun `the striker and non-striker are never the same player`() {
        (1L..SEEDS).forEach { seed ->
            val (_, events) = simulate(seed)
            events.forEach { event ->
                assertTrue(event.striker != event.nonStriker, "seed $seed: a batter was at both ends")
                assertTrue(event.bowler != event.striker, "seed $seed: the bowler was also batting")
            }
        }
    }

    @Test
    fun `over counting reconciles with the event stream`() {
        (1L..SEEDS).forEach { seed ->
            val (state, events) = simulate(seed)
            assertEquals(
                state.legalBalls,
                events.count { it.outcome.isLegalBall },
                "seed $seed: the scorer and the event stream disagree about legal balls",
            )
            assertEquals(
                state.runs,
                events.sumOf { it.outcome.totalRuns },
                "seed $seed: the scorer and the event stream disagree about runs",
            )
        }
    }

    @Test
    fun `no bowler exceeds the format's over limit`() {
        (1L..SEEDS).forEach { seed ->
            val (state, _) = simulate(seed)
            state.snapshot().bowling.forEach { figures ->
                assertTrue(
                    figures.legalBalls <= MatchFormat.T20.maxOversPerBowler!! * MatchFormat.BALLS_PER_OVER,
                    "seed $seed: ${figures.player} bowled ${figures.overs} overs in a T20",
                )
            }
        }
    }

    @Test
    fun `nobody bowls two overs in a row`() {
        (1L..SEEDS).forEach { seed ->
            val (_, events) = simulate(seed)
            var previousOver = -1
            var previousBowler: String? = null
            events.forEach { event ->
                if (event.id.over != previousOver) {
                    if (previousBowler != null) {
                        assertTrue(
                            event.bowler.value != previousBowler,
                            "seed $seed: ${event.bowler} bowled consecutive overs",
                        )
                    }
                    previousBowler = event.bowler.value
                    previousOver = event.id.over
                }
            }
        }
    }

    @Test
    fun `a wicket credited to a bowler is one he can be credited with`() {
        (1L..SEEDS).forEach { seed ->
            val (_, events) = simulate(seed)
            events.mapNotNull { it.outcome.dismissal }.forEach { dismissal ->
                if (dismissal.mode.creditedToBowler) {
                    assertTrue(dismissal.bowler != null, "seed $seed: ${dismissal.mode} with no bowler")
                } else {
                    assertTrue(
                        dismissal.mode == DismissalMode.RUN_OUT || dismissal.bowler == null,
                        "seed $seed: ${dismissal.mode} was credited to ${dismissal.bowler}",
                    )
                }
            }
        }
    }

    @Test
    fun `a no ball is never a caught, bowled or lbw dismissal`() {
        // Only a run out can take a wicket off a no-ball.
        (1L..SEEDS).forEach { seed ->
            val (_, events) = simulate(seed)
            events.filter { it.outcome.noBall }.forEach { event ->
                val mode = event.outcome.dismissal?.mode ?: return@forEach
                assertTrue(
                    mode == DismissalMode.RUN_OUT,
                    "seed $seed: $mode was given off a no-ball",
                )
            }
        }
    }

    @Test
    fun `a wide is never a ball faced and never a legal ball`() {
        (1L..SEEDS).forEach { seed ->
            val (_, events) = simulate(seed)
            events.filter { it.outcome.wides > 0 }.forEach { event ->
                assertTrue(!event.outcome.isLegalBall, "seed $seed: a wide advanced the over")
                assertTrue(!event.outcome.isBallFaced, "seed $seed: a wide was a ball faced")
                assertEquals(0, event.outcome.runsOffBat, "seed $seed: runs off the bat from a wide")
            }
        }
    }

    private companion object {
        /** Enough seeds to be confident, few enough to keep `check` quick. */
        const val SEEDS = 250L
    }
}
