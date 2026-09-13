package com.cricketcareer.engine.match.delivery

import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.field.FieldPosition
import com.cricketcareer.engine.match.sim.InningsSimulator
import com.cricketcareer.engine.match.state.DismissalMode
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.MatchRandom
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The outside edge of the bat.
 *
 * A bat has an edge; a tolerance envelope does not. Modelled as a cliff, every
 * ball a fraction outside the envelope passed through thin air — and the
 * surplus was the largest known defect in the engine, showing up as
 * play-and-miss at 19% of deliveries against a real 10-12%, and carrying dot %
 * and balls per wicket out of band with it.
 *
 * What is defended here is not the rate but the *behaviour*: a feather is a
 * deflection, and it has to behave like one.
 *
 * See docs/SIMULATION_MODEL.md §16.
 */
class FeatheredEdgeTest {

    private val events: List<BallEvent> by lazy { sample() }

    private fun sample(format: MatchFormat = Fixtures.T20, innings: Int = 120): List<BallEvent> {
        val (batting, bowling) = Fixtures.averageTeams()
        val collected = mutableListOf<BallEvent>()
        repeat(innings) { i ->
            InningsSimulator(
                format = format,
                battingSide = batting,
                bowlingSide = bowling,
                venue = Fixtures.AVERAGE_VENUE,
                pitch = Fixtures.AVERAGE_PITCH,
                weather = Weather.AVERAGE,
                level = LadderLevel.STATE_FIRST_CLASS,
                random = MatchRandom(8_000L + i),
                tuning = EngineTuning.DEFAULT,
                sink = BallEventSink { collected += it },
            ).simulate()
        }
        return collected
    }

    private val feathers: List<BallEvent> by lazy { events.filter { it.contact.feathered } }

    @Test
    fun `the edge is not a cliff, and not a barn door either`() {
        val share = feathers.size.toDouble() / events.size
        assertTrue(share in 0.01..0.06) {
            "%.1f%% of deliveries caught the very edge".format(100 * share)
        }
    }

    @Test
    fun `a feather is only ever an edge`() {
        feathers.forEach { event ->
            assertTrue(event.contact.point.isEdge) {
                "a feathered contact came out as ${event.contact.point}"
            }
        }
    }

    @Test
    fun `a feather gets off the ground`() {
        // A nick that never leaves the turf is not a wicket, it is a dot. Given
        // a defensive stroke's own elevation, two in five never got up at all.
        val airborne = feathers.count { it.trajectory?.isAerial == true }
        assertTrue(airborne.toDouble() / feathers.size > 0.9) { "$airborne of ${feathers.size} got airborne" }
    }

    @Test
    fun `a feather goes to the keeper, not to gully`() {
        // Thickness is *not* monotonic in how far the bat missed by. Beyond the
        // envelope the ball is catching the very outer edge — the thinnest
        // contact there is — so it is a near-straight deflection. Read the
        // other way, every feather flew square and the cordon never saw one.
        val azimuths = feathers
            .mapNotNull { it.trajectory?.azimuthDegrees }
            .map { abs(it - 180.0) }
            .sorted()

        assertTrue(azimuths.isNotEmpty())
        val median = azimuths[azimuths.size / 2]
        assertTrue(median < 20.0) { "the median feather went %.0f degrees off straight".format(median) }
    }

    @Test
    fun `a feather carries to where the cordon stands`() {
        // Set by where the keeper and the slips actually are, because a nick
        // that pitches in front of the keeper is not a wicket.
        val cordon = FieldPosition.WICKETKEEPER.distanceMetres
        val carries = feathers
            .filter { it.trajectory?.isAerial == true }
            .mapNotNull { it.trajectory?.carryMetres }
            .sorted()

        assertTrue(carries.isNotEmpty())
        val median = carries[carries.size / 2]
        assertTrue(median > cordon) { "the median feather carried %.1f m; the keeper is at %.1f".format(median, cordon) }
        assertTrue(median < cordon * 2.0) { "the median feather carried %.1f m, well past the cordon".format(median) }
    }

    @Test
    fun `a feather keeps some of the pace it arrived with`() {
        // It is a deflection, not a stroke: the bat puts almost nothing into it
        // and takes almost nothing out. Scored by contact quality like every
        // other contact, it was the slowest ball on the field and died at the
        // batter's feet.
        val feathered = feathers.mapNotNull { it.trajectory?.exitSpeedMetresPerSecond }
        val middled = events
            .filter { it.contact.point == ContactPoint.MIDDLE }
            .mapNotNull { it.trajectory?.exitSpeedMetresPerSecond }

        assertTrue(feathered.isNotEmpty() && middled.isNotEmpty())
        assertTrue(feathered.average() < middled.average())
        assertTrue(feathered.average() > middled.average() * 0.4) {
            "feather %.1f m/s against a middled %.1f".format(feathered.average(), middled.average())
        }
    }

    @Test
    fun `feathers find the cordon often enough to matter`() {
        val caught = feathers.count { it.outcome.dismissal?.mode == DismissalMode.CAUGHT }

        assertTrue(caught > 0) { "not one of ${feathers.size} feathers was caught" }
        val rate = caught.toDouble() / feathers.size
        assertTrue(rate in 0.10..0.60) { "%.0f%% of feathers were caught".format(100 * rate) }
    }

    @Test
    fun `a feather is not a scoring shot`() {
        // It goes behind the wicket off the face of the bat. A four off one is
        // the ball that beat the cordon, and it should be rare.
        val boundaries = feathers.count { it.outcome.runsOffBat >= 4 }
        assertTrue(boundaries.toDouble() / feathers.size < 0.08) {
            "$boundaries of ${feathers.size} feathers went to the fence"
        }
    }
}
