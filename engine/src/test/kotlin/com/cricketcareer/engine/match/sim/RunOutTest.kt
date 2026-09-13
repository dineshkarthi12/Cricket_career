package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.state.DismissalMode
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.MatchRandom
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Run outs, at both ends.
 *
 * The striker used to be out every single time, which is wrong about half the
 * time and — for a game about one cricketer — wrong in the way that matters
 * most: he could never be run out backing up, which is one of the cruellest
 * things that happens to a batter and was simply absent.
 */
class RunOutTest {

    private fun runOuts(format: MatchFormat = Fixtures.LIST_A, innings: Int = 400): List<Pair<BallEvent, Boolean>> {
        val (batting, bowling) = Fixtures.averageTeams()
        val found = mutableListOf<Pair<BallEvent, Boolean>>()
        repeat(innings) { i ->
            val events = mutableListOf<BallEvent>()
            InningsSimulator(
                format = format,
                battingSide = batting,
                bowlingSide = bowling,
                venue = Fixtures.AVERAGE_VENUE,
                pitch = Fixtures.AVERAGE_PITCH,
                weather = Weather.AVERAGE,
                level = LadderLevel.STATE_FIRST_CLASS,
                random = MatchRandom(4_000L + i),
                sink = BallEventSink { events += it },
            ).simulate()
            events.forEach { event ->
                val dismissal = event.outcome.dismissal ?: return@forEach
                if (dismissal.mode != DismissalMode.RUN_OUT) return@forEach
                found += event to (dismissal.batterOut == event.striker)
            }
        }
        return found
    }

    @Test
    fun `both batters get run out`() {
        val all = runOuts()
        assertTrue(all.size >= 30) { "only ${all.size} run outs in the sample" }

        val strikers = all.count { it.second }
        val nonStrikers = all.size - strikers
        assertTrue(nonStrikers > 0) { "the non-striker was never run out in ${all.size} of them" }
        assertTrue(strikers > 0) { "the striker was never run out in ${all.size} of them" }

        // Neither end dominates. Which end the throw goes to is decided by
        // where the ball was fielded, and a field is roughly symmetric about
        // square, so neither batter should be carrying the whole risk.
        val share = strikers.toDouble() / all.size
        assertTrue(share in 0.2..0.8) { "%.0f%% of run outs were the striker".format(100 * share) }
    }

    @Test
    fun `the batter given out was at the crease`() {
        // A run out naming somebody who was not batting is the sort of bug that
        // only shows up on a scorecard three screens later.
        runOuts().forEach { (event, _) ->
            val out = checkNotNull(event.outcome.dismissal).batterOut
            assertTrue(out == event.striker || out == event.nonStriker) {
                "$out was run out but was neither the striker nor the non-striker"
            }
        }
    }

    @Test
    fun `a run out is credited to a fielder and to no bowler`() {
        runOuts().forEach { (event, _) ->
            val dismissal = checkNotNull(event.outcome.dismissal)
            assertTrue(dismissal.bowler == null) { "a run out was credited to a bowler" }
        }
    }

    @Test
    fun `some run outs are direct hits and some are relayed`() {
        // Two different pieces of cricket, and a scorecard names a different
        // fielder for each.
        val direct = runOuts().count { (event, _) -> event.fielding?.directHit == true }
        val all = runOuts().size

        assertTrue(direct > 0) { "no run out in $all was a direct hit" }
        assertTrue(direct < all) { "every run out in $all was a direct hit" }
    }

    @Test
    fun `a held catch carries the difficulty that decided it`() {
        // So that "brilliant catch" and "regulation catch" are different
        // sentences rather than a guess from the outcome.
        val (batting, bowling) = Fixtures.averageTeams()
        val difficulties = mutableListOf<Double>()
        repeat(60) { i ->
            val events = mutableListOf<BallEvent>()
            InningsSimulator(
                format = Fixtures.T20,
                battingSide = batting,
                bowlingSide = bowling,
                venue = Fixtures.AVERAGE_VENUE,
                pitch = Fixtures.AVERAGE_PITCH,
                weather = Weather.AVERAGE,
                level = LadderLevel.STATE_FIRST_CLASS,
                random = MatchRandom(6_000L + i),
                sink = BallEventSink { events += it },
            ).simulate()
            events.forEach { event ->
                if (event.outcome.dismissal?.mode == DismissalMode.CAUGHT) {
                    difficulties += checkNotNull(event.fielding?.catchDifficulty) {
                        "a catch was taken with no difficulty recorded"
                    }
                }
            }
        }

        assertTrue(difficulties.size >= 30) { "only ${difficulties.size} catches" }
        // There is a spread: some are regulation and some are not.
        assertTrue(difficulties.max() - difficulties.min() > 0.3) {
            "every catch came out at the same difficulty, %.2f to %.2f"
                .format(difficulties.min(), difficulties.max())
        }
    }
}
