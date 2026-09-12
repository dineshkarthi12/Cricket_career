package com.cricketcareer.presentation

import com.cricketcareer.engine.match.state.MatchInterruption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rain panel.
 *
 * It exists because a revised target is a number nobody can account for without
 * it: a scoreboard showing a target of 292 against an opposition score of 322,
 * and saying nothing else, looks broken.
 */
class RainStateTest {

    private val stoppage = MatchInterruption(
        innings = 2,
        afterOvers = 36,
        oversBefore = 50,
        oversAfter = 46,
        wicketsLost = 2,
        runs = 180,
        targetBefore = 323,
        revisedTarget = 292,
    )

    @Test
    fun `a dry match has nothing to explain`() {
        val state = rainState(emptyList())
        assertTrue(!state.wasAffected)
        assertTrue(state.stoppages.isEmpty())
        assertNull(state.targetLine)
    }

    @Test
    fun `a stoppage says when it happened and what the score was`() {
        val line = rainState(listOf(stoppage)).stoppages.single()
        assertEquals("Rain stopped play — 36 overs, 180/2", line.headline)
    }

    @Test
    fun `it says what the innings is now and what was lost`() {
        val line = rainState(listOf(stoppage)).stoppages.single()
        assertEquals("Innings reduced to 46 overs (4 lost)", line.overs)
    }

    @Test
    fun `it says what the target became`() {
        assertEquals("Target revised to 292", rainState(listOf(stoppage)).stoppages.single().target)
    }

    @Test
    fun `a first-innings stoppage has no target to revise`() {
        val first = stoppage.copy(innings = 1, targetBefore = null, revisedTarget = null)
        val line = rainState(listOf(first)).stoppages.single()
        assertNull(line.target)
        assertNull(line.requiredRate)
    }

    @Test
    fun `the required rate is shown on both sides of the stoppage`() {
        // The number that tells a player whether the rain helped him, and it
        // goes both ways: a side well ahead of par is asked for less afterwards.
        val line = rainState(listOf(stoppage)).stoppages.single()
        // 143 off 14 before, 112 off 10 after.
        assertEquals("Required rate 10.21 → 11.20", line.requiredRate)
    }

    @Test
    fun `a side already home has no rate left to quote`() {
        val done = stoppage.copy(oversAfter = 36)
        assertNull(rainState(listOf(done)).stoppages.single().requiredRate)
    }

    @Test
    fun `the final target line spells out the whole task`() {
        val state = rainState(listOf(stoppage), finalTarget = 292, finalOvers = 46)
        assertEquals("DLS target: 292 from 46 overs", state.targetLine)
    }
}
