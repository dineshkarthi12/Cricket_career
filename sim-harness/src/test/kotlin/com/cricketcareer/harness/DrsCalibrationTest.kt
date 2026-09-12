package com.cricketcareer.harness

import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.drs.ReviewOutcome
import com.cricketcareer.engine.match.sim.InningsSimulator
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.rng.MatchRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How the review system behaves over a season's worth of cricket.
 *
 * Bands, because the exact split is not knowable to a percentage point — what
 * matters is that a review is a scarce resource with a real chance of failing.
 * A system where reviews always succeed is a free wicket; one where they never
 * do is a button nobody presses.
 *
 * Reference figures for lbw reviews in real cricket: roughly a quarter
 * overturned, a third umpire's call, the rest struck down.
 */
class DrsCalibrationTest {

    private companion object {
        const val INNINGS = 400
    }

    private class Tally {
        var reviews = 0
        var overturned = 0
        var umpiresCall = 0
        var struckDown = 0
    }

    private fun sample(level: LadderLevel): Tally {
        val (batting, bowling) = Fixtures.averageTeams()
        val tally = Tally()
        repeat(INNINGS) { i ->
            InningsSimulator(
                format = Fixtures.LIST_A,
                battingSide = batting,
                bowlingSide = bowling,
                venue = Fixtures.AVERAGE_VENUE,
                pitch = Fixtures.AVERAGE_PITCH,
                weather = Weather.AVERAGE,
                level = level,
                random = MatchRandom(700L + i),
            ).simulate().reviews.forEach { review ->
                tally.reviews++
                when (review.outcome) {
                    ReviewOutcome.OVERTURNED -> tally.overturned++
                    ReviewOutcome.UMPIRES_CALL -> tally.umpiresCall++
                    ReviewOutcome.STRUCK_DOWN -> tally.struckDown++
                }
            }
        }
        return tally
    }

    @Test
    fun `an international innings sees a review every other game or so`() {
        val tally = sample(LadderLevel.INTERNATIONAL)
        val perInnings = tally.reviews.toDouble() / INNINGS
        assertTrue(perInnings in 0.15..1.5) { "%.2f reviews per innings".format(perInnings) }
    }

    @Test
    fun `reviews succeed about as often as they really do`() {
        val tally = sample(LadderLevel.INTERNATIONAL)
        assertTrue(tally.reviews > 50) { "only ${tally.reviews} reviews to measure" }

        val overturned = tally.overturned.toDouble() / tally.reviews
        val umpiresCall = tally.umpiresCall.toDouble() / tally.reviews
        val struckDown = tally.struckDown.toDouble() / tally.reviews

        assertTrue(overturned in 0.15..0.40) { "%.0f%% overturned".format(100 * overturned) }
        assertTrue(umpiresCall in 0.20..0.50) { "%.0f%% umpire's call".format(100 * umpiresCall) }
        assertTrue(struckDown in 0.20..0.50) { "%.0f%% struck down".format(100 * struckDown) }
    }

    @Test
    fun `there is no review system in domestic cricket`() {
        assertEquals(0, sample(LadderLevel.STATE_FIRST_CLASS).reviews)
        assertEquals(0, sample(LadderLevel.DISTRICT_CLUB).reviews)
    }
}
