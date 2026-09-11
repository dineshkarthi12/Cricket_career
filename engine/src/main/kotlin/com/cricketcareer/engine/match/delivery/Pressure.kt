package com.cricketcareer.engine.match.delivery

import com.cricketcareer.engine.config.PressureTuning
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.world.LadderLevel
import kotlin.math.exp
import kotlin.math.pow

/** What the scoreboard looks like from where the batter is standing. */
data class MatchSituation(
    val over: Int,
    val ballInOver: Int,
    val totalOvers: Int?,
    val runs: Int,
    val wicketsLost: Int,
    val target: Int?,
    val ballsRemaining: Int?,
    val ballsSinceLastWicket: Int,
    /** Dot balls in the last `dotWindowBalls` legal deliveries. */
    val recentDots: Int,
    /** Boundaries in the last three legal deliveries, used by the bowler's plan. */
    val recentBoundaries: Int,
    val level: LadderLevel,
) {
    /** Required run rate, or null when not chasing. */
    val requiredRate: Double?
        get() {
            val target = target ?: return null
            val balls = ballsRemaining ?: return null
            if (balls <= 0) return null
            return (target - runs).coerceAtLeast(0) * 6.0 / balls
        }

    val currentRate: Double
        get() {
            val balls = over * 6 + ballInOver
            return if (balls == 0) 0.0 else runs * 6.0 / balls
        }
}

/**
 * The pressure index: one number per ball, consumed by four stages.
 *
 * This is the mechanism that produces collapses without anything in the code
 * being called "collapse". It cuts both ways — pressure makes the bowler worse
 * too, so a bowler defending six off the last over is not simply an obstacle.
 *
 * See docs/SIMULATION_MODEL.md §3.
 */
object Pressure {

    /**
     * @param parRunRate what a good score would be scored at here, used as the
     *   yardstick when nobody is chasing a number.
     */
    fun index(
        situation: MatchSituation,
        strikerAttributes: Attributes,
        strikerHiddenBigMatch: Int,
        parRunRate: Double,
        tuning: PressureTuning,
    ): Double {
        val chaseStress = situation.requiredRate?.let { required ->
            (((required - parRunRate) / parRunRate).coerceIn(-1.0, 2.0) + 1.0) / 3.0
        } ?: run {
            // Setting a total: scoreboard pressure is falling behind par with
            // wickets gone, not a number on the board.
            val behind = ((parRunRate - situation.currentRate) / parRunRate).coerceIn(-1.0, 1.0)
            ((behind + 1.0) / 2.0) * (situation.wicketsLost / 10.0)
        }

        val clusterTerm = exp(-situation.ballsSinceLastWicket / tuning.wicketClusterScaleBalls)
        val wicketStress = (
            0.6 * (situation.wicketsLost / 10.0).pow(tuning.wicketExponent) + 0.4 * clusterTerm
            ).coerceIn(0.0, 1.0)

        val dotStress = (situation.recentDots.toDouble() / tuning.dotWindowBalls).coerceIn(0.0, 1.0)

        val phaseStress = situation.totalOvers?.let { total ->
            // Death overs in white-ball cricket.
            val fraction = situation.over.toDouble() / total
            if (fraction > 0.75) (fraction - 0.75) * 4.0 else 0.0
        } ?: 0.0

        // A big-match player feels less of the occasion; a flat-track bully more.
        val temperament = (strikerHiddenBigMatch - 50) / 50.0
        val occasion = (situation.level.attention * (1.0 - 0.5 * temperament)).coerceIn(0.0, 1.0)

        val weighted = tuning.chaseWeight * chaseStress +
            tuning.wicketWeight * wicketStress +
            tuning.dotWeight * dotStress +
            tuning.phaseWeight * phaseStress.coerceIn(0.0, 1.0) +
            tuning.occasionWeight * occasion

        // Composure damps what the player actually feels, rather than what the
        // situation objectively is.
        val composure = strikerAttributes.normalised(com.cricketcareer.engine.model.player.Attribute.COMPOSURE)
        val felt = weighted * (1.25 - 0.5 * composure)

        return logistic(tuning.slope * felt * 3.0 - tuning.intercept)
    }

    private fun logistic(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
