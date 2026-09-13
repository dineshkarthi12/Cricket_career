package com.cricketcareer.presentation

import com.cricketcareer.engine.career.Appearance
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RecordsStateTest {

    private var n = 0

    private fun innings(
        runs: Int,
        out: Boolean = true,
        balls: Int = runs + 10,
        wickets: Int = 0,
        conceded: Int = 0,
        ballsBowled: Int = 0,
        format: MatchFormat = MatchFormat.LIST_A,
        level: LadderLevel = LadderLevel.STATE_FIRST_CLASS,
    ) = Appearance(
        fixture = "m${n++}",
        date = LocalDate.of(2026, 4, 1).plusDays(n.toLong()),
        format = format,
        level = level,
        runs = runs,
        ballsFaced = balls,
        out = out,
        wickets = wickets,
        runsConceded = conceded,
        ballsBowled = ballsBowled,
    )

    @Test
    fun `overs are six to the over, not ten`() {
        // 43 balls is 7.1, and the dot is a separator rather than a decimal
        // point. Dividing by six here is the classic scorecard bug.
        assertEquals("7.1", overs(43))
        assertEquals("0.0", overs(0))
        assertEquals("10.0", overs(60))
        assertEquals("0.5", overs(5))
    }

    @Test
    fun `a career total is grouped so it can be read`() {
        assertEquals("999", grouped(999))
        assertEquals("1,204", grouped(1204))
        assertEquals("12,041", grouped(12041))
        assertEquals("0", grouped(0))
    }

    @Test
    fun `a player never dismissed shows a dash, not a zero average`() {
        val state = recordsState(listOf(innings(45, out = false)))
        assertEquals("-", state.batting.first { it.isTotal }.average)
    }

    @Test
    fun `a not-out best keeps its asterisk all the way to the screen`() {
        val state = recordsState(listOf(innings(140, out = false), innings(10)))
        assertEquals("140*", state.batting.first { it.isTotal }.highest)
    }

    @Test
    fun `the career line is last and is marked as the total`() {
        val state = recordsState(listOf(innings(40, format = MatchFormat.T20), innings(60)))
        assertTrue(state.batting.last().isTotal)
        assertEquals("Career", state.batting.last().label)
        assertEquals(1, state.batting.count { it.isTotal })
    }

    @Test
    fun `format rows follow the order the world lists them in`() {
        val played = listOf(
            innings(40, format = MatchFormat.LIST_A),
            innings(20, format = MatchFormat.T20),
        )
        val state = recordsState(played, formatOrder = listOf(MatchFormat.T20.id, MatchFormat.LIST_A.id))
        assertEquals(
            listOf(MatchFormat.T20.id, MatchFormat.LIST_A.id, "Career"),
            state.batting.map { it.label },
        )
    }

    @Test
    fun `a format the player has never played does not appear`() {
        val state = recordsState(
            listOf(innings(40, format = MatchFormat.T20)),
            formatOrder = listOf(MatchFormat.T20.id, MatchFormat.LIST_A.id),
        )
        assertEquals(listOf(MatchFormat.T20.id, "Career"), state.batting.map { it.label })
    }

    @Test
    fun `a batter who has never bowled gets no bowling table`() {
        val state = recordsState(listOf(innings(40), innings(12)))
        assertFalse(state.hasBowled)
        // The career line is still there, because a screen showing the table at
        // all needs something in it - hasBowled is what decides whether to.
        assertEquals("-", state.bowling.single().best)
    }

    @Test
    fun `bowling figures read as a scorecard writes them`() {
        val state = recordsState(
            listOf(
                innings(0, wickets = 5, conceded = 40, ballsBowled = 54),
                innings(0, wickets = 1, conceded = 20, ballsBowled = 30),
            ),
        )
        val career = state.bowling.first { it.isTotal }
        assertTrue(state.hasBowled)
        assertEquals("5/40", career.best)
        assertEquals("14.0", career.overs)
        assertEquals("10.00", career.average)
        assertEquals("1", career.fiveFors)
    }

    @Test
    fun `milestones read newest first`() {
        val state = recordsState(listOf(innings(120), innings(30), innings(150)))
        val texts = state.milestones.map { it.text }
        assertEquals("Career-best score: 150", texts.first())
        assertEquals("First-team debut", texts.last())
    }

    @Test
    fun `levels are listed from the bottom of the ladder up`() {
        val state = recordsState(
            listOf(
                innings(40, level = LadderLevel.STATE_FIRST_CLASS),
                innings(20, level = LadderLevel.COLLEGE),
            ),
        )
        assertEquals(
            listOf(LadderLevel.COLLEGE.displayName, LadderLevel.STATE_FIRST_CLASS.displayName),
            state.byLevel.map { it.label },
        )
    }

    @Test
    fun `an empty career renders rather than throwing`() {
        val state = recordsState(emptyList())
        assertEquals("-", state.batting.single().average)
        assertEquals("0", state.batting.single().runs)
        assertTrue(state.milestones.isEmpty())
        assertFalse(state.hasBowled)
    }
}
