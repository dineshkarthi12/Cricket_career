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
