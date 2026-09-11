package com.cricketcareer.engine.match.delivery

import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.world.SoilType
import com.cricketcareer.engine.rng.SimRandom
import kotlin.math.exp
import kotlin.math.pow

/**
 * The ball as it actually arrives, after everything the air and the pitch did
 * to it.
 *
 * [lineAtDecisionMetres] is the key field for Stage 4: it is where the ball had
 * got to by the moment the batter committed to a shot. Deviation after that
 * point is what beats him, and it is the whole mechanism by which late swing is
 * more dangerous than early swing.
 */
data class DeliveredBall(
    val pitchPointMetres: Double,
    /** Effective length after a spinner's dip: where the batter has to play it. */
    val effectiveLengthMetres: Double,
    val lineAtStumpsMetres: Double,
    val lineAtDecisionMetres: Double,
    val heightAtStumpsMetres: Double,
    val paceKph: Double,
    val swingMetres: Double,
    /** In-air drift, a spinner's. Kept apart from swing so nothing calls a spinner's drift "swing". */
    val driftMetres: Double,
    val seamMetres: Double,
    val turnMetres: Double,
    val isFullToss: Boolean,
    val lengthBand: LengthBand,
    val lineBand: LineBand,
) {
    /** Total lateral movement, whatever caused it. */
    val totalDeviationMetres: Double get() = swingMetres + driftMetres + seamMetres + turnMetres

    /** Movement the batter could not have seen before committing. */
    val lateDeviationMetres: Double get() = lineAtStumpsMetres - lineAtDecisionMetres
}

/**
 * Stage 3 — swing, reverse swing, seam, spin and bounce.
 *
 * See docs/SIMULATION_MODEL.md §6. The cricketing point that shapes the code:
 * swing is *chosen* and seam is *not*. A seaming ball's direction is random, and
 * that is precisely why a green top is harder to bat on than a swinging day at
 * the same deviation. Modelling seam as directional would quietly remove the
 * thing that makes it frightening.
 */
object Stage3Movement {

    fun apply(context: DeliveryContext, intent: BowlerIntent, release: Release, rng: SimRandom): DeliveredBall {
        val tuning = context.tuning.movement
        val pitch = context.pitch
        val style = context.bowler.bowlingStyle
        val isFullToss = release.pitchPointMetres <= 0.0

        val atmosphere = (
            tuning.atmosphereBase +
                tuning.humidityWeight * context.weather.humidity +
                tuning.cloudWeight * context.weather.cloudCover
            ).coerceIn(tuning.atmosphereMin, tuning.atmosphereMax)

        var swing = 0.0
        var seam = 0.0
        var turn = 0.0
        var lateness = tuning.conventionalLateness

        if (style.isPace) {
            // --- Conventional swing. Shine differential has to build before it
            // works at all, then fades: it peaks around overs 4-7 and is
            // essentially gone by 30.
            val shineTerm = (1.0 - exp(-context.ball.ageOvers / tuning.swingBuildOvers)) *
                exp(-context.ball.ageOvers / tuning.swingDecayOvers)
            // Very fast bowlers swing it less, not more: less time in the air.
            val paceTerm = exp(-((release.paceKph - tuning.swingPeakKph) / tuning.swingPaceWidth).pow(2))
            val variation = (1.0 + rng.nextGaussian() * tuning.swingVariation).coerceIn(0.4, 1.6)

            val conventional = tuning.swingMax * context.bowlerSkill(Attribute.SWING) *
                shineTerm * atmosphere * paceTerm *
                (0.55 + 0.45 * release.seamPresentation) * variation

            // --- Reverse. Soft gates, so the ball does not start reversing on
            // the stroke of over 35.
            val ageGate = logistic((context.ball.ageOvers - tuning.reverseGateOvers) / tuning.reverseGateWidth)
            val roughGate = logistic((context.ball.roughness - tuning.reverseRoughnessThreshold) * 12.0)
            val paceGate = logistic((release.paceKph - tuning.reversePaceThresholdKph) / 6.0)
            val dryness = 1.0 - pitch.moisture
            val reverse = tuning.reverseMax * context.bowlerSkill(Attribute.REVERSE_SWING) *
                ageGate * roughGate * paceGate * dryness * variation

            // The two swing in opposite directions; whichever dominates decides
            // which way it goes.
            val swingSign = if (style.rightArm) 1.0 else -1.0
            swing = swingSign * (conventional - reverse)
            if (reverse > conventional) lateness = tuning.reverseLateness

            // --- Seam off the pitch. Direction random, by design.
            if (!isFullToss) {
                val upright = tuning.seamUprightBase + tuning.seamUprightAccuracyWeight * release.seamPresentation
                val uprightFactor = if (rng.chance(upright)) 1.0 else tuning.seamScuffedPenalty
                val seamSigma = tuning.seamMax * context.bowlerSkill(Attribute.SEAM_MOVEMENT) *
                    pitch.grassCover.pow(tuning.seamGrassExponent) *
                    (tuning.seamMoistureBase + tuning.seamMoistureWeight * pitch.moisture) *
                    (0.7 + 0.45 * pitch.hardness) * uprightFactor * pitch.gripSeam
                seam = rng.nextGaussian() * seamSigma
            }
        } else if (style.isSpin && !isFullToss) {
            // --- Turn. The grip index is what a dry, worn, cracked surface
            // gives a spinner, scaled by the soil.
            val grip = tuning.gripBase + tuning.gripWeight * gripIndex(pitch.turn, pitch.abrasion, pitch.cracks, pitch.moisture, pitch.soilType)
            val variation = (1.0 + rng.nextGaussian() * tuning.swingVariation).coerceIn(0.4, 1.6)
            val magnitude = tuning.turnMax * context.bowlerSkill(Attribute.TURN) * grip *
                turnMultiplier(intent.deliveryType) * variation

            // Dew makes the ball wet: it grips nothing and skids on.
            val dewPenalty = 1.0 - 0.75 * context.weather.dew

            val awayFromRightHander = turnsAway(style.turnsAwayFromRightHander, intent.deliveryType)
            turn = magnitude * dewPenalty * if (awayFromRightHander) 1.0 else -1.0
            lateness = 2.6 // turn happens off the pitch, so it is inherently late
        }

        // Drift and dip, a spinner's other two weapons. Dip shortens the
        // effective length, which is how a batter ends up stumped having come
        // down to a ball that was never there.
        var drift = 0.0
        var dip = 0.0
        if (style.isSpin) {
            val revs = context.bowlerSkill(Attribute.DRIFT)
            drift = tuning.driftMax * revs * atmosphere * (rng.nextDouble(0.4, 1.0)) *
                if (turn >= 0) -1.0 else 1.0
            dip = tuning.dipMaxMetres * revs * rng.nextDouble(0.25, 1.0)
        }

        val lineAtStumps = release.releaseLineMetres + swing + seam + turn + drift
        // Only the fraction of the deviation that had happened by the decision
        // point is visible to the batter.
        val seen = Geometry.DECISION_POINT_FRACTION.pow(lateness)
        val lineAtDecision = release.releaseLineMetres + (swing + drift) * seen + (seam + turn) * 0.0

        val effectiveLength = release.pitchPointMetres + dip
        val height = heightAtStumps(context, release, effectiveLength, isFullToss, rng)

        return DeliveredBall(
            pitchPointMetres = release.pitchPointMetres,
            effectiveLengthMetres = effectiveLength,
            lineAtStumpsMetres = lineAtStumps,
            lineAtDecisionMetres = lineAtDecision,
            heightAtStumpsMetres = height,
            paceKph = release.paceKph,
            swingMetres = swing,
            driftMetres = drift,
            seamMetres = seam,
            turnMetres = turn,
            isFullToss = isFullToss,
            lengthBand = LengthBand.of(release.pitchPointMetres, style.isSpin),
            lineBand = LineBand.of(lineAtStumps),
        )
    }

    /**
     * Height as the ball passes the stumps.
     *
     * A yorker arrives at boot level, a good length just over the stumps, a ball
     * pitching at twelve metres around chest height. Variable bounce grows with
     * cracks and wear: on day five it is enough that a good-length ball can
     * shoot along the ground or take off.
     */
    private fun heightAtStumps(
        context: DeliveryContext,
        release: Release,
        effectiveLength: Double,
        isFullToss: Boolean,
        rng: SimRandom,
    ): Double {
        val tuning = context.tuning.movement
        if (isFullToss) return Stage2Execution.fullTossHeightAt(release.pitchPointMetres, release.paceKph)

        val pitch = context.pitch
        val bounceFactor = tuning.bounceFactorMin +
            (tuning.bounceFactorMax - tuning.bounceFactorMin) * pitch.bounce * pitch.soilType.bounceFactor.coerceAtMost(1.3)

        // Reference rebound: rises roughly linearly with how far back it pitched,
        // flattening out beyond a long hop, which drops again by the time it
        // reaches the batter.
        // Rises steeply through the back-of-a-length band into the throat ball,
        // peaks for a delivery pitching around twelve to fifteen metres, then
        // falls away again: a genuine long hop has already come down by the time
        // it reaches the batter, which is why it is a long hop and not a bouncer.
        val reference = when {
            effectiveLength < 1.0 -> 0.06 + effectiveLength * 0.20
            effectiveLength < 8.0 -> 0.26 + (effectiveLength - 1.0) * 0.075
            effectiveLength < 12.0 -> 0.79 + (effectiveLength - 8.0) * 0.155
            effectiveLength < 15.0 -> 1.41 + (effectiveLength - 12.0) * 0.10
            else -> (1.71 - (effectiveLength - 15.0) * 0.14).coerceAtLeast(0.95)
        }

        // A tall bowler with a high release hits the deck at a steeper angle.
        val releaseTerm = 1.0 + 0.14 * context.bowlerSkill(Attribute.BOUNCE) - 0.07
        val variableBounce = tuning.variableBounceBase +
            tuning.variableBounceCrackWeight * pitch.cracks * pitch.deterioration * (1.0 - pitch.evenness + 0.4)

        return (reference * bounceFactor * releaseTerm + rng.nextGaussian() * variableBounce)
            .coerceIn(0.0, 2.6)
    }

    /** How dry, worn and cracked the surface is, as one number for the spinner. */
    private fun gripIndex(turn: Double, abrasion: Double, cracks: Double, moisture: Double, soil: SoilType): Double =
        (0.45 * turn + 0.30 * abrasion + 0.25 * cracks).coerceIn(0.0, 1.0) *
            (1.0 - 0.35 * moisture) * soil.turnFactor.coerceIn(0.7, 1.35)

    /** Lateral turn relative to the stock ball, per delivery type. */
    private fun turnMultiplier(type: DeliveryType): Double = when (type) {
        DeliveryType.STOCK, DeliveryType.FLIGHTED -> 1.0
        DeliveryType.TOP_SPINNER -> 0.20
        DeliveryType.GOOGLY -> 0.90
        DeliveryType.DOOSRA -> 0.75
        DeliveryType.ARM_BALL -> 0.15
        DeliveryType.CARROM -> 0.85
        DeliveryType.QUICKER_BALL -> 0.70
        else -> 1.0
    }

    /** Whether this delivery turns away from a right-hander, given the bowler's stock direction. */
    private fun turnsAway(stockTurnsAway: Boolean?, type: DeliveryType): Boolean {
        val stock = stockTurnsAway ?: true
        return when (type) {
            DeliveryType.GOOGLY, DeliveryType.DOOSRA, DeliveryType.CARROM, DeliveryType.ARM_BALL -> !stock
            else -> stock
        }
    }

    private fun logistic(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
