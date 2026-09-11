package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.event.RecordingSink
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.MatchRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The contract the whole project rests on: a match is a pure function of
 * (seed, inputs).
 *
 * Without this a bug report is a story rather than a reproduction, and every
 * calibration figure is unrepeatable.
 */
class MatchDeterminismTest {

    private fun simulate(seed: Long, tuning: EngineTuning = EngineTuning.DEFAULT): List<BallEvent> {
        val sink = RecordingSink()
        val (batting, bowling) = Fixtures.averageTeams()
        InningsSimulator(
            format = MatchFormat.T20,
            battingSide = batting,
            bowlingSide = bowling,
            venue = Fixtures.AVERAGE_VENUE,
            pitch = Fixtures.AVERAGE_PITCH,
            weather = Weather.AVERAGE,
            level = LadderLevel.STATE_FIRST_CLASS,
            random = MatchRandom(seed),
            tuning = tuning,
            sink = sink,
        ).simulate()
        return sink.events()
    }

    @Test
    fun `the same seed replays ball for ball`() {
        (1L..20L).forEach { seed ->
            val first = simulate(seed)
            val second = simulate(seed)
            assertEquals(first.size, second.size, "seed $seed produced a different number of deliveries")
            first.indices.forEach { i ->
                assertEquals(first[i].outcome, second[i].outcome, "seed $seed diverged at ball ${first[i].id}")
                assertEquals(first[i].delivered, second[i].delivered, "seed $seed: ball ${first[i].id} was bowled differently")
                assertEquals(first[i].contact, second[i].contact, "seed $seed: ball ${first[i].id} met the bat differently")
            }
        }
    }

    @Test
    fun `different seeds produce different matches`() {
        val a = simulate(1L)
        val b = simulate(2L)
        assertNotEquals(a.map { it.outcome }, b.map { it.outcome })
    }

    @Test
    fun `the event sink does not change what happens`() {
        // Tier B discards events for speed (docs/ARCHITECTURE.md §7). If the
        // recording sink changed the simulation, every calibration figure taken
        // with events on would be a different game from the one shipped.
        val recorded = simulate(99L)
        val (batting, bowling) = Fixtures.averageTeams()
        val discarded = InningsSimulator(
            format = MatchFormat.T20,
            battingSide = batting,
            bowlingSide = bowling,
            venue = Fixtures.AVERAGE_VENUE,
            pitch = Fixtures.AVERAGE_PITCH,
            weather = Weather.AVERAGE,
            level = LadderLevel.STATE_FIRST_CLASS,
            random = MatchRandom(99L),
            sink = BallEventSink.Discard,
        ).simulate()

        assertEquals(recorded.sumOf { it.outcome.totalRuns }, discarded.runs)
        assertEquals(recorded.count { it.outcome.isLegalBall }, discarded.legalBalls)
        assertEquals(recorded.count { it.outcome.dismissal != null }, discarded.wickets)
    }

    @Test
    fun `every ball carries its full causal chain`() {
        // The event model is what commentary, the wagon wheel, the pitch map and
        // the beehive are all derived from. A ball missing its chain is a chart
        // that has to invent data.
        val events = simulate(7L)
        assertTrue(events.isNotEmpty())
        events.forEach { event ->
            assertTrue(event.delivered.paceKph > 0.0, "ball ${event.id} had no pace")
            assertTrue(event.intent.targetLengthMetres.isFinite(), "ball ${event.id} had no intended length")
            assertTrue(event.perceived.lengthMetres.isFinite(), "ball ${event.id} was never perceived")
            assertTrue(event.contact.quality in 0.0..1.0, "ball ${event.id} had contact quality ${event.contact.quality}")
            assertTrue(event.pressure in 0.0..1.0, "ball ${event.id} had pressure ${event.pressure}")
            if (event.contact.point.hitTheBat) {
                assertTrue(event.trajectory != null, "ball ${event.id} was hit but has no trajectory")
            }
        }
    }

    @Test
    fun `a tuning change moves the game rather than being ignored`() {
        // Guards against a knob that has been disconnected from the model it is
        // supposed to control - which would make calibration meaningless.
        val normal = simulate(5L)
        val powerful = simulate(
            5L,
            EngineTuning.DEFAULT.copy(
                knobs = EngineTuning.DEFAULT.knobs.copy(batPowerScale = 1.8),
            ),
        )
        val normalBoundaries = normal.count { it.outcome.runsOffBat >= 4 }
        val powerfulBoundaries = powerful.count { it.outcome.runsOffBat >= 4 }
        assertTrue(
            powerfulBoundaries > normalBoundaries,
            "raising batPowerScale did not raise the boundary count ($normalBoundaries then $powerfulBoundaries)",
        )
    }
}
