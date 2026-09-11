package com.cricketcareer.presentation

import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.model.world.MatchFormat

/** A point on the worm: cumulative runs after [legalBalls] legal deliveries. */
data class WormPoint(val legalBalls: Int, val runs: Int)

/** One bar of the manhattan. */
data class OverBar(val over: Int, val runs: Int, val wickets: Int)

/**
 * One delivery on the pitch map.
 *
 * [lineMetres] is signed off the stumps — positive to the off side for a
 * right-hander — and [lengthMetres] is measured back from the striker's
 * stumps. Both are the engine's own coordinates, passed through rather than
 * rescaled: the screen does the rescaling, because only the screen knows how
 * big it is.
 *
 * [fullToss] matters more than it looks. The engine gives a full toss a
 * *negative* length, because it never pitched — it was still in the air when it
 * reached the bat. Plotted naively that puts a dot behind the stumps, off the
 * bottom of the strip, which is nonsense. A broadcast pitch map draws full
 * tosses in a band of their own at the batter's end, and the flag is what lets
 * the screen do the same instead of quietly hiding them.
 */
data class PitchMapPoint(
    val lineMetres: Double,
    val lengthMetres: Double,
    val fullToss: Boolean,
    val wicket: Boolean,
    val boundary: Boolean,
)

/** Where a shot went, for the wagon wheel. */
data class WagonSpoke(val azimuthDegrees: Double, val carryMetres: Double, val runs: Int)

/** Everything the analysis screen draws. */
data class ChartState(
    val worm: List<WormPoint>,
    val manhattan: List<OverBar>,
    val pitchMap: List<PitchMapPoint>,
    val wagonWheel: List<WagonSpoke>,
)

/**
 * The charts.
 *
 * The worm plots against **legal balls**, not against deliveries. Plotting
 * deliveries walks the line one step right for every wide, so a scrappy innings
 * ends past the right-hand edge of a chart whose axis says twenty overs. It is
 * invisible until someone bowls eight wides and then it is obviously wrong.
 */
fun chartState(balls: List<BallEvent>): ChartState {
    val worm = ArrayList<WormPoint>(balls.size + 1)
    worm += WormPoint(0, 0)
    var legal = 0

    // Overs are accumulated in an array indexed by over number rather than a
    // map, so an over in which nothing happened still gets a zero bar instead
    // of vanishing from the chart and shifting everything after it left.
    val lastOver = balls.maxOfOrNull { it.id.over } ?: -1
    val overRuns = IntArray(lastOver + 1)
    val overWickets = IntArray(lastOver + 1)

    val pitchMap = ArrayList<PitchMapPoint>(balls.size)
    val wagon = ArrayList<WagonSpoke>()

    for (ball in balls) {
        val outcome = ball.outcome
        if (outcome.isLegalBall) legal++
        worm += WormPoint(legal, ball.scoreAfter)

        overRuns[ball.id.over] += outcome.totalRuns
        if (outcome.dismissal != null) overWickets[ball.id.over]++

        val pitchPoint = ball.delivered.pitchPointMetres
        pitchMap += PitchMapPoint(
            lineMetres = ball.delivered.lineAtStumpsMetres,
            lengthMetres = pitchPoint,
            fullToss = pitchPoint <= 0.0,
            wicket = outcome.dismissal != null,
            boundary = outcome.runsOffBat >= 4,
        )

        // Only deliveries that were actually hit belong on a wagon wheel. A
        // spoke at zero for every leave turns the middle of the chart into a
        // solid blob and hides the scoring areas, which is the one thing the
        // chart is for.
        val trajectory = ball.trajectory
        if (trajectory != null && outcome.runsOffBat > 0) {
            wagon += WagonSpoke(trajectory.azimuthDegrees, trajectory.carryMetres, outcome.runsOffBat)
        }
    }

    return ChartState(
        worm = worm,
        manhattan = List(lastOver + 1) { OverBar(it + 1, overRuns[it], overWickets[it]) },
        pitchMap = pitchMap,
        wagonWheel = wagon,
    )
}

/**
 * The run rate the chase has to beat, as a line on the worm.
 *
 * Returned as two points rather than a rate, because that is what a chart
 * draws, and because computing it in the composable is how the line ends up
 * anchored to the wrong axis.
 */
fun targetLine(target: Int, format: MatchFormat): List<WormPoint>? {
    val balls = format.ballsPerInnings ?: return null
    return listOf(WormPoint(0, 0), WormPoint(balls, target - 1))
}
