package com.cricketcareer.harness

import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.dls.DuckworthLewis
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.sim.InningsSimulator
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.MatchRandom
import kotlin.math.abs
import kotlin.math.exp

/**
 * Fits the Duckworth–Lewis resource table to this engine's own cricket.
 *
 * The table has to be measured rather than borrowed. A table taken from real
 * one-day cricket would settle matches in this game by a scoring rate this
 * game does not have, and every rain-affected result would be quietly wrong in
 * a direction nobody could see. Same rule as `WorldTuning`: the parameters come
 * from engine output, `:sim-harness` does the fitting, and a test in `:engine`
 * fails when the two drift apart.
 *
 *   ./gradlew :sim-harness:run --args="--report=dls --matches=4000 --seed=77"
 *
 * `--matches` is read as the number of fifty-over innings to sample.
 *
 * What is measured, for every ball of every innings: the state before it
 * (overs left, wickets down) and the runs the side went on to add from there.
 * That conditional expectation *is* the resource curve — the model's whole
 * content is that it has the shape `A(w)·(1 − exp(−b(w)·u))`.
 */
object DlsFit {

    /** Overs the table is normalised against, and the innings length sampled. */
    private const val REFERENCE_OVERS = 50

    private const val BALLS_PER_OVER = 6

    /** States with fewer observations than this are too rare to fit through. */
    private const val MINIMUM_OBSERVATIONS = 40

    fun run(args: HarnessArgs) {
        val format = MatchFormat.LIST_A
        val samples = collect(format, args.matches, args.seed)

        println("DLS resource fit - ${args.matches} fifty-over innings, seed ${args.seed}")
        println()

        val asymptote = DoubleArray(DuckworthLewis.ALL_OUT)
        val decay = DoubleArray(DuckworthLewis.ALL_OUT)

        println("  w   points      A       b   initial rate   fit error")
        println("  " + "-".repeat(52))

        // Fitted from nine wickets down upwards, each state constrained by the
        // one below it. The constraints are cricket facts the data cannot
        // settle on its own: a side is only ever none down early in an innings,
        // so its curve is observed over a narrow window of overs and A trades
        // off against b almost freely inside it. Left unconstrained the fit put
        // a higher asymptote on one wicket down than on none - a table in which
        // losing your opener helps.
        var floorA = 0.0
        var ceilingB = Double.MAX_VALUE
        var floorRate = 0.0
        val lines = arrayOfNulls<String>(DuckworthLewis.ALL_OUT)

        for (wickets in DuckworthLewis.ALL_OUT - 1 downTo 0) {
            val usable = samples.curveFor(wickets).filter { it.observations >= MINIMUM_OBSERVATIONS }
            if (usable.size < 3) {
                lines[wickets] = "  $wickets   too few observations to fit"
                continue
            }
            val (rawA, rawB, error) = fit(usable, floorA, ceilingB, floorRate)

            // Rounded to the precision the table is *printed* at, and the next
            // state is constrained against the rounded value. The first version
            // of this checked the full-precision fit and then printed a table
            // rounded to four decimal places; a 0.3% rounding shift in `b` was
            // enough to turn a legal fit into a table `DlsTuning` refused, and
            // the report cheerfully said "safe to paste".
            val a = round(rawA, ASYMPTOTE_PLACES)
            val b = round(rawB, DECAY_PLACES)
            asymptote[wickets] = a
            decay[wickets] = b
            floorA = a * (1.0 + MARGIN)
            ceilingB = b * (1.0 - MARGIN)
            floorRate = a * b * (1.0 + MARGIN)
            lines[wickets] = "  %d %8d %7.1f %8.5f %10.2f %11.2f".format(
                wickets, usable.sumOf { it.observations }, a, b, a * b, error,
            )
        }
        lines.forEach { println(it) }

        val totals = samples.completedTotals
        val meanTotal = if (totals.isEmpty()) 0.0 else totals.average()

        println()
        println("Paste into DlsTuning:")
        println()
        println("    asymptote = listOf(")
        println("        " + asymptote.joinToString(", ") { "%.1f".format(it) } + ",")
        println("    ),")
        println("    decay = listOf(")
        println("        " + decay.joinToString(", ") { "%.5f".format(it) } + ",")
        println("    ),")
        println("    averageFiftyOverTotal = %.1f,".format(meanTotal))
        println()

        report(asymptote, decay)
        println()
        resourceTable(asymptote, decay)
    }

    /**
     * Whether the fitted table is a legal one.
     *
     * Three monotonicities, all of which a real cricket follower would notice
     * being broken. They are checked here rather than only in `DlsTuning`'s
     * constructor so that a bad fit is reported as a bad fit, with the numbers
     * visible, instead of throwing on the next run.
     */
    private fun report(asymptote: DoubleArray, decay: DoubleArray) {
        val problems = mutableListOf<String>()
        asymptote.toList().zipWithNext().forEachIndexed { w, (a, b) ->
            if (b >= a) problems += "asymptote rose from $w to ${w + 1} ($a -> $b)"
        }
        decay.toList().zipWithNext().forEachIndexed { w, (a, b) ->
            if (b <= a) problems += "decay fell from $w to ${w + 1} ($a -> $b)"
        }
        val rates = asymptote.indices.map { asymptote[it] * decay[it] }
        rates.zipWithNext().forEachIndexed { w, (a, b) ->
            if (b > a) problems += "a side ${w + 1} down scored faster off the first ball than one $w down"
        }

        if (problems.isEmpty()) {
            println("Table is monotone in all three directions. Safe to paste.")
        } else {
            println("PROBLEMS - do not paste this table:")
            problems.forEach { println("  - $it") }
        }
    }

    /**
     * The fitted table as a reader of cricket would want to see it: resources
     * remaining, as a percentage, by overs left and wickets down.
     */
    private fun resourceTable(asymptote: DoubleArray, decay: DoubleArray) {
        val full = asymptote[0] * (1.0 - exp(-decay[0] * REFERENCE_OVERS))
        println("Resources remaining (%), overs left down the side, wickets across:")
        println("   overs " + (0..9).joinToString("") { "%6d".format(it) })
        listOf(50, 40, 30, 25, 20, 15, 10, 5, 2).forEach { overs ->
            val row = (0..9).joinToString("") { w ->
                "%6.1f".format(100.0 * asymptote[w] * (1.0 - exp(-decay[w] * overs)) / full)
            }
            println("   %5d %s".format(overs, row))
        }
    }

    /** Mean further runs from one (overs left, wickets down) state. */
    private data class Point(val oversRemaining: Double, val meanFurtherRuns: Double, val observations: Int)

    private class Samples {
        /** Summed further runs and count, indexed by wickets then by legal balls remaining. */
        private val runs = Array(DuckworthLewis.ALL_OUT) { DoubleArray(REFERENCE_OVERS * BALLS_PER_OVER + 1) }
        private val counts = Array(DuckworthLewis.ALL_OUT) { IntArray(REFERENCE_OVERS * BALLS_PER_OVER + 1) }
        val completedTotals = mutableListOf<Int>()

        fun add(ballsRemaining: Int, wickets: Int, furtherRuns: Int) {
            if (wickets !in 0 until DuckworthLewis.ALL_OUT) return
            if (ballsRemaining !in runs[wickets].indices) return
            runs[wickets][ballsRemaining] += furtherRuns
            counts[wickets][ballsRemaining]++
        }

        /**
         * The measured curve for one wicket count, one point per ball
         * remaining.
         *
         * Not bucketed into whole overs. Bucketing labelled the whole first
         * over "fifty overs remaining", so the point the table normalises
         * against was the mean of six states rather than the start of an
         * innings, and every resource in the table came out about half an
         * over's scoring light.
         */
        fun curveFor(wickets: Int): List<Point> = runs[wickets].indices.mapNotNull { balls ->
            val n = counts[wickets][balls]
            if (n == 0 || balls == 0) null else {
                Point(balls.toDouble() / BALLS_PER_OVER, runs[wickets][balls] / n, n)
            }
        }
    }

    private fun collect(format: MatchFormat, innings: Int, firstSeed: Long): Samples {
        val samples = Samples()
        val (batting, bowling) = Fixtures.averageTeams()
        val totalBalls = REFERENCE_OVERS * BALLS_PER_OVER

        repeat(innings) { i ->
            val events = mutableListOf<BallEvent>()
            InningsSimulator(
                format = format,
                battingSide = batting,
                bowlingSide = bowling,
                venue = Fixtures.AVERAGE_VENUE,
                pitch = Fixtures.AVERAGE_PITCH,
                weather = Weather.AVERAGE,
                level = LadderLevel.STATE_FIRST_CLASS,
                random = MatchRandom(firstSeed + i),
                tuning = FITTING_TUNING,
                sink = BallEventSink { events += it },
            ).simulate()

            val finalScore = events.lastOrNull()?.scoreAfter ?: 0
            samples.completedTotals += finalScore

            // Walk the innings and record the state *before* each legal
            // delivery. `legalBallIndex` is the count of legal balls already
            // bowled, so the first ball of an innings genuinely reads three
            // hundred remaining. Wides and no-balls share the index of the ball
            // they precede and would record the same state twice.
            var scoreBefore = 0
            var wicketsBefore = 0
            events.forEach { event ->
                if (event.outcome.isLegalBall) {
                    samples.add(totalBalls - event.id.legalBallIndex, wicketsBefore, finalScore - scoreBefore)
                }
                scoreBefore = event.scoreAfter
                wicketsBefore = event.wicketsAfter
            }
        }
        return samples
    }

    /**
     * Least squares fit of `A(1 − exp(−b·u))` to a measured curve.
     *
     * Two nested coordinate searches rather than a gradient method: there are
     * two parameters and a few dozen points, the surface is smooth, and a
     * search that cannot fail to terminate is worth more here than a fast one.
     * Points are weighted by how many observations stand behind them, so a
     * state seen forty times does not outvote one seen forty thousand.
     */
    private fun fit(
        points: List<Point>,
        floorA: Double,
        ceilingB: Double,
        floorRate: Double,
    ): Triple<Double, Double, Double> {
        fun legal(a: Double, b: Double) = a > 0.0 && b > 1e-5 && a >= floorA && b <= ceilingB && a * b >= floorRate

        // The free fit first. Most wicket states are well enough observed to
        // land somewhere legal on their own, and constraining a state that did
        // not need it costs real accuracy - the first attempt at this started
        // every search on the constraint boundary and left seven wickets down
        // with twice the residual of its neighbours for no reason.
        val free = search(points, ::legal, startA = points.maxOf { it.meanFurtherRuns }.coerceAtLeast(1.0), startB = 0.05, constrained = false)
        if (legal(free.first, free.second)) return free

        // Otherwise start from the free answer, pushed to the nearest legal
        // point, and search inside the feasible region from there.
        var startA = maxOf(free.first, floorA)
        val startB = minOf(free.second, ceilingB)
        if (startA * startB < floorRate) startA = floorRate / startB
        return search(points, ::legal, startA, startB, constrained = true)
    }

    private fun search(
        points: List<Point>,
        legal: (Double, Double) -> Boolean,
        startA: Double,
        startB: Double,
        constrained: Boolean,
    ): Triple<Double, Double, Double> {
        fun error(a: Double, b: Double): Double = points.sumOf { point ->
            val predicted = a * (1.0 - exp(-b * point.oversRemaining))
            val residual = predicted - point.meanFurtherRuns
            point.observations * residual * residual
        } / points.sumOf { it.observations }

        fun allowed(a: Double, b: Double) = a > 0.0 && b > 1e-5 && (!constrained || legal(a, b))

        var bestA = startA
        var bestB = startB
        var best = error(bestA, bestB)

        var stepA = bestA / 2.0
        var stepB = maxOf(bestB / 2.0, 1e-4)
        repeat(SEARCH_ROUNDS) {
            var improved = false
            for (da in listOf(-stepA, 0.0, stepA)) {
                for (db in listOf(-stepB, 0.0, stepB)) {
                    val a = bestA + da
                    val b = bestB + db
                    if (!allowed(a, b)) continue
                    val e = error(a, b)
                    if (e < best - 1e-12) {
                        best = e
                        bestA = a
                        bestB = b
                        improved = true
                    }
                }
            }
            if (!improved) {
                stepA /= 2.0
                stepB /= 2.0
            }
        }
        return Triple(bestA, bestB, kotlin.math.sqrt(abs(best)))
    }

    /**
     * How far apart consecutive wicket states must be kept.
     *
     * Small: the point is to make the inequalities strict, not to force a gap
     * the data does not support. `DlsTuning` refuses a table where two states
     * are equal, because equality means a wicket was free.
     */
    /**
     * How far apart consecutive wicket states are kept.
     *
     * Only has to beat the rounding: `b` at five decimal places carries at most
     * 0.02% error and `A` at one about 0.01%, so 0.2% is a comfortable margin
     * and no more. Wider is not safer — it is a gap forced into the table that
     * the data did not ask for, and it showed up immediately as the fit error
     * doubling on every state the constraint touched.
     */
    private const val MARGIN = 0.002

    private const val ASYMPTOTE_PLACES = 1
    private const val DECAY_PLACES = 5

    private fun round(value: Double, places: Int): Double {
        val scale = Math.pow(10.0, places.toDouble())
        return kotlin.math.round(value * scale) / scale
    }

    /** Enough halvings to take the step from half an innings to under a run. */
    private const val SEARCH_ROUNDS = 400

    /**
     * The engine settings the sample is generated under.
     *
     * Everything is the default **except** the resource table, which is given
     * an explicit legal placeholder. `DlsTuning` refuses a table in which a
     * wicket helps you, so a bad table in the defaults throws on construction —
     * and `EngineTuning.DEFAULT` builds one. That made the tool for fixing a
     * broken table impossible to run while the table was broken, which is the
     * one moment it is needed. Nothing here reads the placeholder: the sample is
     * ball-by-ball cricket, and the table is what comes out.
     */
    private val FITTING_TUNING = EngineTuning(
        dls = com.cricketcareer.engine.config.DlsTuning(
            asymptote = listOf(300.0, 280.0, 255.0, 225.0, 190.0, 152.0, 114.0, 78.0, 46.0, 20.0),
            decay = listOf(0.030, 0.031, 0.033, 0.036, 0.040, 0.046, 0.056, 0.072, 0.100, 0.160),
            averageFiftyOverTotal = 250.0,
        ),
    )
}
