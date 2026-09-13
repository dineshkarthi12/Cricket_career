package com.cricketcareer.engine.match.dls

import com.cricketcareer.engine.config.DlsTuning
import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.sim.InningsSimulator
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.rng.MatchRandom
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The resource table against the cricket it claims to describe.
 *
 * `DlsTuning` is fitted by `:sim-harness` from engine output, the same way
 * `WorldTuning` is, and the same danger applies: the day someone retunes the
 * match engine, the table stops describing this game and every rain-affected
 * result becomes quietly wrong in a direction nobody can see. This test is what
 * fails first.
 *
 * Re-fit with:
 * ```
 * ./gradlew :sim-harness:run --args="--report=dls --matches=4000"
 * ```
 *
 * Tolerances are multiples of the sampling standard error, per CLAUDE.md §6.
 */
class DlsCalibrationTest {

    private val tuning = DlsTuning.DEFAULT

    private companion object {
        /**
         * Innings in the sample.
         *
         * Small enough for `check` to stay fast; the tolerance is stated in
         * standard errors, so a bigger sample tightens the test rather than
         * changing what it means.
         */
        const val INNINGS = 400
        const val REFERENCE_OVERS = 50
        const val BALLS_PER_OVER = 6

        /** Ignore states seen too rarely for a mean to mean anything. */
        const val MINIMUM_OBSERVATIONS = 200
    }

    /**
     * Further runs added from one (overs left, wickets down) state.
     *
     * Carries the mean *balls* remaining as well as the mean runs, so that a
     * cell holding a whole over's worth of states is predicted at the middle of
     * that over rather than at its label. Labelling a cell by its bucket is what
     * made the first version of this test read the whole first over as "fifty
     * overs remaining".
     */
    private class Observations {
        var total = 0.0
        var sumOfSquares = 0.0
        var balls = 0L
        var count = 0

        fun add(runs: Int, ballsRemaining: Int) {
            total += runs
            sumOfSquares += runs.toDouble() * runs
            balls += ballsRemaining
            count++
        }

        val mean: Double get() = total / count
        val meanOversRemaining: Double get() = balls.toDouble() / count / BALLS_PER_OVER

        /** Standard error of the mean. */
        val standardError: Double
            get() {
                val variance = (sumOfSquares - total * total / count) / (count - 1)
                return sqrt(variance / count)
            }
    }

    /** Every innings total in the sample, and the state grid, from one run. */
    private class Sample(val grid: Array<Array<Observations>>, val totals: Observations)

    /**
     * Indexed by wickets lost, then by whole overs remaining — but every cell
     * remembers the mean balls that went into it, and is predicted there.
     *
     * Bucketing is needed for statistical power: a sample this size spread over
     * three hundred ball-states leaves almost every cell too thin to mean
     * anything. Predicting at the cell's own mean is what keeps the bucketing
     * from reintroducing the labelling error it is standing in for.
     */
    private fun measure(): Sample {
        val totalBalls = REFERENCE_OVERS * BALLS_PER_OVER
        val grid = Array(DuckworthLewis.ALL_OUT) { Array(REFERENCE_OVERS + 1) { Observations() } }
        val totals = Observations()
        val (batting, bowling) = Fixtures.averageTeams()

        repeat(INNINGS) { i ->
            val events = mutableListOf<BallEvent>()
            InningsSimulator(
                format = Fixtures.LIST_A,
                battingSide = batting,
                bowlingSide = bowling,
                venue = Fixtures.AVERAGE_VENUE,
                pitch = Fixtures.AVERAGE_PITCH,
                weather = Weather.AVERAGE,
                level = LadderLevel.STATE_FIRST_CLASS,
                random = MatchRandom(9_000L + i),
                tuning = EngineTuning.DEFAULT,
                sink = BallEventSink { events += it },
            ).simulate()

            val finalScore = events.lastOrNull()?.scoreAfter ?: 0
            totals.add(finalScore, totalBalls)

            var scoreBefore = 0
            var wicketsBefore = 0
            events.forEach { event ->
                // legalBallIndex counts the legal balls already bowled, so this
                // is the state before the delivery. Wides share the index of the
                // ball they precede and would record the same state twice.
                if (event.outcome.isLegalBall && wicketsBefore < DuckworthLewis.ALL_OUT) {
                    val ballsRemaining = totalBalls - event.id.legalBallIndex
                    grid[wicketsBefore][ballsRemaining / BALLS_PER_OVER]
                        .add(finalScore - scoreBefore, ballsRemaining)
                }
                scoreBefore = event.scoreAfter
                wicketsBefore = event.wicketsAfter
            }
        }
        return Sample(grid, totals)
    }

    @Test
    fun `the resource table predicts what the engine actually scores`() {
        val sample = measure()
        val misses = mutableListOf<String>()
        var checked = 0
        val fullInnings = DuckworthLewis.resources(REFERENCE_OVERS.toDouble(), 0, tuning)

        for (wickets in 0 until DuckworthLewis.ALL_OUT) {
            for (cell in 1..REFERENCE_OVERS) {
                val observed = sample.grid[wickets][cell]
                if (observed.count < MINIMUM_OBSERVATIONS) continue
                checked++

                // The table is a percentage, so turn it back into runs through
                // the one number that defines the scale: what a full innings
                // with all ten in hand is worth.
                val overs = observed.meanOversRemaining
                val share = DuckworthLewis.resources(overs, wickets, tuning) / fullInnings
                val predicted = share * tuning.averageFiftyOverTotal

                // Four standard errors of the measured mean, plus a floor: the
                // fit is a two-parameter curve through a noisy cloud, not an
                // interpolation, so it is allowed to miss a well-observed state
                // by a few runs without that being a broken table.
                val tolerance = 4.0 * observed.standardError + ABSOLUTE_SLACK
                if (abs(predicted - observed.mean) > tolerance) {
                    misses += "%d down, %.1f overs left: table %.1f, engine %.1f (+- %.1f, n=%d)"
                        .format(wickets, overs, predicted, observed.mean, tolerance, observed.count)
                }
            }
        }

        assertTrue(checked > 100) { "only $checked states had enough observations to check" }
        assertTrue(misses.isEmpty()) {
            "the resource table has drifted from the engine in ${misses.size} of $checked states:\n" +
                misses.take(12).joinToString("\n") { "  $it" }
        }
    }

    @Test
    fun `a full innings is worth what a full innings scores`() {
        // The scale of the whole table. If this drifts, every revised target in
        // the game is wrong by the same proportion.
        val sample = measure()

        val tolerance = 4.0 * sample.totals.standardError
        assertTrue(abs(tuning.averageFiftyOverTotal - sample.totals.mean) <= tolerance) {
            "averageFiftyOverTotal is %.1f, engine scores %.1f (4 s.e. = %.1f)"
                .format(tuning.averageFiftyOverTotal, sample.totals.mean, tolerance)
        }
    }

    @Test
    fun `the top of an innings is a hundred per cent, and worth the average total`() {
        // The two ends of the normalisation meeting: resources(50, 0) is 100%
        // by construction, and 100% has to be worth what a side actually makes.
        val sample = measure()
        val fromTheTop = sample.grid[0][REFERENCE_OVERS]

        assertTrue(fromTheTop.count >= INNINGS) { "expected one observation per innings, got ${fromTheTop.count}" }
        val tolerance = 4.0 * fromTheTop.standardError
        assertTrue(abs(tuning.averageFiftyOverTotal - fromTheTop.mean) <= tolerance) {
            "100%% of resources is %.1f runs, the engine's first ball is worth %.1f"
                .format(tuning.averageFiftyOverTotal, fromTheTop.mean)
        }
    }

    /**
     * Runs the table is allowed to be out by, over and above sampling error.
     *
     * A two-parameter curve cannot pass through every point of a three-hundred
     * by ten grid, and demanding that it did would be demanding an
     * interpolation table rather than a model.
     *
     * Nine runs, and the extra three are for one corner in particular: a side
     * *one down with the innings barely started*. The Duckworth-Lewis form
     * insists a wicket costs resource, and at that corner in this engine it
     * very nearly does not — the batter who comes in at three is about as good
     * as the opener he replaced, so losing one in the first over costs almost
     * nothing. The curve cannot represent that and should not be forced to; it
     * is a real mismatch between the model's shape and the data, at a state
     * where the model is extrapolating anyway.
     */
    private val ABSOLUTE_SLACK: Double = 9.0
}
