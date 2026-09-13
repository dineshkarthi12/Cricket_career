package com.cricketcareer.engine.performance

import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.FieldGaps
import com.cricketcareer.engine.match.delivery.FieldRewards
import com.cricketcareer.engine.match.delivery.Shot
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.event.RecordingSink
import com.cricketcareer.engine.match.field.FieldCaptain
import com.cricketcareer.engine.match.field.MatchPhase
import com.cricketcareer.engine.match.sim.InningsSimulator
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.MatchRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The performance work, tested for the thing that can actually break.
 *
 * A wall-clock assertion on a build machine is a coin toss, so the budget in
 * `docs/ARCHITECTURE.md` §7 is measured by the harness
 * (`--report=bench`) and not asserted here. What *is* asserted is the part a
 * refactor can silently get wrong: **making the engine faster must not change
 * what happens in the cricket.** Every optimisation below is a claim that two
 * things are equal, and each of those claims is checked.
 */
class HotPathTest {

    private val format: MatchFormat = MatchFormat.T20

    private fun simulate(sink: BallEventSink, seed: Long = 4_242L) =
        Fixtures.averageTeams().let { (batting, bowling) ->
            InningsSimulator(
                format = format,
                battingSide = batting,
                bowlingSide = bowling,
                venue = Fixtures.AVERAGE_VENUE,
                pitch = Fixtures.AVERAGE_PITCH,
                weather = Weather.AVERAGE,
                level = LadderLevel.STATE_FIRST_CLASS,
                random = MatchRandom(seed),
                tuning = EngineTuning.DEFAULT,
                sink = sink,
            ).simulate()
        }

    @Test
    fun `a discarding sink plays exactly the same innings as a recording one`() {
        // The whole tiering rests on this. If the background tiers played even
        // slightly different cricket from the watched one, a rival's average
        // and the user's would be drawn from different games and the career
        // layer's comparisons would be meaningless.
        val recorded = RecordingSink()
        val watched = simulate(recorded)
        val background = simulate(BallEventSink.Discard)

        assertEquals(watched.runs, background.runs)
        assertEquals(watched.wickets, background.wickets)
        assertEquals(watched.legalBalls, background.legalBalls)
        assertTrue(recorded.size > 100) { "expected a full innings of events, got ${recorded.size}" }
    }

    @Test
    fun `a sink that does not want events is not handed any`() {
        // The strategy saved the branch and not the allocation: the event was
        // built before `accept` could throw it away. A sink says up front, and
        // this is what checks the simulator listens.
        assertFalse(BallEventSink.Discard.wantsEvents)

        var built = 0
        val counting = object : BallEventSink {
            override fun accept(event: BallEvent) {
                built++
            }

            override val wantsEvents: Boolean get() = false
        }
        simulate(counting)
        assertEquals(0, built)
    }

    @Test
    fun `a sink that says nothing still gets its events`() {
        // The default has to be "yes": a sink written to read events obviously
        // wants them and should not have to say so.
        val sink = BallEventSink { }
        assertTrue(sink.wantsEvents)
    }

    @Test
    fun `the precomputed field rewards are the rewards`() {
        // FieldRewards exists only to stop the same answer being computed
        // twenty times a ball. It is worth exactly nothing if it is a
        // different answer.
        val field = FieldCaptain.setField(
            bowlingSide = Fixtures.averageXI("b"),
            bowler = Fixtures.averageXI("b").first().id,
            keeper = Fixtures.averageXI("b")[5].id,
            phase = MatchPhase.MIDDLE,
            bowlerIsSpin = false,
            fieldersOutsideLimit = null,
        )
        val rewards = FieldRewards.of(field)
        Shot.entries.forEach { shot ->
            assertEquals(FieldGaps.reward(shot, field), rewards[shot], 0.0) { shot.name }
        }
    }

    @Test
    fun `ten thousand innings run in a loop without the heap growing`() {
        // CLAUDE.md 3: the engine must run ten thousand matches in a JVM loop
        // without leaking anything. Deliberately a *shape* test rather than a
        // timing one - it fails on a retained reference, which is a real bug,
        // and not on a busy build machine, which is not.
        val runtime = Runtime.getRuntime()
        fun settledHeap(): Long {
            repeat(3) { System.gc() }
            return runtime.totalMemory() - runtime.freeMemory()
        }

        repeat(200) { simulate(BallEventSink.Discard, seed = it.toLong()) }
        val before = settledHeap()
        repeat(2_000) { simulate(BallEventSink.Discard, seed = 10_000L + it) }
        val after = settledHeap()

        // Two thousand innings hold on to nothing, so anything beyond a few
        // megabytes of ordinary JVM noise is something the simulator kept.
        val grewMb = (after - before) / (1024.0 * 1024.0)
        assertTrue(grewMb < 32.0) { "heap grew %.1f MB over 2000 innings".format(grewMb) }
    }
}
