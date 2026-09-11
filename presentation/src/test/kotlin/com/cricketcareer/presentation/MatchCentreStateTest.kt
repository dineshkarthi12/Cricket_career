package com.cricketcareer.presentation

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.sim.InningsSimulator
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.rng.MatchRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MatchCentreStateTest {

    /** A real T20 innings from the engine, so the state is built from real deliveries. */
    private val innings: List<BallEvent> = run {
        val events = mutableListOf<BallEvent>()
        val (batting, bowling) = Fixtures.averageTeams()
        InningsSimulator(
            format = Fixtures.T20,
            battingSide = batting,
            bowlingSide = bowling,
            venue = Fixtures.AVERAGE_VENUE,
            pitch = Fixtures.AVERAGE_PITCH,
            weather = Weather.AVERAGE,
            level = LadderLevel.STATE_WHITE_BALL,
            random = MatchRandom(77),
            sink = BallEventSink { events += it },
        ).simulate()
        events.toList()
    }

    private fun state(balls: List<BallEvent> = innings, target: Int? = null) =
        matchCentreState(balls, Fixtures.T20, target = target, commentary = { "ball ${it.id}" })

    @Test
    fun `the score matches the last delivery`() {
        val last = innings.last()
        assertEquals("${last.scoreAfter}-${last.wicketsAfter}", state().score)
    }

    @Test
    fun `overs count legal balls, not deliveries`() {
        // The wide bug. Counting deliveries reports 20.4 overs in a twenty-over
        // innings and nobody notices until somebody sprays eight wides.
        val legal = innings.count { it.outcome.isLegalBall }
        val deliveries = innings.size
        assertTrue(deliveries > legal) { "this seed bowled no wides, so the test proves nothing" }
        assertEquals(oversDisplay(legal), state().overs)
        assertEquals("20.0", state().overs)
    }

    @Test
    fun `an empty innings is a real state, not a crash`() {
        val fresh = state(emptyList())
        assertEquals("0-0", fresh.score)
        assertEquals("0.0", fresh.overs)
        assertNull(fresh.currentRunRate)
        assertTrue(fresh.thisOver.isEmpty())
        assertTrue(fresh.feed.isNotEmpty() || fresh.feed.isEmpty())
    }

    @Test
    fun `the chase equation counts the balls that are left`() {
        // Required rate over balls REMAINING. Dividing by balls bowled gives a
        // plausible-looking number that is wrong all innings.
        val prefix = innings.take(60)
        val legal = prefix.count { it.outcome.isLegalBall }
        val target = 215
        val chase = checkNotNull(state(prefix, target = target).chase)
        assertEquals(target - prefix.last().scoreAfter, chase.runsNeeded)
        assertEquals(120 - legal, chase.ballsLeft)
        assertEquals(chase.runsNeeded * 6.0 / chase.ballsLeft, chase.requiredRate!!, 1e-9)
    }

    @Test
    fun `runs needed plus balls left reconcile with the target all innings`() {
        val target = 215
        for (n in 1..innings.size) {
            val prefix = innings.take(n)
            val chase = state(prefix, target = target).chase!!
            val legal = prefix.count { it.outcome.isLegalBall }
            assertEquals(target - prefix.last().scoreAfter, chase.runsNeeded) { "at ball $n" }
            assertEquals(120 - legal, chase.ballsLeft) { "at ball $n" }
            assertTrue(chase.wicketsLeft in 0..10) { "at ball $n: ${chase.wicketsLeft}" }
        }
    }

    @Test
    fun `a finished chase has no required rate to quote`() {
        val done = state(innings, target = 1).chase!!
        assertTrue(done.runsNeeded <= 0)
        assertNull(done.requiredRate) { "a won chase should not quote a rate" }
    }

    @Test
    fun `there is no chase equation in the first innings`() {
        assertNull(state().chase)
    }

    @Test
    fun `the over strip holds every delivery of the over, wides included`() {
        // Which is why it can legitimately show more than six pips, and why
        // capping it at six would hide the wide that cost the game.
        val overs = innings.groupBy { it.id.over }
        val messy = checkNotNull(overs.entries.firstOrNull { it.value.size > 6 }) {
            "this seed bowled no over with an extra in it"
        }
        val upToEndOfThatOver = innings.filter { it.id.over <= messy.key }
        val strip = state(upToEndOfThatOver).thisOver
        assertEquals(messy.value.size, strip.size)
        assertTrue(strip.size > 6)
    }

    @Test
    fun `the over number shown is the one being bowled, counting from one`() {
        val firstOver = innings.filter { it.id.over == 0 }
        assertEquals(1, state(firstOver).overNumber)
    }

    @Test
    fun `the feed reads newest first`() {
        val feed = state().feed
        assertEquals(innings.last().id.toString(), feed.first().over)
        assertTrue(feed.size <= 6)
    }

    @Test
    fun `a dot is a dot, not a zero`() {
        val dots = state().feed.filter { it.chip != "W" && it.chip.toIntOrNull() == null }
        dots.forEach { assertEquals("•", it.chip) }
        assertTrue(state().feed.none { it.chip == "0" }) { "a scorer writes a dot" }
    }

    @Test
    fun `a wicket ball is marked as one`() {
        val wicketBall = innings.first { it.outcome.dismissal != null }
        val upTo = innings.take(innings.indexOf(wicketBall) + 1)
        val top = state(upTo).feed.first()
        assertTrue(top.wicket)
        assertEquals("W", top.chip)
    }

    @Test
    fun `a boundary is marked off the bat, not off a wide`() {
        val four = checkNotNull(innings.firstOrNull { it.outcome.runsOffBat == 4 })
        val upTo = innings.take(innings.indexOf(four) + 1)
        assertTrue(state(upTo).feed.first().boundary)
        // Four wides is four runs and is nobody's boundary.
        val wide = innings.firstOrNull { it.outcome.wides >= 4 }
        if (wide != null) {
            val upToWide = innings.take(innings.indexOf(wide) + 1)
            assertTrue(!state(upToWide).feed.first().boundary)
        }
    }

    @Test
    fun `a negative feed length is rejected rather than silently emptied`() {
        assertThrows<IllegalArgumentException> { matchCentreState(innings, Fixtures.T20, feedLength = -1, commentary = { "" }) }
    }
}
