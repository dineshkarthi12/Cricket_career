package com.cricketcareer.engine.fixtures

import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.match.delivery.ContactPoint
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.sim.InningsSimulator
import com.cricketcareer.engine.match.state.DismissalMode
import com.cricketcareer.engine.match.state.InningsState
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.rng.MatchRandom
import kotlin.math.sqrt

/**
 * Everything docs/CALIBRATION.md measures, accumulated over a sample of innings.
 *
 * Lives in testFixtures so that the engine's calibration tests and
 * :sim-harness's reports compute the *same* numbers from the same definition of
 * "average" — a divergence there would make the calibration log fiction.
 */
class CalibrationStats {
    var innings: Int = 0; private set
    var legalBalls: Int = 0; private set
    var totalDeliveries: Int = 0; private set
    var runs: Int = 0; private set
    var dots: Int = 0; private set
    var fours: Int = 0; private set
    var sixes: Int = 0; private set
    var wickets: Int = 0; private set
    var wides: Int = 0; private set
    var noBalls: Int = 0; private set
    var byes: Int = 0; private set
    var legByes: Int = 0; private set

    var catchChances: Int = 0; private set
    var catchesHeld: Int = 0; private set
    var edges: Int = 0; private set
    var beaten: Int = 0; private set

    private val dismissalCounts = LinkedHashMap<DismissalMode, Int>()
    private val contactPoints = LinkedHashMap<ContactPoint, Int>()
    private val shots = LinkedHashMap<String, Int>()
    private var aerialBalls: Int = 0
    private var qualitySum: Double = 0.0
    private var qualityCount: Int = 0
    private var fielderFound: Int = 0
    private var noFielder: Int = 0
    private val runsOffBat = IntArray(8)
    private var exitSpeedSum: Double = 0.0
    private var exitSpeedCount: Int = 0
    private var carrySum: Double = 0.0
    private val inningsTotals = mutableListOf<Int>()
    private val individualScores = mutableListOf<Int>()

    /** Dismissals and balls faced, bucketed by how settled the batter was. */
    private val hazardDismissals = IntArray(HAZARD_BUCKETS)
    private val hazardBalls = IntArray(HAZARD_BUCKETS)

    fun record(state: InningsState, events: List<BallEvent>) {
        innings++
        legalBalls += state.legalBalls
        runs += state.runs
        wickets += state.wickets
        wides += state.wides
        noBalls += state.noBalls
        byes += state.byes
        legByes += state.legByes
        inningsTotals += state.runs

        val card = state.snapshot()
        card.batting.filter { it.balls > 0 }.forEach { individualScores += it.runs }
        card.fallOfWickets.forEach { }

        totalDeliveries += events.size
        events.forEach { event ->
            val outcome = event.outcome
            if (outcome.totalRuns == 0) dots++
            if (outcome.runsOffBat == 4) fours++
            if (outcome.runsOffBat == 6) sixes++
            outcome.dismissal?.let { dismissalCounts.merge(it.mode, 1, Int::plus) }

            if (event.contact.point.isEdge) edges++
            if (event.contact.point == ContactPoint.MISSED) beaten++
            contactPoints.merge(event.contact.point, 1, Int::plus)
            shots.merge(event.shot.shot.name, 1, Int::plus)
            if (event.contact.point.hitTheBat) {
                qualitySum += event.contact.quality
                qualityCount++
            }
            if (outcome.runsOffBat in 0..7) runsOffBat[outcome.runsOffBat]++
            event.trajectory?.let {
                if (it.isAerial) aerialBalls++
                exitSpeedSum += it.exitSpeedMetresPerSecond
                carrySum += it.carryMetres
                exitSpeedCount++
            }
            event.fielding?.let { if (it.nearestFielder != null) fielderFound++ else noFielder++ }
            event.fielding?.let { fielding ->
                if (fielding.wasChance) {
                    catchChances++
                    if (fielding.caught) catchesHeld++
                }
            }
        }

        // Survival hazard by balls faced, which is the shape test that matters:
        // it must FALL as a batter settles.
        val facedByBatter = LinkedHashMap<String, Int>()
        events.forEach { event ->
            if (!event.outcome.isBallFaced) return@forEach
            val key = event.striker.value
            val faced = facedByBatter.getOrDefault(key, 0)
            val bucket = (faced / HAZARD_BUCKET_SIZE).coerceAtMost(HAZARD_BUCKETS - 1)
            hazardBalls[bucket]++
            val dismissal = event.outcome.dismissal
            if (dismissal != null && dismissal.batterOut.value == key) hazardDismissals[bucket]++
            facedByBatter[key] = faced + 1
        }
    }

    val runRate: Double get() = if (legalBalls == 0) 0.0 else runs * 6.0 / legalBalls
    val dotPercent: Double get() = percent(dots, totalDeliveries)
    val boundaryPercent: Double get() = percent(fours + sixes, totalDeliveries)
    val ballsPerWicket: Double get() = if (wickets == 0) Double.NaN else legalBalls.toDouble() / wickets
    val widePercent: Double get() = percent(wides, totalDeliveries)
    val noBallPercent: Double get() = percent(noBalls, totalDeliveries)
    val byePercentOfRuns: Double get() = percent(byes + legByes, runs)
    val catchSuccessRate: Double get() = if (catchChances == 0) Double.NaN else catchesHeld.toDouble() / catchChances
    val dropRate: Double get() = if (catchChances == 0) Double.NaN else 1.0 - catchSuccessRate
    val meanInningsTotal: Double get() = if (inningsTotals.isEmpty()) 0.0 else inningsTotals.average()

    fun dismissalShare(mode: DismissalMode): Double =
        percent(dismissalCounts.getOrDefault(mode, 0), dismissalCounts.values.sum())

    fun dismissalCount(mode: DismissalMode): Int = dismissalCounts.getOrDefault(mode, 0)

    /** Dismissals per 100 balls faced, by how many balls the batter had faced. */
    fun hazardCurve(): List<Double> = (0 until HAZARD_BUCKETS).map { bucket ->
        if (hazardBalls[bucket] == 0) Double.NaN else hazardDismissals[bucket] * 100.0 / hazardBalls[bucket]
    }

    /** Share of deliveries by where the ball hit the bat, or did not. */
    fun contactPointShares(): Map<ContactPoint, Double> =
        contactPoints.entries.sortedByDescending { it.value }
            .associate { it.key to it.value * 100.0 / totalDeliveries.coerceAtLeast(1) }

    /** Share of deliveries by the stroke the batter chose. */
    fun shotShares(): Map<String, Double> =
        shots.entries.sortedByDescending { it.value }
            .associate { it.key to it.value * 100.0 / totalDeliveries.coerceAtLeast(1) }

    /** How often each number of runs came off the bat, as a share of deliveries. */
    fun runsOffBatShares(): Map<Int, Double> =
        (0..7).filter { runsOffBat[it] > 0 }
            .associateWith { runsOffBat[it] * 100.0 / totalDeliveries.coerceAtLeast(1) }

    val meanExitSpeed: Double get() = if (exitSpeedCount == 0) Double.NaN else exitSpeedSum / exitSpeedCount
    val meanCarry: Double get() = if (exitSpeedCount == 0) Double.NaN else carrySum / exitSpeedCount

    val meanContactQuality: Double get() = if (qualityCount == 0) Double.NaN else qualitySum / qualityCount
    val aerialPercentOfHits: Double get() = percent(aerialBalls, qualityCount)
    val fielderReachedPercent: Double get() = percent(fielderFound, fielderFound + noFielder)

    fun scoreHistogram(): Map<String, Int> {
        val buckets = linkedMapOf("0-9" to 0, "10-19" to 0, "20-39" to 0, "40-69" to 0, "70-99" to 0, "100+" to 0)
        individualScores.forEach { score ->
            val key = when {
                score < 10 -> "0-9"
                score < 20 -> "10-19"
                score < 40 -> "20-39"
                score < 70 -> "40-69"
                score < 100 -> "70-99"
                else -> "100+"
            }
            buckets[key] = buckets.getValue(key) + 1
        }
        return buckets
    }

    /**
     * Standard error of a percentage measured over [n] trials, in percentage
     * points. Tolerances are stated as multiples of this rather than as a
     * hand-picked epsilon, so changing the sample size cannot silently change
     * how strict a test is (CLAUDE.md §6).
     */
    fun standardErrorOfPercent(percent: Double, n: Int): Double =
        if (n == 0) Double.NaN else sqrt(percent * (100.0 - percent) / n)

    private fun percent(part: Int, whole: Int): Double =
        if (whole == 0) 0.0 else part * 100.0 / whole

    companion object {
        const val HAZARD_BUCKET_SIZE: Int = 5
        const val HAZARD_BUCKETS: Int = 8
    }
}

/** Runs innings of average players on an average pitch and accumulates the stats. */
object CalibrationRun {

    /**
     * Multi-day innings are bounded by the match clock, not by an over limit.
     *
     * A Test innings that ran until all out with no ceiling would happily go
     * past 200 overs when nobody could get anybody out, which is not a Test
     * innings — it is a bug in the shape of one. 130 overs is a long first
     * innings and a sane cap for a single-innings calibration sample.
     */
    private const val MULTI_DAY_INNINGS_OVER_CAP = 130

    fun run(
        format: MatchFormat,
        innings: Int,
        firstSeed: Long = 1L,
        tuning: EngineTuning = EngineTuning.DEFAULT,
        pitch: Pitch = Fixtures.AVERAGE_PITCH,
        weather: Weather = Weather.AVERAGE,
    ): CalibrationStats = CalibrationStats().also {
        runInto(it, format, innings, firstSeed, tuning, pitch, weather)
    }

    /** Accumulate into an existing collector, so a sample can span formats. */
    fun runInto(
        stats: CalibrationStats,
        format: MatchFormat,
        innings: Int,
        firstSeed: Long = 1L,
        tuning: EngineTuning = EngineTuning.DEFAULT,
        pitch: Pitch = Fixtures.AVERAGE_PITCH,
        weather: Weather = Weather.AVERAGE,
    ) {
        val (batting, bowling) = Fixtures.averageTeams()
        repeat(innings) { i ->
            val events = mutableListOf<BallEvent>()
            val state = InningsSimulator(
                format = format,
                battingSide = batting,
                bowlingSide = bowling,
                venue = Fixtures.AVERAGE_VENUE,
                pitch = pitch,
                weather = weather,
                level = LadderLevel.STATE_FIRST_CLASS,
                random = MatchRandom(firstSeed + i),
                tuning = tuning,
                sink = BallEventSink { events += it },
            ).simulate(overLimit = if (format.isMultiDay) MULTI_DAY_INNINGS_OVER_CAP else null)
            stats.record(state, events)
        }
    }
}
