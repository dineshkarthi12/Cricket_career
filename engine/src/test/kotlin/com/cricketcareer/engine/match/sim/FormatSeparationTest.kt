package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.fixtures.CalibrationRun
import com.cricketcareer.engine.model.world.MatchFormat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The formats must behave like different games.
 *
 * This is the structural property behind every band in docs/CALIBRATION.md, and
 * it is worth testing separately because the bands can all drift while the
 * *ordering* silently collapses — which is exactly what happened during Phase 3,
 * when a fifty-over innings was no safer than a Twenty20 one.
 *
 * Nothing physical differs between the formats. The same batters, the same
 * pitch, the same bowlers. Only what the players are *trying to do* changes,
 * and everything below has to fall out of that.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormatSeparationTest {

    private lateinit var t20: com.cricketcareer.engine.fixtures.CalibrationStats
    private lateinit var listA: com.cricketcareer.engine.fixtures.CalibrationStats
    private lateinit var multiDay: com.cricketcareer.engine.fixtures.CalibrationStats

    @BeforeAll
    fun simulate() {
        t20 = CalibrationRun.run(MatchFormat.T20, innings = 150)
        listA = CalibrationRun.run(MatchFormat.LIST_A, innings = 120)
        multiDay = CalibrationRun.run(MatchFormat.TEST, innings = 80)
    }

    @Test
    fun `scoring rate falls as the format lengthens`() {
        assertTrue(
            t20.runRate > listA.runRate,
            "Twenty20 (${t20.runRate}) did not out-score fifty overs (${listA.runRate})",
        )
        assertTrue(
            listA.runRate > multiDay.runRate,
            "fifty overs (${listA.runRate}) did not out-score multi-day (${multiDay.runRate})",
        )
        assertTrue(
            t20.runRate > multiDay.runRate * 2.0,
            "Twenty20 should score at more than twice the rate of multi-day cricket",
        )
    }

    @Test
    fun `dot balls rise as the format lengthens`() {
        assertTrue(multiDay.dotPercent > listA.dotPercent, "multi-day had fewer dots than fifty overs")
        assertTrue(listA.dotPercent > t20.dotPercent, "fifty overs had fewer dots than Twenty20")
    }

    @Test
    fun `batters survive longer as the format lengthens`() {
        // The single most important separation. When defence stopped being
        // meaningfully safer than attack, this was the test that would have
        // caught it: every format converged on about twenty balls a wicket.
        assertTrue(
            listA.ballsPerWicket > t20.ballsPerWicket * 1.4,
            "fifty overs (${listA.ballsPerWicket} balls a wicket) was barely safer than " +
                "Twenty20 (${t20.ballsPerWicket})",
        )
        assertTrue(
            multiDay.ballsPerWicket > listA.ballsPerWicket * 1.4,
            "multi-day (${multiDay.ballsPerWicket}) was barely safer than fifty overs (${listA.ballsPerWicket})",
        )
    }

    @Test
    fun `boundaries are a Twenty20 habit`() {
        assertTrue(
            t20.boundaryPercent > listA.boundaryPercent * 1.4,
            "Twenty20 boundary rate (${t20.boundaryPercent}) was not clearly above fifty overs " +
                "(${listA.boundaryPercent})",
        )
        assertTrue(listA.boundaryPercent > multiDay.boundaryPercent, "fifty overs hit fewer boundaries than a Test")
    }

    @Test
    fun `wides are a white-ball problem`() {
        // The wide line is judged far more tightly in limited-overs cricket, so
        // the same bowlers concede materially more of them.
        assertTrue(
            t20.widePercent > multiDay.widePercent * 1.5,
            "Twenty20 wides (${t20.widePercent}) were not clearly above multi-day (${multiDay.widePercent})",
        )
    }

    @Test
    fun `a multi-day innings is long enough to be a multi-day innings`() {
        assertTrue(
            multiDay.meanInningsTotal > 250.0,
            "the mean multi-day innings total was only ${multiDay.meanInningsTotal}",
        )
    }
}
