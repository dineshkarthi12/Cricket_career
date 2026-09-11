package com.cricketcareer.engine.match.delivery

import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.rng.SimRandom
import kotlin.math.abs
import kotlin.math.pow

/**
 * What actually left the hand.
 *
 * [releaseLineMetres] is where the ball would pass the stumps with no lateral
 * movement at all; Stage 3 adds swing, seam and turn on top of it.
 */
data class Release(
    val pitchPointMetres: Double,
    val releaseLineMetres: Double,
    val paceKph: Double,
    /** How cleanly the seam is presented, 0 to 1. Feeds both swing and seam movement. */
    val seamPresentation: Double,
    val isNoBall: Boolean,
    val noBallReason: NoBallReason?,
    /** Length error actually made, in metres. Negative is fuller than intended. */
    val lengthError: Double,
    val lineError: Double,
)

enum class NoBallReason { OVERSTEP, ABOVE_WAIST_FULL_TOSS }

/**
 * Stage 2 — execution.
 *
 * The intended ball plus error. The error is a **mixture** of two Gaussians
 * rather than one: a single Gaussian produces far too few genuine long hops and
 * rank full tosses, and every bowler has an occasional one that gets away. That
 * heavy tail is where free boundaries — and a good share of the wickets that
 * follow them — come from.
 *
 * Note what is *not* here: there is no roll for "wide". A wide is a geometric
 * consequence of where the ball ended up, decided in Stage 6, which means the
 * wide rate is emergent from the line error rather than dialled in. See
 * docs/SIMULATION_MODEL.md §5.
 */
object Stage2Execution {

    fun execute(context: DeliveryContext, intent: BowlerIntent, rng: SimRandom): Release {
        val tuning = context.tuning.execution
        val knob = context.tuning.knobs.executionSpreadScale

        val accuracy = context.bowlerSkill(Attribute.ACCURACY)
        val composure = context.bowlerSkill(Attribute.COMPOSURE)
        val discipline = context.bowlerSkill(Attribute.DISCIPLINE)

        // Accuracy 100 lands at 0.45x the reference spread, accuracy 1 at 1.55x.
        val accuracyFactor = tuning.accuracyWorst - (tuning.accuracyWorst - tuning.accuracyBest) * accuracy
        val fatigueFactor = 1.0 + tuning.fatigueWeight * context.bowlerSpellFatigue.pow(tuning.fatigueExponent)
        val pressureFactor = 1.0 + tuning.pressureWeight * context.pressure * (1.0 - composure)
        val surfaceFactor = 1.0 + 0.12 * context.pitch.deterioration

        val spread = accuracyFactor * fatigueFactor * pressureFactor * surfaceFactor *
            intent.deliveryType.executionDifficulty * knob

        // The heavy tail: one ball in thirty is simply not what he meant.
        val tailWidening = if (rng.chance(tuning.tailProbability)) tuning.tailWidening else 1.0

        val rawLength = rng.nextGaussian()
        val rawLine = rng.nextGaussian()
        // Dragging down tends to go down the leg side for a right-arm-over
        // bowler; the correlation is applied along the release angle.
        val correlatedLine = tuning.lengthLineCorrelation * rawLength +
            kotlin.math.sqrt(1.0 - tuning.lengthLineCorrelation * tuning.lengthLineCorrelation) * rawLine

        val lengthError = rawLength * tuning.lengthSigmaBase * spread * tailWidening
        val lineError = correlatedLine * tuning.lineSigmaBase * spread * tailWidening

        val pitchPoint = intent.targetLengthMetres + lengthError
        val line = intent.targetLineMetres + lineError

        val pace = intent.targetPaceKph *
            (1.0 - tuning.paceFatigueLoss * context.bowlerSpellFatigue) *
            (1.0 + rng.nextGaussian() * tuning.paceSigmaFraction)

        // Overstepping is largely independent of what the ball does, so it is
        // the one thing in this stage that is genuinely sampled.
        val overstepChance = tuning.noBallBase *
            (1.0 + tuning.noBallDisciplineWeight * (1.0 - discipline)) *
            (1.0 + tuning.noBallFatigueWeight * context.bowlerSpellFatigue) *
            (0.6 + 0.8 * context.bowlerSkill(Attribute.PACE))
        val overstepped = rng.chance(overstepChance)

        // A full toss above waist height is a no-ball too, and it is simply what
        // the far tail of the length error looks like when it goes the whole way.
        val fullToss = pitchPoint <= 0.0
        val fullTossHeight = if (fullToss) fullTossHeightAt(pitchPoint, pace) else 0.0
        val beamer = fullToss && fullTossHeight > Geometry.WAIST_HEIGHT_M

        val seamPresentation = (
            tuning.accuracyBest + 0.55 * accuracy + 0.25 * rng.nextDouble() -
                0.30 * abs(lengthError).coerceAtMost(1.5) / 1.5
            ).coerceIn(0.0, 1.0)

        return Release(
            pitchPointMetres = pitchPoint,
            releaseLineMetres = line,
            paceKph = pace,
            seamPresentation = seamPresentation,
            isNoBall = overstepped || beamer,
            noBallReason = when {
                beamer -> NoBallReason.ABOVE_WAIST_FULL_TOSS
                overstepped -> NoBallReason.OVERSTEP
                else -> null
            },
            lengthError = lengthError,
            lineError = lineError,
        )
    }

    /**
     * Height of a full toss as it reaches the batter.
     *
     * A ball that would have pitched just past the crease arrives at shin
     * height; one that never gets near the pitch arrives at the head. Linear in
     * how far past the stumps it would have landed, which is close enough over
     * the two metres where it matters.
     */
    fun fullTossHeightAt(pitchPointMetres: Double, paceKph: Double): Double {
        val overshoot = (-pitchPointMetres).coerceAtLeast(0.0)
        // Faster balls are flatter, so the same overshoot arrives lower.
        val flatness = (paceKph / 130.0).coerceIn(0.55, 1.35)
        // Deliberately shallow: most full tosses arrive at thigh height and are
        // simply bad balls. Only one that misses the pitch by a long way is a
        // waist-high no-ball, which is why beamers are rare.
        return (0.15 + overshoot * 0.34 / flatness).coerceIn(0.0, 2.4)
    }
}
