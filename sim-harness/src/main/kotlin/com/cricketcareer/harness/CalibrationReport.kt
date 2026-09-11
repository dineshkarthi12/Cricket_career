package com.cricketcareer.harness

import com.cricketcareer.engine.fixtures.CalibrationRun
import com.cricketcareer.engine.fixtures.CalibrationStats
import com.cricketcareer.engine.match.state.DismissalMode
import com.cricketcareer.engine.model.world.MatchFormat

/**
 * The report docs/CALIBRATION.md is written from.
 *
 * Every line shows the measured figure, the target band, and whether it is
 * inside — so a run either passes or names exactly what is wrong.
 */
object CalibrationReport {

    private data class Band(val label: String, val low: Double, val high: Double, val actual: Double) {
        val inside: Boolean get() = actual in low..high
        val mark: String get() = if (inside) "ok " else "OUT"
    }

    fun run(args: HarnessArgs) {
        val format = formatFor(args.format)
        val started = System.nanoTime() // determinism-ok: timing the harness, not the simulation
        val stats = CalibrationRun.run(format, args.matches, args.seed)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000 // determinism-ok: harness timing

        println("Calibration - ${format.displayName}, ${args.matches} innings from seed ${args.seed}")
        println("${stats.legalBalls} legal balls simulated in ${elapsedMillis} ms")
        println()

        val bands = bandsFor(format, stats)
        println("%-30s %9s   %-16s %s".format("Metric", "Measured", "Target", ""))
        println("-".repeat(66))
        bands.forEach { band ->
            println(
                "%-30s %9.2f   %-16s %s".format(
                    band.label, band.actual, "%.1f - %.1f".format(band.low, band.high), band.mark,
                ),
            )
        }

        println()
        println("Dismissal mix")
        println("-".repeat(66))
        DismissalMode.entries.forEach { mode ->
            val count = stats.dismissalCount(mode)
            if (count > 0) {
                println("%-30s %9.2f%%  %d".format(mode.name, stats.dismissalShare(mode), count))
            }
        }

        println()
        println("Contact - where the ball met the bat, % of deliveries")
        println("-".repeat(66))
        stats.contactPointShares().forEach { (point, share) -> println("%-30s %6.2f%%".format(point.name, share)) }
        println("mean contact quality when he hit it: %.3f".format(stats.meanContactQuality))
        println("hits that went in the air: %.1f%%".format(stats.aerialPercentOfHits))
        println("hit balls a fielder got near: %.1f%%".format(stats.fielderReachedPercent))
        println("mean exit speed off the bat: %.1f m/s (%.0f km/h)".format(stats.meanExitSpeed, stats.meanExitSpeed * 3.6))
        println("mean carry of a struck ball: %.1f m".format(stats.meanCarry))
        println()
        println("Runs off the bat, % of deliveries")
        stats.runsOffBatShares().forEach { (runs, share) -> println("  %d runs %6.2f%%".format(runs, share)) }

        println()
        println("Shot selection, % of deliveries")
        println("-".repeat(66))
        stats.shotShares().entries.take(10).forEach { (shot, share) -> println("%-30s %6.2f%%".format(shot, share)) }

        println()
        println("Survival hazard - dismissals per 100 balls faced, by balls already faced")
        println("It must FALL as a batter settles; a flat curve means the model is broken.")
        println("-".repeat(66))
        stats.hazardCurve().forEachIndexed { bucket, hazard ->
            val from = bucket * CalibrationStats.HAZARD_BUCKET_SIZE
            val label = if (bucket == CalibrationStats.HAZARD_BUCKETS - 1) "$from+" else "$from-${from + 4}"
            println("%-10s %6.2f".format(label, hazard))
        }

        println()
        println("Individual innings scores")
        println("-".repeat(66))
        val histogram = stats.scoreHistogram()
        val total = histogram.values.sum().coerceAtLeast(1)
        histogram.forEach { (bucket, count) ->
            val share = count * 100.0 / total
            println("%-10s %6.1f%%  %s".format(bucket, share, "#".repeat((share / 2).toInt())))
        }

        println()
        println("Mean innings total: %.1f".format(stats.meanInningsTotal))
        println("Catch chances: ${stats.catchChances}, held ${stats.catchesHeld}")
        println()
        val failures = bands.count { !it.inside }
        println(if (failures == 0) "All bands met." else "$failures band(s) outside target.")
    }

    private fun bandsFor(format: MatchFormat, stats: CalibrationStats): List<Band> {
        val overs = format.oversPerInnings ?: 0
        val (rateLow, rateHigh) = when {
            format.isMultiDay -> 3.0 to 3.5
            overs <= 20 -> 8.0 to 8.8
            else -> 5.4 to 6.2
        }
        val (dotLow, dotHigh) = when {
            format.isMultiDay -> 68.0 to 75.0
            overs <= 20 -> 30.0 to 36.0
            else -> 38.0 to 45.0
        }
        val (boundaryLow, boundaryHigh) = when {
            format.isMultiDay -> 9.0 to 11.0
            overs <= 20 -> 17.0 to 20.0
            else -> 13.0 to 15.0
        }
        val (wicketLow, wicketHigh) = when {
            format.isMultiDay -> 55.0 to 65.0
            overs <= 20 -> 16.0 to 19.0
            else -> 35.0 to 40.0
        }
        val (wideLow, wideHigh) = if (format.isMultiDay) 1.5 to 2.5 else 3.0 to 5.0

        return listOf(
            Band("Run rate (per over)", rateLow, rateHigh, stats.runRate),
            Band("Dot ball %", dotLow, dotHigh, stats.dotPercent),
            Band("Boundary % of balls", boundaryLow, boundaryHigh, stats.boundaryPercent),
            Band("Balls per wicket", wicketLow, wicketHigh, stats.ballsPerWicket),
            Band("Wide %", wideLow, wideHigh, stats.widePercent),
            Band("No ball %", 0.4, 1.0, stats.noBallPercent),
            Band("Byes+leg byes % of runs", 1.5, 2.5, stats.byePercentOfRuns),
            Band("Catch success %", 75.0, 80.0, stats.catchSuccessRate * 100.0),
            Band("Caught % of dismissals", 56.0, 62.0, stats.dismissalShare(DismissalMode.CAUGHT)),
            Band("Bowled % of dismissals", 18.0, 22.0, stats.dismissalShare(DismissalMode.BOWLED)),
            Band("LBW % of dismissals", 12.0, 16.0, stats.dismissalShare(DismissalMode.LBW)),
            Band("Run out % of dismissals", 4.0, 6.0, stats.dismissalShare(DismissalMode.RUN_OUT)),
            Band("Stumped % of dismissals", 1.0, 3.0, stats.dismissalShare(DismissalMode.STUMPED)),
        )
    }

    private fun formatFor(name: String): MatchFormat = when (name) {
        "T20" -> MatchFormat.T20
        "LIST_A" -> MatchFormat.LIST_A
        "FIRST_CLASS" -> MatchFormat.FOUR_DAY
        "TEST" -> MatchFormat.TEST
        else -> MatchFormat.T20
    }
}
