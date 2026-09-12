package com.cricketcareer.engine.match.drs

import com.cricketcareer.engine.config.DrsTuning
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The review system.
 *
 * Two things are being kept apart: what the ball did, and what the players
 * thought it did. The adjudication half has no judgement in it at all — the
 * same tracking always comes back the same way — and that is what these tests
 * pin down first.
 */
class DrsTest {

    private val tuning = DrsTuning()

    private fun tracking(pitching: Double = 3.0, impact: Double = 3.0, wickets: Double = 3.0) =
        BallTracking(pitching, impact, wickets)

    private fun rng(seed: Long = 5) = SimRandom.fromSeed(seed)

    // ---- the tracking itself -----------------------------------------------

    @Test
    fun `an lbw is only as clear as its least clear question`() {
        // A chain is as strong as its weakest link. Plumb in front, hitting
        // middle, and it pitched a whisker outside leg: not out, and the
        // margin has to say so.
        val marginal = tracking(pitching = 0.05, impact = 4.0, wickets = 4.0)
        assertEquals(0.05, marginal.outMargin, 1e-12)
        assertTrue(marginal.isOut)

        val outsideLeg = tracking(pitching = -0.4, impact = 4.0, wickets = 4.0)
        assertTrue(!outsideLeg.isOut)
    }

    // ---- adjudication ------------------------------------------------------

    @Test
    fun `a clear mistake is overturned`() {
        val given = Drs.adjudicate(onFieldOut = true, tracking = tracking(wickets = -2.0), by = ReviewingSide.BATTING, reviewsRemaining = 2, tuning = tuning)
        assertEquals(ReviewOutcome.OVERTURNED, given.outcome)
        assertTrue(!given.isOut)
        assertTrue(given.changedTheDecision)

        val turnedDown = Drs.adjudicate(onFieldOut = false, tracking = tracking(), by = ReviewingSide.FIELDING, reviewsRemaining = 2, tuning = tuning)
        assertEquals(ReviewOutcome.OVERTURNED, turnedDown.outcome)
        assertTrue(turnedDown.isOut)
    }

    @Test
    fun `an overturn costs nobody a review`() {
        val review = Drs.adjudicate(true, tracking(wickets = -2.0), ReviewingSide.BATTING, reviewsRemaining = 2, tuning = tuning)
        assertEquals(2, review.reviewsLeft)
    }

    @Test
    fun `a review that agrees with the umpire is gone`() {
        val review = Drs.adjudicate(true, tracking(), ReviewingSide.BATTING, reviewsRemaining = 2, tuning = tuning)
        assertEquals(ReviewOutcome.STRUCK_DOWN, review.outcome)
        assertEquals(1, review.reviewsLeft)
        assertTrue(!review.changedTheDecision)
    }

    @Test
    fun `umpire's call leaves the decision alone and the review intact`() {
        // The most misunderstood rule in cricket and the most important one
        // here: it is why the same tracking can be out at one end and not out
        // at the other.
        val band = tuning.umpiresCallBand
        val justOut = Drs.adjudicate(true, tracking(wickets = band / 2), ReviewingSide.BATTING, 2, tuning)
        val justNotOut = Drs.adjudicate(false, tracking(wickets = band / 2), ReviewingSide.FIELDING, 2, tuning)

        assertEquals(ReviewOutcome.UMPIRES_CALL, justOut.outcome)
        assertEquals(ReviewOutcome.UMPIRES_CALL, justNotOut.outcome)
        assertTrue(justOut.isOut) { "the on-field decision must stand" }
        assertTrue(!justNotOut.isOut) { "the on-field decision must stand" }
        assertEquals(2, justOut.reviewsLeft)
        assertEquals(2, justNotOut.reviewsLeft)
    }

    @Test
    fun `identical tracking can be out at one end and not out at the other`() {
        val same = tracking(wickets = 0.05)
        assertTrue(Drs.adjudicate(true, same, ReviewingSide.BATTING, 2, tuning).isOut)
        assertTrue(!Drs.adjudicate(false, same, ReviewingSide.FIELDING, 2, tuning).isOut)
    }

    @Test
    fun `adjudication is a pure function of the tracking and the decision`() {
        repeat(20) {
            assertEquals(
                Drs.adjudicate(true, tracking(wickets = 0.4), ReviewingSide.BATTING, 2, tuning),
                Drs.adjudicate(true, tracking(wickets = 0.4), ReviewingSide.BATTING, 2, tuning),
            )
        }
    }

    // ---- whether anybody goes upstairs -------------------------------------

    @Test
    fun `a side with no reviews left cannot review`() {
        assertNull(
            Drs.consider(
                onFieldOut = true, tracking = tracking(wickets = -5.0), reviewsRemaining = 0,
                judgement = 0.9, desperate = false, random = rng(), tuning = tuning,
            ),
        )
    }

    @Test
    fun `a plumb decision is not reviewed, and a howler nearly always is`() {
        fun rate(wickets: Double, out: Boolean) = (1..800).count { seed ->
            Drs.consider(out, tracking(wickets = wickets), 2, 0.92, false, rng(seed.toLong()), tuning) != null
        } / 800.0

        // Given out, and it was crashing into middle: nobody reviews that.
        assertTrue(rate(4.0, out = true) < 0.02) { "%.3f of plumb decisions were reviewed".format(rate(4.0, true)) }
        // Given out, and it was missing by a mile: nearly everybody does.
        assertTrue(rate(-4.0, out = true) > 0.85) { "%.3f of howlers were reviewed".format(rate(-4.0, true)) }
    }

    @Test
    fun `a side that reads the ball better wastes fewer reviews`() {
        fun wasted(judgement: Double): Double {
            val taken = (1..3000).mapNotNull { seed ->
                Drs.consider(true, tracking(wickets = 0.9), 2, judgement, false, rng(seed.toLong()), tuning)
            }
            return if (taken.isEmpty()) 0.0 else taken.count { it.outcome == ReviewOutcome.STRUCK_DOWN }.toDouble() / taken.size
        }
        // The ball was clearly hitting, so every review of it is a waste; the
        // question is how often each side is fooled into taking one.
        val international = (1..3000).count {
            Drs.consider(true, tracking(wickets = 0.9), 2, 0.92, false, rng(it.toLong()), tuning) != null
        }
        val district = (1..3000).count {
            Drs.consider(true, tracking(wickets = 0.9), 2, 0.34, false, rng(it.toLong()), tuning) != null
        }
        assertTrue(district > international) { "district $district, international $international" }
        assertTrue(wasted(0.92) > 0.5) { "a review of a ball that was clearly hitting should usually be struck down" }
    }

    @Test
    fun `the last pair reviews things nobody else would`() {
        fun rate(desperate: Boolean) = (1..1500).count { seed ->
            Drs.consider(true, tracking(wickets = 1.1), 2, 0.92, desperate, rng(seed.toLong()), tuning) != null
        } / 1500.0

        assertTrue(rate(desperate = true) > rate(desperate = false) * 2) {
            "desperate %.3f, calm %.3f".format(rate(true), rate(false))
        }
    }

    @Test
    fun `whoever the decision went against is the side that reviews it`() {
        val batting = Drs.consider(true, tracking(wickets = -5.0), 2, 0.92, false, rng(), tuning)
        val fielding = Drs.consider(false, tracking(wickets = 5.0), 2, 0.92, false, rng(), tuning)

        assertEquals(ReviewingSide.BATTING, checkNotNull(batting).by)
        assertEquals(ReviewingSide.FIELDING, checkNotNull(fielding).by)
    }

    @Test
    fun `the same seed gives the same review`() {
        assertEquals(
            Drs.consider(true, tracking(wickets = -1.0), 2, 0.8, false, rng(31), tuning),
            Drs.consider(true, tracking(wickets = -1.0), 2, 0.8, false, rng(31), tuning),
        )
    }

    // ---- where the technology exists ---------------------------------------

    @Test
    fun `there is no review system below the top of the ladder`() {
        // A player's whole career at district level is played under umpires who
        // are wrong more often and cannot be corrected, which is one of the
        // things that makes climbing mean something.
        assertTrue(Drs.availableAt(LadderLevel.INTERNATIONAL, tuning))
        assertTrue(Drs.availableAt(LadderLevel.FRANCHISE_T20, tuning))
        assertTrue(!Drs.availableAt(LadderLevel.STATE_FIRST_CLASS, tuning))
        assertTrue(!Drs.availableAt(LadderLevel.DISTRICT_CLUB, tuning))
        assertTrue(!Drs.availableAt(LadderLevel.COLLEGE, tuning))
    }
}
