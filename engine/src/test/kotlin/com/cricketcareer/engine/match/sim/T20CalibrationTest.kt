package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.fixtures.CalibrationRun
import com.cricketcareer.engine.fixtures.CalibrationStats
import com.cricketcareer.engine.match.state.DismissalMode
import com.cricketcareer.engine.model.world.MatchFormat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The acceptance criteria from docs/CALIBRATION.md, as a build gate.
 *
 * Tolerances are stated as a multiple of the **sampling standard error**, never
 * as a hand-picked epsilon (CLAUDE.md §6). That way changing the sample size
 * cannot silently change how strict the suite is: a smaller sample widens the
 * allowance exactly as much as the extra noise warrants, and no more.
 *
 * The sample here is deliberately modest so `./gradlew check` stays usable. The
 * full-size run lives in :sim-harness and its results are logged in
 * docs/CALIBRATION.md.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class T20CalibrationTest {

    private lateinit var stats: CalibrationStats

    @BeforeAll
    fun simulate() {
        stats = CalibrationRun.run(MatchFormat.T20, innings = SAMPLE_INNINGS, firstSeed = 1L)
    }

    @Test
    fun `run rate is Twenty20 scoring`() = assertBand("run rate", stats.runRate, 8.0, 8.8, stats.legalBalls, 0.6)

    @Test
    fun `dot ball share`() =
        assertPercentBand("dot ball %", stats.dotPercent, 30.0, 36.0, stats.totalDeliveries)

    @Test
    fun `boundary share of deliveries`() =
        assertPercentBand("boundary %", stats.boundaryPercent, 17.0, 20.0, stats.totalDeliveries)

    @Test
    fun `wicket frequency`() =
        assertBand("balls per wicket", stats.ballsPerWicket, 16.0, 19.0, stats.wickets, 4.5)

    @Test
    fun `wides are white-ball frequency`() =
        assertPercentBand("wide %", stats.widePercent, 3.0, 5.0, stats.totalDeliveries)

    @Test
    fun `no balls are rare`() =
        assertPercentBand("no ball %", stats.noBallPercent, 0.4, 1.0, stats.totalDeliveries)

    @Test
    fun `byes and leg byes are a small share of runs`() =
        assertPercentBand("byes+leg byes % of runs", stats.byePercentOfRuns, 1.5, 2.5, stats.runs)

    @Test
    fun `catches are held at a believable rate`() =
        assertPercentBand("catch success %", stats.catchSuccessRate * 100.0, 75.0, 80.0, stats.catchChances)

    @Test
    fun `dismissal mix matches real cricket`() {
        val dismissals = DismissalMode.entries.sumOf { stats.dismissalCount(it) }
        assertPercentBand("caught %", stats.dismissalShare(DismissalMode.CAUGHT), 56.0, 62.0, dismissals)
        assertPercentBand("bowled %", stats.dismissalShare(DismissalMode.BOWLED), 18.0, 22.0, dismissals)
        assertPercentBand("lbw %", stats.dismissalShare(DismissalMode.LBW), 12.0, 16.0, dismissals)
        assertPercentBand("run out %", stats.dismissalShare(DismissalMode.RUN_OUT), 4.0, 6.0, dismissals)
        assertPercentBand("stumped %", stats.dismissalShare(DismissalMode.STUMPED), 0.0, 3.0, dismissals)
    }

    @Test
    fun `the dismissal hazard falls as a batter settles`() {
        // The single most diagnostic shape in the engine. A batter must be
        // measurably harder to dismiss once he is in - that is what produces the
        // innings-score distribution, and a flat curve means the settling model
        // has stopped working.
        val curve = stats.hazardCurve().filter { !it.isNaN() }
        assertTrue(curve.size >= 4, "not enough data to measure the hazard curve")
        val firstFive = curve.first()
        val settled = curve.drop(2).average()
        assertTrue(
            firstFive > settled * 1.08,
            "a new batter's hazard ($firstFive per 100 balls) is not meaningfully above a settled one's ($settled)",
        )
    }

    @Test
    fun `individual scores are geometric, not normal`() {
        // Lots of single-figure scores, a fat middle, a long thin tail. A normal
        // distribution here would mean the model has no survival dynamic at all.
        val histogram = stats.scoreHistogram()
        val total = histogram.values.sum().toDouble()
        val singleFigures = histogram.getValue("0-9") / total
        val fatBand = (histogram.getValue("10-19") + histogram.getValue("20-39")) / total
        val tail = (histogram.getValue("70-99") + histogram.getValue("100+")) / total

        assertTrue(singleFigures in 0.35..0.60, "single-figure scores were ${pct(singleFigures)}")
        assertTrue(fatBand in 0.25..0.45, "the 10-39 band was ${pct(fatBand)}")
        assertTrue(tail in 0.01..0.12, "the tail past 70 was ${pct(tail)}")
        assertTrue(singleFigures > fatBand, "more innings must end in single figures than in the 10-39 band")
    }

    @Test
    fun `team totals are Twenty20 totals`() {
        assertTrue(
            stats.meanInningsTotal in 150.0..195.0,
            "mean innings total was ${stats.meanInningsTotal}",
        )
    }

    private fun pct(value: Double) = (kotlin.math.round(value * 1000) / 10).toString() + " per cent" 

    /**
     * Assert a percentage sits inside its band, allowing [SIGMA] standard errors
     * of sampling noise at the edges.
     */
    private fun assertPercentBand(name: String, actual: Double, low: Double, high: Double, n: Int) {
        val standardError = stats.standardErrorOfPercent(actual, n)
        assertTrue(
            actual >= low - SIGMA * standardError && actual <= high + SIGMA * standardError,
            // Built by concatenation, not String.format: several metric names
            // contain a per-cent sign, which format() reads as a conversion.
            name + " was " + round(actual) + ", outside " + low + "-" + high +
                " (standard error " + round(standardError) + " over " + n + " samples)",
        )
    }

    /** As above, for a metric that is not a percentage; [spread] is its standard deviation. */
    private fun assertBand(name: String, actual: Double, low: Double, high: Double, n: Int, spread: Double) {
        val standardError = spread / kotlin.math.sqrt(n.toDouble())
        assertTrue(
            actual >= low - SIGMA * standardError && actual <= high + SIGMA * standardError,
            name + " was " + round(actual) + ", outside " + low + "-" + high +
                " (standard error " + round(standardError) + " over " + n + " samples)",
        )
    }

    private fun round(value: Double): String = (kotlin.math.round(value * 1000) / 1000).toString()

    private companion object {
        /**
         * Sample size for the build gate. Around 35,000 balls: enough that the
         * headline rates are stable to a few tenths of a percentage point, and
         * fast enough that nobody skips `check`.
         */
        const val SAMPLE_INNINGS = 300

        /** Standard errors of slack allowed at a band edge. */
        const val SIGMA = 3.0
    }
}
