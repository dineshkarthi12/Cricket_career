package com.cricketcareer.engine.rng

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class MatchRandomTest {

    @Test
    fun `the same match seed rebuilds the same streams`() {
        val a = MatchRandom(4242L)
        val b = MatchRandom(4242L)
        repeat(500) {
            assertEquals(
                a.stream(RngStreams.EXECUTION).nextLong(),
                b.stream(RngStreams.EXECUTION).nextLong(),
            )
        }
    }

    @Test
    fun `different streams of one match are independent`() {
        val match = MatchRandom(4242L)
        val execution = List(256) { match.stream(RngStreams.EXECUTION).nextLong() }
        val fielding = List(256) { match.stream(RngStreams.FIELDING).nextLong() }
        assertNotEquals(execution, fielding)
        assertEquals(0, execution.intersect(fielding.toSet()).size)
    }

    @Test
    fun `a stream keeps advancing rather than rewinding on each lookup`() {
        val match = MatchRandom(1L)
        val first = match.stream(RngStreams.CONTACT).nextLong()
        val second = match.stream(RngStreams.CONTACT).nextLong()
        assertNotEquals(first, second)
    }

    @Test
    fun `draws in one stream do not disturb another`() {
        // This is the property the whole design exists for: changing how many
        // random numbers the execution model burns must leave the fielding
        // model's numbers - and therefore every fielding regression baseline -
        // bit identical.
        val baseline = MatchRandom(777L)
        val expectedFielding = List(100) { baseline.stream(RngStreams.FIELDING).nextLong() }

        val afterChange = MatchRandom(777L)
        repeat(9_999) { afterChange.stream(RngStreams.EXECUTION).nextGaussian() }
        val actualFielding = List(100) { afterChange.stream(RngStreams.FIELDING).nextLong() }

        assertEquals(expectedFielding, actualFielding)
    }

    @Test
    fun `stream order of first use does not affect any stream`() {
        val forwards = MatchRandom(555L)
        forwards.stream(RngStreams.BOWLER_INTENT).nextLong()
        val forwardsUmpiring = forwards.stream(RngStreams.UMPIRING).nextLong()

        val backwards = MatchRandom(555L)
        val backwardsUmpiring = backwards.stream(RngStreams.UMPIRING).nextLong()
        backwards.stream(RngStreams.BOWLER_INTENT).nextLong()

        assertEquals(forwardsUmpiring, backwardsUmpiring)
    }

    @Test
    fun `derived seeds are stable values`() {
        // Pinned: save files and calibration baselines reference these. If this
        // test fails after a refactor, every stored seed has silently changed
        // meaning and old careers will not replay.
        assertEquals(-6491524045729747972L, SimRandom.deriveSeed(0L, RngStreams.EXECUTION))
        assertEquals(-1522691380653631457L, SimRandom.deriveSeed(1L, RngStreams.FIELDING))
    }

    @Test
    fun `activeStreams reports first use order`() {
        val match = MatchRandom(9L)
        match.stream(RngStreams.CONTACT)
        match.stream(RngStreams.BOWLER_INTENT)
        match.stream(RngStreams.CONTACT)
        assertEquals(listOf(RngStreams.CONTACT, RngStreams.BOWLER_INTENT), match.activeStreams())
    }
}
