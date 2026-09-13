package com.cricketcareer.engine.match.delivery

import com.cricketcareer.engine.match.field.FieldSetting
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.rng.SimRandom
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** The ball as the batter believes it to be. Stage 5 measures his shot against the real one. */
data class PerceivedBall(
    val lengthMetres: Double,
    val lineMetres: Double,
    val heightMetres: Double,
    val paceKph: Double,
    /** False when he failed to pick a googly, doosra or carrom ball out of the hand. */
    val readTheDelivery: Boolean,
)

/** What he decided to play, and how much he was going after it. */
data class ShotSelection(val shot: Shot, val riskAppetite: Double)

/**
 * Stage 4 — the batter's read and his shot.
 *
 * The move this whole engine turns on: the batter selects a shot from the ball
 * he *thinks* is coming, and Stage 5 then scores that shot against the ball that
 * actually arrived. Every edge, play-and-miss and mistimed pull is the gap
 * between the two. There is no "edge probability" anywhere in the codebase.
 *
 * See docs/SIMULATION_MODEL.md §7.
 */
object Stage4Read {

    fun perceive(context: DeliveryContext, intent: BowlerIntent, ball: DeliveredBall, rng: SimRandom): PerceivedBall {
        val tuning = context.tuning.perception
        val knob = context.tuning.knobs.perceptionErrorScale

        val technique = context.strikerSkill(Attribute.TECHNIQUE)
        val concentration = context.strikerSkill(Attribute.CONCENTRATION)

        // Settling, in two stages: sighting the ball over a handful of
        // deliveries, and getting properly in over the following hour. At nought
        // balls faced this is 1.75x, at fifteen 1.33x, at forty 1.14x, and it is
        // still improving at a hundred. That is what produces the brief's
        // "vulnerable in his first 10-15 balls", a hazard that keeps falling
        // through a long innings, and the gap between a Twenty20 average and a
        // first-class one.
        val settle = 1.0 +
            tuning.settleWeight * exp(-context.strikerBallsFaced / tuning.settleScaleBalls) +
            tuning.deepSettleWeight * exp(-context.strikerBallsFaced / tuning.deepSettleScaleBalls)

        val comfort = tuning.paceComfortFloorKph +
            tuning.paceComfortRangeKph * context.strikerSkill(Attribute.PACE_PLAY)
        val paceDiscomfort = 1.0 + tuning.paceDiscomfortWeight *
            max(0.0, (ball.paceKph - comfort) / tuning.paceDiscomfortScaleKph)

        val experience = context.strikerSkill(Attribute.SPIN_PLAY)
        val deception = 1.0 + intent.deliveryType.deception * (1.0 - experience)
        val light = 1.0 + tuning.lightWeight * (1.0 - context.weather.lightQuality)

        val sigma = tuning.baseSigmaMetres * knob *
            (tuning.techniqueWorst - (tuning.techniqueWorst - tuning.techniqueBest) * technique) *
            (tuning.concentrationWorst - (tuning.concentrationWorst - tuning.concentrationBest) * concentration) *
            settle * paceDiscomfort * deception * light

        // Whether he picked the wrong'un. On failure his sense of which way it
        // is turning is inverted, which is exactly how a googly produces an
        // inside edge or an lbw.
        val readIt = if (intent.deliveryType.deception <= 0.0) {
            true
        } else {
            rng.chance(
                logistic(
                    tuning.wrongUnDetectionSlope *
                        (experience + context.strikerSkill(Attribute.TECHNIQUE) - 2.0 * intent.deliveryType.deception),
                ),
            )
        }

        // He sees the ball as it was at his decision point, then extrapolates —
        // well if his technique is good, badly if not. Late movement is what
        // survives that extrapolation.
        val extrapolation = 0.35 + 0.5 * technique
        val expectedLine = ball.lineAtDecisionMetres +
            (ball.lineAtStumpsMetres - ball.lineAtDecisionMetres) * extrapolation
        // A misread wrong'un: he expects the turn to go the other way.
        val misreadLine = if (readIt) expectedLine else expectedLine - 2.0 * ball.turnMetres * extrapolation

        return PerceivedBall(
            lengthMetres = ball.effectiveLengthMetres + rng.nextGaussian() * sigma,
            lineMetres = misreadLine + rng.nextGaussian() * sigma * tuning.lineSigmaFraction,
            heightMetres = ball.heightAtStumpsMetres + rng.nextGaussian() * sigma * tuning.lineSigmaFraction,
            paceKph = ball.paceKph,
            readTheDelivery = readIt,
        )
    }

    fun selectShot(
        context: DeliveryContext,
        perceived: PerceivedBall,
        rng: SimRandom,
    ): ShotSelection {
        val tuning = context.tuning.perception
        val risk = riskAppetite(context)

        val temperature = tuning.shotTemperature *
            (1.0 + tuning.shotComposureWeight * (1.0 - context.strikerSkill(Attribute.COMPOSURE))) *
            (1.0 + tuning.shotPressureWeight * context.pressure)

        val options = Shot.ALL
        val utilities = DoubleArray(options.size) { i -> utility(context, options[i], perceived, risk) }
        val shot = rng.pickWeighted(options, softmax(utilities, temperature))
        return ShotSelection(shot, risk)
    }

    /**
     * How much he is going after it.
     *
     * The user's posture setting (Q3) enters here as [DeliveryContext.battingIntent],
     * as a term in the same expression an AI batter uses — never a parallel path.
     */
    private fun riskAppetite(context: DeliveryContext): Double {
        val tuning = context.tuning.perception
        val situation = context.situation

        val intent = context.tuning.formatIntent.forFormat(context.format)
        val base = intent.baseRisk +
            tuning.aggressionRiskWeight * context.strikerSkill(Attribute.AGGRESSION) +
            context.tuning.knobs.aggressionBias
        val required = situation.requiredRate?.let { rate ->
            ((rate - 7.0) / 7.0).coerceIn(-0.6, 1.2)
        } ?: run {
            // Not chasing: the phase of the innings decides how hard to go.
            val total = situation.totalOvers ?: return@run 0.0
            // Phase of the innings, per format. A Twenty20 attacks from ball
            // one and accelerates; a fifty-over innings has a long flat middle;
            // a Test innings barely changes at all.
            val fraction = situation.over.toDouble() / total
            when {
                fraction < 0.30 -> intent.earlyPhaseRisk
                fraction < 0.75 -> intent.middlePhaseRisk
                else -> intent.latePhaseRisk
            }
        }

        // Wickets in hand buy licence; a collapse takes it away.
        val wicketLicence = 0.22 * (1.0 - situation.wicketsLost / 10.0) - 0.10

        return (
            base +
                tuning.requiredRateWeight * required +
                wicketLicence +
                context.battingIntent * 0.35 -
                tuning.settlednessCaution * (1.0 - context.settledness) -
                tuning.newBatterCaution * (1.0 - context.settledness) * (1.0 - context.strikerSkill(Attribute.CONCENTRATION))
            ).coerceIn(0.0, 1.6)
    }

    private fun utility(context: DeliveryContext, shot: Shot, perceived: PerceivedBall, risk: Double): Double {
        // How well this stroke suits the ball he thinks is coming. Squared
        // distance in each axis, in units of the shot's own tolerance.
        // Batters adapt along the length axis far more than across the line: you
        // can drive anything from a yorker to a fullish ball, but a cover drive
        // to a ball on leg stump is a different shot. Hence the wider length
        // denominator.
        val dLength = (perceived.lengthMetres - shot.idealLengthMetres) / (2.30 * shot.toleranceScale)
        val dLine = (perceived.lineMetres - shot.idealLineMetres) / (0.62 * shot.toleranceScale)
        val dHeight = (perceived.heightMetres - shot.idealHeightMetres) / (0.58 * shot.toleranceScale)
        val fit = -(dLength * dLength + dLine * dLine + dHeight * dHeight)

        if (shot == Shot.LEAVE) {
            // A leave is only on for a ball going past off stump and not
            // threatening the timber.
            val threatensStumps = abs(perceived.lineMetres) < Geometry.STUMP_HALF_WIDTH_M + 0.10 &&
                perceived.heightMetres < Geometry.STUMP_HEIGHT_M + 0.12
            if (threatensStumps || perceived.lineMetres < 0.26) return -20.0

            // Scored on the SAME scale as every stroke: its geometric fit, plus
            // what patience is worth here, minus the cost of using up a ball.
            // Scoring it on its own scale made a leave beat a defensive shot to
            // almost any delivery, and batters left a third of a Twenty20.
            val intent = context.tuning.formatIntent.forFormat(context.format)
            val formatPatience = intent.leavePatience
            // Leaving costs a delivery. In a format with only 120 of them that
            // is a real price, and it is why nobody leaves in a Twenty20.
            val ballCost = intent.leaveBallCost
            return fit * 1.5 - ballCost +
                formatPatience * (
                    1.9 * (1.0 - risk) +
                        1.1 * context.strikerSkill(Attribute.PATIENCE) * (1.0 - context.settledness)
                    )
        }

        val skill = context.strikerSkill(shot.keyAttribute)
        val reward = context.fieldRewards[shot]

        // Risk appetite buys the shot's reward; caution charges for its danger.
        // A defensive stroke suits a far wider range of deliveries than a loft,
        // so it wins on fit every time - the reward term is what has to pay for
        // the attacking shot, and it has to pay enough or nobody ever plays one.
        // The reward is what the shot might SCORE, which is its power, not its
        // danger. Pricing the upside by `risk` made a wild shot attractive for
        // being wild and left a Twenty20 batter blocking and running singles.
        // The caution charge is floored at zero. Letting `1 - risk` go negative
        // when a batter is desperate turned the charge into a second bonus, so
        // the most reckless states in the game rewarded reckless shots twice.
        val caution = (1.0 - risk).coerceAtLeast(0.0)
        return fit * 1.5 +
            1.25 * skill +
            4.2 * risk * shot.powerFactor * reward -
            2.2 * caution * shot.risk -
            0.55 * (1.0 - shot.toleranceScale)
    }

    private fun softmax(utilities: DoubleArray, temperature: Double): DoubleArray {
        var max = Double.NEGATIVE_INFINITY
        for (u in utilities) if (u > max) max = u
        val weights = DoubleArray(utilities.size)
        for (i in utilities.indices) weights[i] = exp((utilities[i] - max) / temperature)
        return weights
    }

    private fun logistic(x: Double): Double = 1.0 / (1.0 + exp(-x))
}

/**
 * How attractive a shot's area is, given where the fielders actually are.
 *
 * This is what makes a field setting matter to the batter rather than being
 * decoration: lofting over a vacant deep midwicket scores more than lofting to
 * a man there, and a gap at third man makes the steer worth playing.
 */
object FieldGaps {

    /** 0 (a fielder exactly there) to 1 (nobody near it). */
    fun reward(shot: Shot, field: FieldSetting): Double {
        var worst = 1.0
        field.fielders.forEach { fielder ->
            val separation = angularSeparation(shot.azimuthDegrees, fielder.position.azimuthDegrees)
            // A deep fielder only guards an aerial shot; an infielder only a
            // ground one. Hitting over the top of cover is not cover's problem.
            val relevant = if (shot.isAerial) fielder.position.isOutsideRing else !fielder.position.isOutsideRing
            if (!relevant) return@forEach
            // Within 12 degrees he is in the way; beyond 35 he is irrelevant.
            val blocked = ((35.0 - separation) / 23.0).coerceIn(0.0, 1.0)
            worst = minOf(worst, 1.0 - blocked)
        }
        return worst
    }

    /** Smallest angle between two bearings, in degrees. */
    fun angularSeparation(a: Double, b: Double): Double {
        val raw = abs(a - b) % 360.0
        return if (raw > 180.0) 360.0 - raw else raw
    }
}


/**
 * What every stroke in the book is worth against one field.
 *
 * Shot selection scores all twenty-odd strokes on every ball, and each score
 * needs the gap the stroke would be played into — which means walking the whole
 * field per stroke, per ball. That was two hundred-odd angular comparisons a
 * delivery to answer a question whose answer only changes when the captain
 * moves somebody, which he does once an over.
 *
 * So it is computed once per field and read by index. Not a cache and not
 * global state: it is an immutable value the simulator builds when it sets the
 * field and hands to every ball of that over, which keeps `:engine` free of the
 * mutable statics CLAUDE.md §4 bans and stays correct when ten thousand matches
 * run on ten threads at once.
 */
class FieldRewards private constructor(private val byShot: DoubleArray) {

    operator fun get(shot: Shot): Double = byShot[shot.ordinal]

    companion object {
        fun of(field: FieldSetting): FieldRewards =
            FieldRewards(DoubleArray(Shot.entries.size) { FieldGaps.reward(Shot.entries[it], field) })
    }
}
