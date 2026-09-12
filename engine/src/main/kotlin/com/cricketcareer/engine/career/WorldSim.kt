package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.WorldTuning
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.SimRandom
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * How much of the engine a match gets.
 *
 * Decided in docs/OPEN_QUESTIONS.md Q1 and not negotiable per match: a match is
 * in tier 1 because the user or one of his direct rivals for a place is in it,
 * which is a property of the fixture, not a performance optimisation applied
 * when the phone gets warm.
 */
enum class SimTier {
    /** The user's matches and his direct rivals'. Full ball-by-ball. */
    FULL,

    /** The rest of his competition. Reduced-form innings. */
    REDUCED,

    /** Other countries' domestic cricket. Aggregate season. */
    AGGREGATE,
}

/** One reduced-form innings: enough to keep an average, and nothing more. */
data class ReducedInnings(val runs: Int, val ballsFaced: Int, val out: Boolean)

/** One reduced-form bowling spell. */
data class ReducedSpell(val ballsBowled: Int, val runsConceded: Int, val wickets: Int)

/**
 * Tiers 2 and 3.
 *
 * The rule this module exists to keep: **a tier-2 player's career figures must
 * be comparable with a tier-1 player's.** If a batter averages 38 in the user's
 * competition and 47 in a reduced one, then every "leading run-scorer" table in
 * the game is a lie and the selection model built on top of it is picking the
 * wrong people.
 *
 * So the parameters here are fitted from engine output rather than chosen. They
 * live in [WorldTuning], `sim-harness` fits them, and the calibration suite
 * checks the two tiers still agree.
 *
 * See docs/CAREER_MODEL.md §9.
 */
object WorldSim {

    /**
     * One innings, reduced.
     *
     * Drawn from an exponential rather than a normal distribution, because
     * cricket scores are roughly geometric: a batter's most likely score is
     * low, his mean sits well above his median, and there is a long right tail.
     * A normal draw would give a world with no ducks and no hundreds, and every
     * career would look the same.
     */
    fun innings(
        batter: Player,
        format: MatchFormat,
        bowlingStandard: Double,
        random: SimRandom,
        tuning: WorldTuning,
    ): ReducedInnings {
        require(bowlingStandard in 0.0..1.0) { "bowlingStandard $bowlingStandard must be in 0..1" }
        val standard = Selection.standardFor(batter, format)
        val base = meanRuns(format, tuning)
        // Standard enters as a ratio around the average player, so the scale of
        // the format and the quality of the player stay separable.
        val skill = 1.0 + tuning.standardToRuns * (standard - 0.5)
        val opposition = 1.0 - 0.6 * (bowlingStandard - 0.5)
        val form = 1.0 + tuning.formEffect * batter.state.form
        val mean = (base * skill * opposition * form).coerceAtLeast(1.0)

        val runs = drawGeometricish(mean, random)
        // A big score is a longer innings, not merely a faster one - but how
        // long depends on who is batting and on the innings he happens to play.
        val balls = (runs * ballsPerRun(format, tuning) * tempo(batter, random, tuning))
            .roundToInt().coerceAtLeast(1)
        // An innings has eleven batters and at most ten wickets, so somebody is
        // always not out; over five days almost everybody else is.
        val dismissalRate =
            if (format.isMultiDay) tuning.dismissalRateMultiDay else tuning.dismissalRateLimitedOvers
        return ReducedInnings(runs, balls, out = random.nextDouble() < dismissalRate)
    }

    /**
     * Balls-per-run multiplier for this batter in this innings, mean 1.
     *
     * Two parts. A batter's *shape* — range hitting, power and strike rotation
     * against patience and concentration — says whether he is naturally quick
     * or slow, and it is the same distinction the full engine makes in shot
     * selection. On top of that, one innings is not the next: a batter who
     * averages a strike rate of 60 plays some innings at 40 and some at 90.
     *
     * The log-normal is shifted by -σ²/2 so its mean is exactly one. Without
     * that, widening the spread would quietly lower every strike rate in the
     * world and the tier-1/tier-2 agreement would drift with it.
     */
    private fun tempo(batter: Player, random: SimRandom, tuning: WorldTuning): Double {
        val attacking = listOf(Attribute.RANGE_HITTING, Attribute.POWER, Attribute.STRIKE_ROTATION)
            .sumOf { batter.attributes.normalised(it) } / 3.0
        val occupying = listOf(Attribute.PATIENCE, Attribute.CONCENTRATION)
            .sumOf { batter.attributes.normalised(it) } / 2.0
        // Quicker scoring means fewer balls per run, so the term subtracts.
        val shape = 1.0 - tuning.tempoFromAttributes * (attacking - occupying)
        val sigma = tuning.tempoSpread
        val noise = exp(random.nextGaussian() * sigma - sigma * sigma / 2.0)
        return (shape * noise).coerceIn(0.35, 3.0)
    }

    /** One bowling spell, reduced. */
    fun spell(
        bowler: Player,
        format: MatchFormat,
        ballsBowled: Int,
        battingStandard: Double,
        random: SimRandom,
        tuning: WorldTuning,
    ): ReducedSpell {
        require(ballsBowled >= 0) { "ballsBowled $ballsBowled cannot be negative" }
        require(battingStandard in 0.0..1.0) { "battingStandard $battingStandard must be in 0..1" }
        if (ballsBowled == 0) return ReducedSpell(0, 0, 0)

        val standard = Selection.standardFor(bowler, format)
        val baseStrikeRate = meanStrikeRate(format, tuning)
        // Better bowlers strike sooner, so skill divides rather than multiplies.
        val skill = (1.0 + tuning.standardToStrikeRate * (standard - 0.5)).coerceAtLeast(0.25)
        val opposition = 1.0 + 0.7 * (battingStandard - 0.5)
        val strikeRate = (baseStrikeRate / skill * opposition).coerceAtLeast(4.0)

        var wickets = 0
        val perBall = 1.0 / strikeRate
        repeat(ballsBowled) { if (random.nextDouble() < perBall) wickets++ }

        // Runs per ball is the inverse of balls per run, measured directly.
        // Deriving it from the mean and the strike rate instead would mix a
        // per-innings figure with a per-wicket one and land 25% low.
        val economyPerBall = 1.0 / ballsPerRun(format, tuning) / skill * opposition
        val runs = drawGeometricish(economyPerBall * ballsBowled, random).coerceAtLeast(0)
        return ReducedSpell(ballsBowled, runs, wickets)
    }

    /**
     * An exponential draw rounded to a whole number of runs.
     *
     * Inverse-transform rather than a rejection loop, so the cost is exactly
     * one draw from the stream however extreme the result. A rejection loop
     * would consume a variable number of draws and make the whole world's
     * subsequent randomness depend on one batter's score.
     */
    internal fun drawGeometricish(mean: Double, random: SimRandom): Int {
        require(mean > 0.0 && mean.isFinite()) { "mean $mean must be finite and positive" }
        val u = random.nextDouble().coerceIn(1e-12, 1.0 - 1e-12)
        return (-mean * ln(1.0 - u)).roundToInt().coerceAtLeast(0)
    }

    private fun meanRuns(format: MatchFormat, tuning: WorldTuning): Double = when {
        format.isMultiDay -> tuning.meanRunsMultiDay
        format.oversPerInnings != null && format.oversPerInnings!! <= 20 -> tuning.meanRunsT20
        else -> tuning.meanRunsListA
    }

    private fun meanStrikeRate(format: MatchFormat, tuning: WorldTuning): Double = when {
        format.isMultiDay -> tuning.meanStrikeRateMultiDay
        format.oversPerInnings != null && format.oversPerInnings!! <= 20 -> tuning.meanStrikeRateT20
        else -> tuning.meanStrikeRateListA
    }

    /** Balls per run, the inverse of a typical strike rate for the format. */
    private fun ballsPerRun(format: MatchFormat, tuning: WorldTuning): Double = when {
        format.isMultiDay -> tuning.ballsPerRunMultiDay
        format.oversPerInnings != null && format.oversPerInnings!! <= 20 -> tuning.ballsPerRunT20
        else -> tuning.ballsPerRunListA
    }

    /**
     * A whole season for one player at tier 3, where individual matches are
     * never inspected and only the totals are ever read.
     *
     * Still drawn innings by innings rather than as one aggregate, because a
     * season of 22 innings has ducks and hundreds in it and an aggregate does
     * not — and "most hundreds this season" is a table the game shows.
     */
    fun season(
        player: Player,
        format: MatchFormat,
        inningsCount: Int,
        oppositionStandard: Double,
        random: SimRandom,
        tuning: WorldTuning,
    ): List<ReducedInnings> {
        require(inningsCount >= 0) { "inningsCount $inningsCount cannot be negative" }
        return List(inningsCount) { innings(player, format, oppositionStandard, random, tuning) }
    }

    /** Mean of a list of reduced innings, in the batting-average sense. */
    fun average(innings: List<ReducedInnings>): Double {
        val dismissals = innings.count { it.out }
        val runs = innings.sumOf { it.runs }
        return if (dismissals == 0) runs.toDouble() else runs.toDouble() / dismissals
    }

}
