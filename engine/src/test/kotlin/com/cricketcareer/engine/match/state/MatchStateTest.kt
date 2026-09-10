package com.cricketcareer.engine.match.state

import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.BoundaryShape
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.model.world.PitchArchetype
import com.cricketcareer.engine.model.world.Venue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchStateTest {

    private val venue = Venue(
        id = "v1", name = "Test Ground", city = "Chennai", country = "IND", region = "TN",
        boundary = BoundaryShape.AVERAGE,
        archetypeWeights = mapOf(PitchArchetype.BALANCED to 1.0),
    )

    private fun xi(prefix: String) = (1..11).map { PlayerId("$prefix$it") }

    /** Six bowlers, so a full limited-overs innings fits inside the per-bowler cap. */
    private val attack = (1..6).map { PlayerId("x$it") }

    /** The next bowler who is legally allowed to bowl. */
    private fun InningsState.rotateBowler() {
        setBowler(attack.first { mayBowlNextOver(it) })
    }

    private fun match(format: MatchFormat) = MatchState(
        MatchSetup(format, venue, "HOME", "AWAY", xi("h"), xi("a"), seed = 1L),
        Pitch.AVERAGE,
    )

    /** Score [runs] then finish the innings by declaring, so the test can move on. */
    private fun InningsState.scoreAndClose(runs: Int) {
        rotateBowler()
        var scored = 0
        while (scored < runs && !isComplete) {
            record(DeliveryOutcome.offBat(minOf(4, runs - scored)))
            scored = this.runs
            if (atEndOfOver && !isComplete) rotateBowler()
        }
        if (!isComplete) declare()
    }

    @Test
    fun `an XI cannot contain the same player twice`() {
        assertThrows(IllegalArgumentException::class.java) {
            MatchSetup(MatchFormat.T20, venue, "HOME", "AWAY", listOf(PlayerId("a"), PlayerId("a")), xi("b"), 1L)
        }
    }

    @Test
    fun `a player cannot appear for both sides`() {
        assertThrows(IllegalArgumentException::class.java) {
            MatchSetup(MatchFormat.T20, venue, "HOME", "AWAY", xi("h"), xi("h"), 1L)
        }
    }

    @Test
    fun `the first innings of a limited-overs match has no target`() {
        val m = match(MatchFormat.T20)
        assertNull(m.startInnings("HOME").target)
    }

    @Test
    fun `the chasing side needs one more than the lead`() {
        // The classic off-by-one: 180 scored means 181 to win, not 180.
        val m = match(MatchFormat.T20)
        m.startInnings("HOME").scoreAndClose(180)
        assertEquals(181, m.startInnings("AWAY").target)
    }

    @Test
    fun `a successful chase is won by wickets`() {
        val m = match(MatchFormat.T20)
        m.startInnings("HOME").scoreAndClose(100)
        val chase = m.startInnings("AWAY")
        chase.rotateBowler()
        var scored = 0
        while (!chase.isComplete) {
            chase.record(DeliveryOutcome.offBat(4))
            scored += 4
            if (chase.atEndOfOver && !chase.isComplete) chase.rotateBowler()
        }
        val result = m.concludeIfFinished()
        assertTrue(result is MatchResult.WonByWickets, "was $result")
        assertEquals("AWAY", (result as MatchResult.WonByWickets).winner)
        assertEquals(10, result.wickets)
        assertTrue(scored >= 101)
    }

    @Test
    fun `a defended total is won by runs`() {
        val m = match(MatchFormat.T20)
        m.startInnings("HOME").scoreAndClose(200)
        val chase = m.startInnings("AWAY")
        chase.rotateBowler()
        // All out for 20.
        chase.record(DeliveryOutcome.offBat(4))
        chase.record(DeliveryOutcome.offBat(4))
        chase.record(DeliveryOutcome.offBat(4))
        chase.record(DeliveryOutcome.offBat(4))
        chase.record(DeliveryOutcome.offBat(4))
        while (!chase.isComplete) {
            chase.record(DeliveryOutcome(dismissal = Dismissal(DismissalMode.BOWLED, chase.striker, chase.bowler)))
            if (chase.atEndOfOver && !chase.isComplete) chase.rotateBowler()
        }
        val result = m.concludeIfFinished()
        assertTrue(result is MatchResult.WonByRuns, "was $result")
        assertEquals(180, (result as MatchResult.WonByRuns).runs)
    }

    @Test
    fun `level scores with the innings complete is a tie`() {
        val m = match(MatchFormat.T20)
        m.startInnings("HOME").scoreAndClose(100)
        val chase = m.startInnings("AWAY")
        chase.scoreAndClose(100)
        assertTrue(m.concludeIfFinished() is MatchResult.Tied)
    }

    @Test
    fun `a multi-day match with time expired is drawn`() {
        val m = match(MatchFormat.TEST)
        m.startInnings("HOME").scoreAndClose(400)
        assertTrue(m.concludeIfFinished(timeExpired = true) is MatchResult.Drawn)
    }

    @Test
    fun `a limited-overs match with time expired is a no result`() {
        val m = match(MatchFormat.T20)
        m.startInnings("HOME").scoreAndClose(40)
        assertTrue(m.concludeIfFinished(timeExpired = true) is MatchResult.NoResult)
    }

    @Test
    fun `the follow-on becomes available once the deficit is large enough`() {
        val m = match(MatchFormat.TEST)
        assertFalse(m.followOnAvailable())
        m.startInnings("HOME").scoreAndClose(500)
        assertFalse(m.followOnAvailable())
        m.startInnings("AWAY").scoreAndClose(250)
        // 250 behind, and a Test needs 200.
        assertTrue(m.followOnAvailable())
    }

    @Test
    fun `the follow-on is not available on a small deficit`() {
        val m = match(MatchFormat.TEST)
        m.startInnings("HOME").scoreAndClose(300)
        m.startInnings("AWAY").scoreAndClose(250)
        assertFalse(m.followOnAvailable())
    }

    @Test
    fun `the follow-on does not exist in limited-overs cricket`() {
        val m = match(MatchFormat.T20)
        m.startInnings("HOME").scoreAndClose(200)
        m.startInnings("AWAY").scoreAndClose(20)
        assertFalse(m.followOnAvailable())
    }

    @Test
    fun `a side cannot bat more times than the format allows`() {
        val m = match(MatchFormat.T20)
        m.startInnings("HOME").scoreAndClose(100)
        m.startInnings("AWAY").scoreAndClose(50)
        assertThrows(IllegalStateException::class.java) { m.startInnings("HOME") }
    }

    @Test
    fun `two innings cannot run at once`() {
        val m = match(MatchFormat.T20)
        m.startInnings("HOME")
        assertThrows(IllegalStateException::class.java) { m.startInnings("AWAY") }
    }

    @Test
    fun `days advance only in multi-day cricket and only up to the scheduled number`() {
        val test = match(MatchFormat.TEST)
        repeat(4) { test.advanceDay() }
        assertEquals(5, test.day)
        assertThrows(IllegalStateException::class.java) { test.advanceDay() }

        assertThrows(IllegalStateException::class.java) { match(MatchFormat.T20).advanceDay() }
    }

    @Test
    fun `an abandoned match has no result`() {
        val m = match(MatchFormat.T20)
        m.abandon("rain")
        assertTrue(m.isComplete)
        assertTrue(m.result is MatchResult.NoResult)
    }
}
