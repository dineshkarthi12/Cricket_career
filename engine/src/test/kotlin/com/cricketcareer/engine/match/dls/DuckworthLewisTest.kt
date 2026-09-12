package com.cricketcareer.engine.match.dls

import com.cricketcareer.engine.config.DlsTuning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The method that settles a rain-affected match.
 *
 * Most of these are not statistical: they are the properties a resource table
 * has to have before it is allowed anywhere near a result. A table that broke
 * one of them would produce matches decided in a way a cricket follower can see
 * is wrong, which is far worse than a table that is a few runs out.
 */
class DuckworthLewisTest {

    private val tuning = DlsTuning.DEFAULT

    private fun resources(overs: Double, wickets: Int) = DuckworthLewis.resources(overs, wickets, tuning)

    // ---- the table ---------------------------------------------------------

    @Test
    fun `a full fifty-over innings is a hundred per cent of the resources`() {
        assertEquals(100.0, resources(50.0, 0), 1e-9)
    }

    @Test
    fun `an exhausted innings has nothing left`() {
        assertEquals(0.0, resources(0.0, 0), 1e-12)
        assertEquals(0.0, resources(30.0, 10), 1e-12)
        assertEquals(0.0, resources(0.0, 10), 1e-12)
    }

    @Test
    fun `more overs is never worse`() {
        for (wickets in 0..9) {
            val curve = (0..100).map { resources(it * 0.5, wickets) }
            curve.zipWithNext { fewer, more ->
                assertTrue(more >= fewer) { "$wickets down: $fewer at fewer overs beat $more at more" }
            }
        }
    }

    @Test
    fun `losing a wicket always costs a side resource`() {
        // If it did not, a captain could profit from a run out, and a side
        // could improve its position by getting out. Checked everywhere on the
        // table rather than at a convenient point.
        for (halfOvers in 1..100) {
            val overs = halfOvers * 0.5
            (0..9).map { resources(overs, it) }.zipWithNext { withMore, withFewer ->
                assertTrue(withFewer < withMore) { "at $overs overs, losing a wicket did not cost anything" }
            }
        }
    }

    @Test
    fun `a T20 innings is worth far less than a one-day innings but not proportionally less`() {
        // Twenty overs is 40% of fifty, but a side batting twenty holds all ten
        // wickets for them and scores faster, so it is worth more than 40%.
        val t20 = resources(20.0, 0)
        assertTrue(t20 > 40.0) { "twenty overs came out at only $t20%" }
        assertTrue(t20 < 70.0) { "twenty overs came out at $t20%, which is most of a one-day innings" }
    }

    @Test
    fun `a side nine down loses almost nothing to rain`() {
        // The heart of the method: overs are only worth something to a side
        // with wickets to use them.
        val ninthWicketCost = resources(25.0, 9) - resources(10.0, 9)
        val openingCost = resources(25.0, 0) - resources(10.0, 0)
        assertTrue(ninthWicketCost < openingCost / 5.0) {
            "nine down lost $ninthWicketCost%%, none down lost $openingCost%%"
        }
    }

    @Test
    fun `the table rejects nonsense`() {
        assertThrows<IllegalArgumentException> { resources(-1.0, 0) }
        assertThrows<IllegalArgumentException> { resources(10.0, 11) }
        assertThrows<IllegalArgumentException> { resources(10.0, -1) }
    }

    // ---- interruptions -----------------------------------------------------

    @Test
    fun `an uninterrupted innings has all of its resources`() {
        assertEquals(100.0, DuckworthLewis.resourcesAvailable(50.0, tuning = tuning), 1e-9)
        assertEquals(
            resources(20.0, 0),
            DuckworthLewis.resourcesAvailable(20.0, tuning = tuning),
            1e-9,
        )
    }

    @Test
    fun `rain costs a side the difference between what it had and what it gets back`() {
        // Twenty overs gone, none down, and ten of the remaining thirty washed
        // out. The cost is the gap between thirty overs' resource and twenty's,
        // at nought down - not the raw ten overs.
        val stoppage = Interruption(oversRemainingBefore = 30.0, oversRemainingAfter = 20.0, wicketsLost = 0)
        val available = DuckworthLewis.resourcesAvailable(50.0, listOf(stoppage), tuning)

        assertEquals(100.0 - (resources(30.0, 0) - resources(20.0, 0)), available, 1e-9)
        assertTrue(available < 100.0)
    }

    @Test
    fun `the same stoppage costs a settled side more than a struggling one`() {
        fun cost(wickets: Int) = 100.0 - DuckworthLewis.resourcesAvailable(
            50.0,
            listOf(Interruption(30.0, 20.0, wickets)),
            tuning,
        )
        assertTrue(cost(0) > cost(7)) { "nought down lost ${cost(0)}%, seven down lost ${cost(7)}%" }
    }

    @Test
    fun `an abandoned innings keeps only what it used`() {
        val stoppage = Interruption(oversRemainingBefore = 20.0, oversRemainingAfter = 0.0, wicketsLost = 3)
        val available = DuckworthLewis.resourcesAvailable(50.0, listOf(stoppage), tuning)

        assertEquals(100.0 - resources(20.0, 3), available, 1e-9)
    }

    @Test
    fun `several stoppages accumulate`() {
        val one = Interruption(40.0, 35.0, 1)
        val two = Interruption(20.0, 14.0, 4)
        val both = DuckworthLewis.resourcesAvailable(50.0, listOf(one, two), tuning)
        val first = DuckworthLewis.resourcesAvailable(50.0, listOf(one), tuning)

        assertTrue(both < first)
        assertEquals(first - (resources(20.0, 4) - resources(14.0, 4)), both, 1e-9)
    }

    @Test
    fun `an interruption cannot hand overs back`() {
        assertThrows<IllegalArgumentException> { Interruption(20.0, 25.0, 0) }
    }

    // ---- the target --------------------------------------------------------

    @Test
    fun `an uninterrupted match is settled by the score, not by a table`() {
        assertEquals(251, DuckworthLewis.target(250, 100.0, 100.0, tuning))
    }

    @Test
    fun `a shortened chase is scaled down, and by less than the overs are`() {
        // 250 in 50, chase cut to 25: naive scaling says 126, which hands the
        // match over. The method asks for materially more, because the side
        // chasing still has all ten wickets for those 25 overs.
        val second = resources(25.0, 0)
        val target = DuckworthLewis.target(250, 100.0, second, tuning)

        assertTrue(target > 126) { "a 25-over chase of 250 was set at $target" }
        assertTrue(target < 250) { "a 25-over chase of 250 was set at $target" }
    }

    @Test
    fun `a side given more resource than its opponent is charged at the average rate, not its opponent's`() {
        // Side one was rained on and batted only 30 overs; side two gets its
        // full fifty. Proportional scaling would project side one's rate across
        // an innings it never had to sustain, which cuts both ways:
        //
        //  - a side that flogged it for 30 overs would set an impossible target
        //  - a side that crawled would set a free one
        //
        // The extra resource is charged at the average scoring rate instead, so
        // the correction runs in opposite directions for the two cases. That is
        // the property, not "the target always comes down".
        val first = resources(30.0, 0)
        fun proportional(runs: Int) = (runs * 100.0 / first).toInt() + 1
        fun charged(runs: Int) = DuckworthLewis.target(runs, first, 100.0, tuning)

        val fast = 260 // about 8.7 an over: far above a full-innings average
        val slow = 150 // five an over
        assertTrue(proportional(fast) > tuning.averageFiftyOverTotal) { "pick a faster side-one score" }
        assertTrue(proportional(slow) < tuning.averageFiftyOverTotal) { "pick a slower side-one score" }

        assertTrue(charged(fast) < proportional(fast)) {
            "a side that flogged 30 overs set ${charged(fast)}, proportional wanted ${proportional(fast)}"
        }
        assertTrue(charged(slow) > proportional(slow)) {
            "a side that crawled set ${charged(slow)}, proportional wanted ${proportional(slow)}"
        }
        // In both cases side two is still asked for more than side one made:
        // it has more resource, so it must do more with it.
        assertTrue(charged(fast) > fast && charged(slow) > slow)
    }

    @Test
    fun `the target is always one more than par`() {
        // The off-by-one that decides tied matches.
        listOf(0, 1, 97, 250, 438).forEach { score ->
            val first = 100.0
            val second = 63.4
            assertEquals(
                DuckworthLewis.par(score, first, second, tuning) + 1,
                DuckworthLewis.target(score, first, second, tuning),
            )
        }
    }

    @Test
    fun `par never rounds a run in the chasing side's favour`() {
        // par is the score that ties. Rounding up would give a side a run it
        // has not scored and turn losses into ties.
        for (score in 100..300) {
            val par = DuckworthLewis.par(score, 100.0, 50.0, tuning)
            assertTrue(par <= score * 0.5) { "par $par for $score at half the resources" }
        }
    }

    @Test
    fun `a chase that is never resumed is settled on par at the moment the rain came`() {
        // 250 to win off 50. Side two is 120 for 2 off 25 when it rains for
        // good. Par is what decides it, and it is not half of 250.
        val secondAtStart = 100.0
        val par = DuckworthLewis.parNow(
            firstInningsRuns = 250,
            resourcesFirst = 100.0,
            resourcesSecondAtStart = secondAtStart,
            oversRemaining = 25.0,
            wicketsLost = 2,
            tuning = tuning,
        )
        assertTrue(par in 100..140) { "par after 25 overs for 2 was $par" }
        assertTrue(par < 125) { "par $par should be under half, because wickets in hand are worth overs" }
    }

    @Test
    fun `par rises as a chase goes on`() {
        val pars = (0..40).map {
            val overs = 25.0 - it * 0.5
            DuckworthLewis.parNow(250, 100.0, 100.0, overs, wicketsLost = 2, tuning = tuning)
        }
        pars.zipWithNext { earlier, later -> assertTrue(later >= earlier) }
    }

    @Test
    fun `par rises when a wicket falls`() {
        // Losing a wicket uses resource, so the score needed at that moment to
        // still be ahead goes up. This is what makes a collapse in a rain-hit
        // chase so dangerous.
        fun par(wickets: Int) = DuckworthLewis.parNow(250, 100.0, 100.0, 20.0, wickets, tuning)
        assertTrue(par(5) > par(2)) { "five down par ${par(5)}, two down par ${par(2)}" }
    }

    @Test
    fun `rain does not move a side relative to par`() {
        // The fairness theorem the whole method rests on, and the only claim
        // about a stoppage that holds without qualification. The resource a
        // side has *used* is untouched by rain - a stoppage takes away future
        // overs, not past ones - so par at the moment the players go off is the
        // same number it was a ball earlier. A side ahead of par when the
        // covers come on is still ahead of it when they are on.
        val score = 260
        val used = { overs: Double, wickets: Int, atStart: Double ->
            atStart - resources(overs, wickets)
        }

        for (wickets in 0..8) {
            for (oversLeft in listOf(40.0, 30.0, 20.0, 12.0, 6.0)) {
                for (lost in listOf(2.0, 5.0, 11.0)) {
                    val after = (oversLeft - lost).coerceAtLeast(0.0)
                    val before = DuckworthLewis.resourcesAvailable(50.0, emptyList(), tuning)
                    val afterRain = DuckworthLewis.resourcesAvailable(
                        50.0,
                        listOf(Interruption(oversLeft, after, wickets)),
                        tuning,
                    )
                    assertEquals(
                        DuckworthLewis.par(score, 100.0, used(oversLeft, wickets, before), tuning),
                        DuckworthLewis.par(score, 100.0, used(after, wickets, afterRain), tuning),
                        "$wickets down, $oversLeft overs left, $lost lost",
                    )
                }
            }
        }
    }

    @Test
    fun `whether rain helps a chasing side depends on how far short it is`() {
        // A side needing a lot is asked for more per over after a stoppage; a
        // side almost home is asked for less. Both are correct, and the second
        // is why a captain well ahead of the rate wants the covers on. A first
        // attempt at this asserted the rate always rises, which is only true of
        // sides that are behind.
        val score = 260
        val cutShort = DuckworthLewis.resourcesAvailable(
            50.0,
            listOf(Interruption(20.0, 12.0, wicketsLost = 3)),
            tuning,
        )
        val full = 100.0
        val targetBefore = DuckworthLewis.target(score, 100.0, full, tuning)
        val targetAfter = DuckworthLewis.target(score, 100.0, cutShort, tuning)

        fun rates(runs: Int) =
            (targetBefore - runs).toDouble() / 20 to (targetAfter - runs).toDouble() / 12

        val (struggling, struggledAfter) = rates(90)
        assertTrue(struggledAfter > struggling) {
            "90 for 3 was asked %.2f an over and then %.2f".format(struggling, struggledAfter)
        }

        val (cruising, cruisedAfter) = rates(240)
        assertTrue(cruisedAfter < cruising) {
            "240 for 3 was asked %.2f an over and then %.2f".format(cruising, cruisedAfter)
        }
    }

    @Test
    fun `a side well short of par is asked for more per over when overs go`() {
        // The single most important consequence of pricing wickets. A side 20
        // overs into a chase of 250 that loses 15 of its remaining 30 does not
        // get to keep scoring at the rate it was: it keeps ten wickets' worth
        // of resource for half the overs, so the target falls by less than the
        // overs do, and the required rate goes up.
        val target = 250
        val before = DuckworthLewis.target(target, 100.0, 100.0, tuning)
        val requiredBefore = (before - 90).toDouble() / 30 // 90 for 2 after 20 overs

        val after = DuckworthLewis.target(
            firstInningsRuns = target,
            resourcesFirst = 100.0,
            resourcesSecond = DuckworthLewis.resourcesAvailable(
                oversAtStart = 50.0,
                interruptions = listOf(Interruption(30.0, 15.0, wicketsLost = 2)),
                tuning = tuning,
            ),
            tuning = tuning,
        )
        val requiredAfter = (after - 90).toDouble() / 15

        assertTrue(requiredAfter > requiredBefore) {
            "before %.2f an over, after %.2f".format(requiredBefore, requiredAfter)
        }
        assertTrue(after < before) { "the target must still come down: $before -> $after" }
    }

    // ---- the table itself is well formed ------------------------------------

    @Test
    fun `a table where a wicket helped you is refused`() {
        assertThrows<IllegalArgumentException> {
            DlsTuning(asymptote = List(10) { 100.0 + it })
        }
        assertThrows<IllegalArgumentException> {
            DlsTuning(decay = List(10) { 0.3 - it * 0.01 })
        }
        assertThrows<IllegalArgumentException> { DlsTuning(asymptote = listOf(1.0, 2.0)) }
    }
}
