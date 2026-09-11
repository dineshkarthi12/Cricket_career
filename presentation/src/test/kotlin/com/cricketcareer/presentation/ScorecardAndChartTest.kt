package com.cricketcareer.presentation

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.sim.InningsSimulator
import com.cricketcareer.engine.match.state.InningsState
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.rng.MatchRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScorecardAndChartTest {

    private val events = mutableListOf<BallEvent>()

    private val innings: InningsState = run {
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
    }

    private val names = Names(Fixtures.averageTeams().first + Fixtures.averageTeams().second)
    private val card = scorecardState(innings.snapshot(), names)
    private val charts = chartState(events)

    // ---- Scorecard ------------------------------------------------------

    @Test
    fun `the card is in batting order, not sorted by runs`() {
        // A card sorted by score is a table of figures. It loses who came in
        // when, which is what a card is for.
        val positions = innings.snapshot().batting.sortedBy { it.position }.map { it.player }
        assertEquals(positions.map { names.short(it) }, card.batting.map { it.name })
    }

    @Test
    fun `nobody on the card is described as b b someone`() {
        card.batting.forEach {
            assertTrue(!it.howOut.startsWith("b b ")) { "${it.name}: ${it.howOut}" }
            assertTrue(!it.howOut.contains("null")) { "${it.name}: ${it.howOut}" }
            assertTrue(it.howOut.isNotBlank()) { "${it.name} has no how-out" }
        }
    }

    @Test
    fun `a not out batter carries a star and a man who did not bat does not`() {
        card.batting.filter { it.notOut && it.batted }.forEach {
            assertTrue(it.runsDisplay.endsWith("*")) { "${it.name} ${it.runsDisplay}" }
        }
        card.batting.filter { !it.batted }.forEach {
            assertEquals(HowOut.DID_NOT_BAT, it.howOut)
            assertTrue(!it.runsDisplay.endsWith("*")) { "${it.name} did not bat but has a star" }
        }
    }

    @Test
    fun `a strike rate off no balls is blank, not zero`() {
        // 0.0 reads as a man who blocked fourteen. It does not exist.
        card.batting.filter { it.balls == 0 }.forEach {
            assertEquals("-", it.strikeRate) { "${it.name}" }
        }
    }

    @Test
    fun `the extras breakdown drops the categories that were zero`() {
        val source = innings.snapshot()
        if (source.byes == 0) assertTrue(!card.extrasBreakdown.contains("b 0")) { card.extrasBreakdown }
        if (source.wides > 0) assertTrue(card.extrasBreakdown.contains("w ${source.wides}")) { card.extrasBreakdown }
        assertEquals(source.extras, card.extrasTotal)
    }

    @Test
    fun `the card's runs and the engine's total agree`() {
        // Presentation formats; it does not re-derive. Two places computing one
        // total is two places to disagree.
        val fromCard = card.batting.sumOf { it.runs } + card.extrasTotal
        assertEquals(innings.snapshot().runs, fromCard)
    }

    @Test
    fun `bowling figures read as wickets-runs with a real economy`() {
        card.bowling.forEach {
            assertEquals("${it.wickets}-${it.runs}", it.figures)
            assertTrue(it.economy != "-") { "${it.name} bowled ${it.overs} and has no economy" }
        }
    }

    // ---- Charts ---------------------------------------------------------

    @Test
    fun `the worm plots against legal balls, not deliveries`() {
        // Plot deliveries and the line walks one step right per wide, ending
        // past the right edge of an axis that says twenty overs.
        val legal = events.count { it.outcome.isLegalBall }
        assertEquals(legal, charts.worm.last().legalBalls)
        assertTrue(events.size > legal) { "this seed bowled no extras, so the test proves nothing" }
        assertEquals(120, charts.worm.last().legalBalls)
    }

    @Test
    fun `the worm starts at the origin and never goes backwards`() {
        assertEquals(WormPoint(0, 0), charts.worm.first())
        charts.worm.zipWithNext { a, b ->
            assertTrue(b.runs >= a.runs) { "runs fell from ${a.runs} to ${b.runs}" }
            assertTrue(b.legalBalls >= a.legalBalls) { "balls went backwards" }
        }
    }

    @Test
    fun `the manhattan has a bar for every over, including the quiet ones`() {
        // An over where nothing happened must still get a zero bar, or the
        // chart silently shifts every later over one place to the left.
        val overs = events.maxOf { it.id.over } + 1
        assertEquals(overs, charts.manhattan.size)
        assertEquals((1..overs).toList(), charts.manhattan.map { it.over })
    }

    @Test
    fun `the manhattan totals reconcile with the innings`() {
        assertEquals(innings.snapshot().runs, charts.manhattan.sumOf { it.runs })
        assertEquals(innings.snapshot().wickets, charts.manhattan.sumOf { it.wickets })
    }

    @Test
    fun `the pitch map has one point per delivery`() {
        assertEquals(events.size, charts.pitchMap.size)
        charts.pitchMap.forEach {
            assertTrue(it.lengthMetres.isFinite()) { "length ${it.lengthMetres}" }
            assertTrue(it.lineMetres.isFinite()) { "line ${it.lineMetres}" }
        }
    }

    @Test
    fun `a full toss is flagged rather than plotted behind the stumps`() {
        // The engine gives a ball that never pitched a negative length. Plotted
        // naively that is a dot off the bottom of the strip.
        charts.pitchMap.forEach {
            assertEquals(it.lengthMetres <= 0.0, it.fullToss) {
                "length ${it.lengthMetres} but fullToss=${it.fullToss}"
            }
        }
        val tosses = charts.pitchMap.count { it.fullToss }
        assertTrue(tosses > 0) { "this seed bowled no full toss, so the test proves nothing" }
        assertTrue(tosses < charts.pitchMap.size / 4) { "$tosses full tosses is not cricket" }
    }

    @Test
    fun `the wagon wheel only holds balls that were actually hit`() {
        // A spoke at zero for every leave turns the middle into a blob and
        // hides the scoring areas, which is the one thing the chart is for.
        assertTrue(charts.wagonWheel.all { it.runs > 0 })
        assertEquals(events.count { it.trajectory != null && it.outcome.runsOffBat > 0 }, charts.wagonWheel.size)
    }

    @Test
    fun `the target line spans the innings and stops one short of the target`() {
        // A chase of 215 is level on 214: the line the batting side must beat.
        val line = checkNotNull(targetLine(target = 215, format = Fixtures.T20))
        assertEquals(WormPoint(0, 0), line.first())
        assertEquals(WormPoint(120, 214), line.last())
    }

    @Test
    fun `an innings with no balls charts nothing rather than crashing`() {
        val empty = chartState(emptyList())
        assertEquals(listOf(WormPoint(0, 0)), empty.worm)
        assertTrue(empty.manhattan.isEmpty())
        assertTrue(empty.pitchMap.isEmpty())
        assertTrue(empty.wagonWheel.isEmpty())
    }
}
