package com.cricketcareer.presentation

import com.cricketcareer.engine.config.DrsTuning
import com.cricketcareer.engine.match.drs.BallTracking
import com.cricketcareer.engine.match.drs.Drs
import com.cricketcareer.engine.match.drs.ReviewingSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The review screen.
 *
 * The thing being defended is that a viewer who has seen this on television
 * reads the same three lines here, in the same order, with the same one picked
 * out in yellow.
 */
class ReviewStateTest {

    private val tuning = DrsTuning()

    private fun state(tracking: BallTracking, onFieldOut: Boolean) = reviewState(
        review = Drs.adjudicate(
            onFieldOut = onFieldOut,
            tracking = tracking,
            by = if (onFieldOut) ReviewingSide.BATTING else ReviewingSide.FIELDING,
            reviewsRemaining = 2,
            tuning = tuning,
        ),
        battingTeam = "India",
        fieldingTeam = "Australia",
        umpiresCallBand = tuning.umpiresCallBand,
    )

    @Test
    fun `all three questions are always shown`() {
        // What the big screen does, and what makes the decision legible.
        // Showing only the leg that failed leaves a viewer wondering about the
        // other two.
        val shown = state(BallTracking(3.0, 3.0, 3.0), onFieldOut = true)
        assertEquals(listOf("Pitching", "Impact", "Wickets"), shown.findings.map { it.label })
    }

    @Test
    fun `the three questions read the way a big screen reads them`() {
        val plumb = state(BallTracking(2.0, 2.0, 2.0), onFieldOut = true)
        assertEquals(listOf("In line", "In line", "Hitting"), plumb.findings.map { it.verdict })

        val reprieve = state(BallTracking(-2.0, 2.0, 2.0), onFieldOut = true)
        assertEquals("Outside leg", reprieve.findings.first().verdict)

        val missing = state(BallTracking(2.0, 2.0, -2.0), onFieldOut = true)
        assertEquals("Missing", missing.findings.last().verdict)
    }

    @Test
    fun `only the question that made it umpire's call is marked`() {
        // An lbw is only as clear as its least clear question, so at most one
        // leg is ever the marginal one.
        val band = tuning.umpiresCallBand
        val marginal = state(BallTracking(3.0, 3.0, band / 2), onFieldOut = true)

        assertEquals(1, marginal.findings.count { it.marginal })
        assertEquals("Wickets", marginal.findings.single { it.marginal }.label)
    }

    @Test
    fun `nothing is marked when the verdict was not umpire's call`() {
        assertTrue(state(BallTracking(3.0, 3.0, 3.0), onFieldOut = true).findings.none { it.marginal })
        assertTrue(state(BallTracking(3.0, 3.0, -3.0), onFieldOut = true).findings.none { it.marginal })
    }

    @Test
    fun `the screen says who reviewed it`() {
        assertEquals("India review", state(BallTracking(3.0, 3.0, -3.0), onFieldOut = true).heading)
        assertEquals("Australia review", state(BallTracking(3.0, 3.0, 3.0), onFieldOut = false).heading)
    }

    @Test
    fun `the decision shown is the one that stands afterwards`() {
        val overturned = state(BallTracking(3.0, 3.0, -3.0), onFieldOut = true)
        assertEquals("NOT OUT", overturned.decision)
        assertTrue(overturned.changed)

        val stands = state(BallTracking(3.0, 3.0, 3.0), onFieldOut = true)
        assertEquals("OUT", stands.decision)
        assertTrue(!stands.changed)
    }

    @Test
    fun `one review left is not one reviews left`() {
        val one = reviewState(
            Drs.adjudicate(true, BallTracking(3.0, 3.0, 3.0), ReviewingSide.BATTING, 2, tuning),
            "India", "Australia", tuning.umpiresCallBand,
        )
        assertEquals("1 review remaining", one.reviewsLeft)

        val two = reviewState(
            Drs.adjudicate(true, BallTracking(3.0, 3.0, -3.0), ReviewingSide.BATTING, 2, tuning),
            "India", "Australia", tuning.umpiresCallBand,
        )
        assertEquals("2 reviews remaining", two.reviewsLeft)
    }

    @Test
    fun `the one-line summary says who, what and the outcome`() {
        val summary = state(BallTracking(3.0, 3.0, -3.0), onFieldOut = true).summary
        assertTrue(summary.contains("India"))
        assertTrue(summary.contains("Overturned"))
        assertTrue(summary.contains("NOT OUT"))
    }
}
