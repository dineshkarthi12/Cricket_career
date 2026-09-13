package com.cricketcareer.engine.career

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RecordsTest {

    private var day = 0L

    private fun innings(
        runs: Int,
        out: Boolean = true,
        balls: Int = runs + 10,
        wickets: Int = 0,
        conceded: Int = 0,
        ballsBowled: Int = 0,
        format: MatchFormat = Fixtures.LIST_A,
    ): Appearance = Appearance(
        fixture = "m${day++}",
        date = LocalDate.of(2026, 4, 1).plusDays(day),
        format = format,
        level = LadderLevel.STATE_FIRST_CLASS,
        runs = runs,
        ballsFaced = balls,
        out = out,
        wickets = wickets,
        runsConceded = conceded,
        ballsBowled = ballsBowled,
    )

    @Test
    fun `average is runs per dismissal, not runs per innings`() {
        val record = Records.batting(listOf(innings(50), innings(30, out = false), innings(20)))
        assertEquals(3, record.innings)
        assertEquals(1, record.notOuts)
        assertEquals(100, record.runs)
        // 100 runs, twice out.
        assertEquals(50.0, record.average!!, 1e-9)
    }

    @Test
    fun `a player never dismissed has no average at all`() {
        // Not an infinite one, and certainly not runs-per-innings quietly
        // substituted. A scorecard leaves the column blank.
        val record = Records.batting(listOf(innings(12, out = false)))
        assertNull(record.average)
    }

    @Test
    fun `a not-out best keeps its asterisk, and beats the same score out`() {
        val record = Records.batting(listOf(innings(72), innings(72, out = false)))
        assertEquals("72*", record.highestScoreText)
    }

    @Test
    fun `a higher score out still beats a lower one not out`() {
        val record = Records.batting(listOf(innings(90, out = false), innings(120)))
        assertEquals("120", record.highestScoreText)
    }

    @Test
    fun `padding up is not an innings`() {
        // In the XI three times, batted twice: a number eleven's career is full
        // of this and a records screen that counts them as ducks is libel.
        val record = Records.batting(
            listOf(
                innings(4),
                innings(0, out = true, balls = 3),
                Appearance("m99", LocalDate.of(2026, 5, 1), Fixtures.LIST_A, LadderLevel.STATE_FIRST_CLASS, 0, 0, false),
            ),
        )
        assertEquals(3, record.matches)
        assertEquals(2, record.innings)
        assertEquals(1, record.ducks)
    }

    @Test
    fun `nought not out is not a duck`() {
        val record = Records.batting(listOf(innings(0, out = false, balls = 6)))
        assertEquals(1, record.innings)
        assertEquals(0, record.ducks)
    }

    @Test
    fun `best bowling is most wickets, then fewest runs`() {
        val record = Records.bowling(
            listOf(
                innings(0, wickets = 4, conceded = 12, ballsBowled = 30),
                innings(0, wickets = 5, conceded = 62, ballsBowled = 60),
                innings(0, wickets = 5, conceded = 40, ballsBowled = 54),
            ),
        )
        assertEquals("5/40", record.bestText)
        assertEquals(2, record.fiveWicketHauls)
    }

    @Test
    fun `an economy rate is runs per six balls`() {
        val record = Records.bowling(listOf(innings(0, wickets = 1, conceded = 30, ballsBowled = 60)))
        assertEquals(3.0, record.economy!!, 1e-9)
        assertEquals(60.0, record.strikeRate!!, 1e-9)
    }

    @Test
    fun `a career total is marked on the match that carried it past the mark, once`() {
        val book = Records.of(
            List(20) { innings(60) } + List(20) { innings(60) },
        )
        val thousands = book.milestones.filter { it.description == "1000 career runs" }
        assertEquals(1, thousands.size)
        // 17 innings of 60 is 1020, so the 17th is the one that did it.
        assertEquals("m16", thousands.single().fixture)
    }

    @Test
    fun `a career best is only announced once there is something to beat`() {
        val book = Records.of(listOf(innings(3), innings(40), innings(41)))
        val bests = book.milestones.filter { it.description.startsWith("Career-best score") }
        assertEquals(listOf("Career-best score: 40", "Career-best score: 41"), bests.map { it.description })
    }

    @Test
    fun `a debut is recorded once overall and once per format`() {
        val book = Records.of(
            listOf(
                innings(10, format = Fixtures.LIST_A),
                innings(10, format = Fixtures.LIST_A),
                innings(10, format = Fixtures.T20),
            ),
        )
        assertEquals(1, book.milestones.count { it.description == "First-team debut" })
        assertEquals(
            listOf("50-over debut", "Twenty20 debut"),
            book.milestones.map { it.description }.filter { it.endsWith(" debut") && it != "First-team debut" },
        )
    }

    @Test
    fun `the milestone list does not depend on the order appearances arrive in`() {
        // A save file reload must not reshuffle a career.
        val played = listOf(innings(120), innings(30), innings(150), innings(0))
        val forwards = Records.of(played).milestones
        val backwards = Records.of(played.reversed()).milestones
        assertEquals(forwards, backwards)
    }

    @Test
    fun `format lines add up to the career line`() {
        val played = List(6) { innings(40, format = Fixtures.LIST_A) } +
            List(4) { innings(25, format = Fixtures.T20) }
        val book = Records.of(played)
        assertEquals(book.batting.runs, book.battingByFormat.values.sumOf { it.runs })
        assertEquals(book.batting.innings, book.battingByFormat.values.sumOf { it.innings })
        assertTrue(book.batting.highestScore >= book.battingByFormat.getValue(Fixtures.T20.id).highestScore)
    }

    @Test
    fun `an empty career is empty rather than broken`() {
        val book = Records.of(emptyList())
        assertEquals("-", book.batting.highestScoreText)
        assertEquals("-", book.bowling.bestText)
        assertNull(book.batting.average)
        assertNull(book.bowling.average)
        assertTrue(book.milestones.isEmpty())
    }
}
