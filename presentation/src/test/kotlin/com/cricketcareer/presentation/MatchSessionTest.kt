package com.cricketcareer.presentation

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.state.MatchResult
import com.cricketcareer.presentation.demo.DemoMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MatchSessionTest {

    private val demo = DemoMatch.simulate(seed = 77)
    private val session = demo.session

    /**
     * An innings that definitely contains at least two wickets.
     *
     * The navigation tests below are about the control, not about what seed 77
     * happens to produce. Pinning them to one seed made them a hidden
     * regression test on the match engine: re-calibrating the running model
     * changed the innings under them and a *presentation* test went red for a
     * reason that had nothing to do with presentation. So the fixture states
     * the precondition it actually needs and searches deterministically for a
     * seed that meets it.
     */
    private val twoWicketSession: MatchSession = (77L..200L)
        .asSequence()
        .map { DemoMatch.simulate(seed = it).session }
        .first { candidate -> candidate.toEnd().state.feed.count { it.wicket } >= 2 }

    @Test
    fun `a fresh session is sitting before the first ball`() {
        assertEquals(0, session.cursor)
        assertEquals("0-0", session.state.score)
        assertEquals("0.0", session.state.overs)
        assertTrue(session.totalBalls > 100) { "a T20 chase should have over a hundred deliveries" }
    }

    @Test
    fun `one ball at a time`() {
        val after = session.advance()
        assertEquals(1, after.cursor)
        assertEquals(1, after.state.feed.size)
        // The original is untouched: a session is a value, not a cursor someone
        // else is moving underneath the screen.
        assertEquals(0, session.cursor)
    }

    @Test
    fun `next wicket stops on the wicket, not after it`() {
        // The point of the control is to arrive at the moment the innings
        // turned, and the delivery that took it is the one worth reading.
        val atWicket = twoWicketSession.toNextWicket()
        assertTrue(atWicket.cursor > 0)
        assertEquals("W", atWicket.state.feed.first().chip)
        assertTrue(atWicket.state.feed.first().wicket)
    }

    @Test
    fun `next wicket from a wicket finds the following one`() {
        val first = twoWicketSession.toNextWicket()
        val second = first.toNextWicket()
        assertTrue(second.cursor > first.cursor) { "stuck at ${first.cursor}" }
        assertTrue(second.state.feed.first().wicket)
    }

    @Test
    fun `next wicket with no wickets left runs to the end`() {
        val end = session.toEnd()
        assertEquals(end.cursor, end.toNextWicket().cursor)
    }

    @Test
    fun `skipping to the end shows the whole innings`() {
        val end = session.toEnd()
        assertEquals(session.totalBalls, end.cursor)
        assertTrue(end.isFinished)
        assertTrue(end.state.result != null) { "a finished match should show its result" }
    }

    @Test
    fun `the result is withheld until the match is over`() {
        assertNull(session.state.result)
        assertNull(session.advance(5).state.result)
        assertTrue(session.toEnd().state.result != null)
    }

    @Test
    fun `a finished session ignores further controls`() {
        val end = session.toEnd()
        assertEquals(end, end.advance())
        assertEquals(end, end.toNextWicket())
    }

    @Test
    fun `reset puts it back before the first ball`() {
        assertEquals(0, session.toEnd().reset().cursor)
    }

    @Test
    fun `advancing past the end stops at the end rather than throwing`() {
        assertEquals(session.totalBalls, session.advance(10_000).cursor)
    }

    @Test
    fun `a cursor outside the innings is rejected`() {
        assertThrows<IllegalArgumentException> { session.copy(cursor = -1) }
        assertThrows<IllegalArgumentException> { session.copy(cursor = session.totalBalls + 1) }
        assertThrows<IllegalArgumentException> { session.advance(-1) }
    }

    @Test
    fun `the same seed shows the same match`() {
        val a = DemoMatch.simulate(seed = 4242)
        val b = DemoMatch.simulate(seed = 4242)
        assertEquals(a.session.toEnd().state.score, b.session.toEnd().state.score)
        assertEquals(a.result, b.result)
        assertEquals(a.homeTeam, b.homeTeam)
    }

    @Test
    fun `different seeds show different matches`() {
        val a = DemoMatch.simulate(seed = 1).session.toEnd().state.score
        val b = DemoMatch.simulate(seed = 2).session.toEnd().state.score
        assertTrue(a != b) { "two seeds produced the same score: $a" }
    }

    @Test
    fun `the demo hands over everything a screen needs`() {
        assertTrue(demo.homeTeam.isNotBlank())
        assertTrue(demo.awayTeam.isNotBlank())
        assertTrue(demo.firstInnings != null) { "no first innings" }
        assertTrue(demo.result != null) { "no result" }
        // And every player in the match can be named, or a scorecard would be
        // full of raw ids.
        val card = scorecardState(checkNotNull(demo.firstInnings), demo.names)
        assertTrue(card.batting.none { it.name.startsWith("?") }) { card.batting.map { it.name }.toString() }
    }

    @Test
    fun `an empty session is a real state rather than a crash`() {
        val empty = MatchSession.empty(Fixtures.T20)
        assertEquals(0, empty.totalBalls)
        assertTrue(empty.isFinished)
        assertEquals("0-0", empty.state.score)
    }

    // ---- Result text ----------------------------------------------------

    @Test
    fun `a result reads as a sentence, with the margin pluralised`() {
        assertEquals("MH won by 13 runs", resultText(MatchResult.WonByRuns("MH", "TN", 13)))
        assertEquals("MH won by 1 run", resultText(MatchResult.WonByRuns("MH", "TN", 1)))
        assertEquals("TN won by 4 wickets", resultText(MatchResult.WonByWickets("TN", "MH", 4)))
        assertEquals("TN won by 1 wicket", resultText(MatchResult.WonByWickets("TN", "MH", 1)))
    }

    @Test
    fun `a tie and a draw are not the same result`() {
        // Something a cricket supporter notices immediately and a distribution
        // test never will.
        assertEquals("Match tied", resultText(MatchResult.Tied("MH", "TN")))
        assertEquals("Match drawn", resultText(MatchResult.Drawn("MH", "TN")))
    }

    @Test
    fun `a match still in progress has no result to show`() {
        assertNull(resultText(null))
    }
}
