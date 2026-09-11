package com.cricketcareer.engine.match.delivery

import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.rng.SimRandom
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Where on the bat — or on the batter — the ball hit. */
@Serializable
enum class ContactPoint(val displayName: String, val isEdge: Boolean = false) {
    MIDDLE("the middle of the bat"),
    INSIDE_EDGE("an inside edge", isEdge = true),
    OUTSIDE_EDGE("an outside edge", isEdge = true),
    TOP_EDGE("a top edge", isEdge = true),
    BOTTOM_EDGE("a bottom edge", isEdge = true),
    LEADING_EDGE("a leading edge", isEdge = true),
    SPLICE("the splice"),
    GLOVE("the glove"),
    PAD("the pad"),
    BODY("the body"),
    MISSED("thin air"),
    ;

    val hitTheBat: Boolean get() = this != MISSED && this != PAD && this != BODY
}

/** The result of bat meeting ball, or not. */
data class Contact(
    /** 0 is a complete miss, 1 is the middle of the bat. */
    val quality: Double,
    val point: ContactPoint,
    /** Normalised mismatch along each axis, kept for commentary and debugging. */
    val lengthMismatch: Double,
    val lineMismatch: Double,
    val heightMismatch: Double,
    val timingErrorSeconds: Double,
)

/**
 * Stage 5 — bat on ball.
 *
 * The batter committed to a stroke based on the ball he perceived. His bat
 * therefore arrives somewhere determined by that stroke and that perception —
 * not by where the ball actually is. The mismatch between the two decides
 * everything, and the *direction* of the mismatch decides where on the bat it
 * hit.
 *
 * Nothing here is sampled from a table of outcomes. Edges are geometry.
 * See docs/SIMULATION_MODEL.md §8.
 */
object Stage5Contact {

    fun resolve(
        context: DeliveryContext,
        ball: DeliveredBall,
        perceived: PerceivedBall,
        selection: ShotSelection,
        rng: SimRandom,
    ): Contact {
        val tuning = context.tuning.contact
        val shot = selection.shot

        if (!shot.makesContact) {
            // He shouldered arms. Whether that was wise is Stage 6's problem.
            return Contact(0.0, ContactPoint.MISSED, 0.0, 0.0, 0.0, 0.0)
        }

        // A good player adapts the stroke to the ball he saw; a poor one plays
        // the shot he decided on and hopes. What is left over is the price of
        // having chosen the wrong stroke.
        val adjust = (0.55 + 0.40 * context.strikerSkill(Attribute.TECHNIQUE)).coerceIn(0.0, 1.0)
        val batLength = shot.idealLengthMetres + adjust * (perceived.lengthMetres - shot.idealLengthMetres)
        // Clamped to where the bat can physically go: a long stretch outside
        // off, very little outside leg, and nothing above head height. A ball
        // beyond the clamp leaves a mismatch the batter cannot close, which is
        // exactly how a wide, a play-and-miss or a bouncer past the nose happens.
        val batLine = (shot.idealLineMetres + adjust * (perceived.lineMetres - shot.idealLineMetres))
            .coerceIn(tuning.batReachLegSideMetres, tuning.batReachOffSideMetres)
        val batHeight = (shot.idealHeightMetres + adjust * (perceived.heightMetres - shot.idealHeightMetres))
            .coerceIn(tuning.batReachLowMetres, tuning.batReachHighMetres)

        // Timing error becomes a length error: a bat 12 ms early at 140 km/h is
        // most of half a metre out. This is why timing matters more against pace
        // than against spin, without anything saying so.
        val timingSigma = tuning.timingSigmaSeconds *
            (tuning.timingSkillWorst - (tuning.timingSkillWorst - tuning.timingSkillBest) * context.strikerSkill(Attribute.TIMING)) *
            (1.0 + tuning.timingPressureWeight * context.pressure) *
            (1.0 + 0.55 * exp(-context.strikerBallsFaced / context.tuning.perception.settleScaleBalls))
        val timingError = rng.nextGaussian() * timingSigma
        val metresPerSecond = ball.paceKph / 3.6
        val timingLengthOffset = timingError * metresPerSecond

        val footSkill = when (shot.foot) {
            FootMovement.FRONT -> context.strikerSkill(Attribute.FRONTFOOT_PLAY)
            FootMovement.BACK -> context.strikerSkill(Attribute.BACKFOOT_PLAY)
            FootMovement.NONE -> 0.5
        }
        val skillWidening = 1.0 +
            tuning.techniqueWeight * (context.strikerSkill(Attribute.FOOTWORK) - 0.5) +
            tuning.footworkWeight * (footSkill - 0.5)

        val scale = shot.toleranceScale * skillWidening * context.tuning.knobs.contactToleranceScale
        val tolLength = tuning.lengthTolerance * scale
        val tolLine = tuning.lineTolerance * scale
        val tolHeight = tuning.heightTolerance * scale

        val dLength = (ball.effectiveLengthMetres + timingLengthOffset - batLength) / tolLength
        val dLine = (ball.lineAtStumpsMetres - batLine) / tolLine
        val dHeight = (ball.heightAtStumpsMetres - batHeight) / tolHeight

        val distance = sqrt(dLength * dLength + dLine * dLine + dHeight * dHeight)
        val quality = exp(-0.5 * distance * distance)

        val point = classify(context, ball, shot, dLength, dLine, dHeight, distance, tuning.missThreshold)

        return Contact(
            quality = if (point == ContactPoint.MISSED || point == ContactPoint.PAD || point == ContactPoint.BODY) 0.0 else quality,
            point = point,
            lengthMismatch = dLength,
            lineMismatch = dLine,
            heightMismatch = dHeight,
            timingErrorSeconds = timingError,
        )
    }

    /**
     * Where it hit, read off the geometry.
     *
     * This table is the whole of the brief's Stage 5 list — beaten, feather,
     * thick edge, inside edge, top edge, leading edge, splice, gloves, middled —
     * derived from which way the bat missed rather than enumerated as outcomes.
     */
    private fun classify(
        context: DeliveryContext,
        ball: DeliveredBall,
        shot: Shot,
        dLength: Double,
        dLine: Double,
        dHeight: Double,
        distance: Double,
        missThreshold: Double,
    ): ContactPoint {
        if (distance > missThreshold) {
            // Beaten. Only a clear blow on the upper body is decided here -
            // whether the ball then hit the stumps, the pad, or went through to
            // the keeper is Stage 6's call, because it needs the pad and the
            // stumps weighed against each other. Deciding "pad" first here made
            // the pad intervene on every beaten ball and all but removed bowled
            // from the game.
            val bodyHigh = ball.heightAtStumpsMetres > 1.10 &&
                ball.lineAtStumpsMetres > -0.42 && ball.lineAtStumpsMetres < 0.20
            return if (bodyHigh) ContactPoint.BODY else ContactPoint.MISSED
        }

        // Inside the envelope: the largest normalised miss says where on the bat.
        val absLength = abs(dLength)
        val absLine = abs(dLine)
        val absHeight = abs(dHeight)
        val worst = maxOf(absLength, absLine, absHeight)
        if (worst < 0.85) return ContactPoint.MIDDLE

        return when (worst) {
            absLine -> if (dLine > 0) ContactPoint.OUTSIDE_EDGE else ContactPoint.INSIDE_EDGE
            absHeight ->
                if (dHeight > 0) {
                    // It bounced more than he played for: top edge off a
                    // cross-bat shot, splice or glove off a vertical one.
                    if (shot.foot == FootMovement.BACK && shot.elevationDegrees > 5.0) {
                        ContactPoint.TOP_EDGE
                    } else if (ball.heightAtStumpsMetres > 1.15) {
                        if (context.strikerSkill(Attribute.SHORT_BALL_PLAY) < 0.45) ContactPoint.GLOVE else ContactPoint.SPLICE
                    } else {
                        ContactPoint.TOP_EDGE
                    }
                } else {
                    ContactPoint.BOTTOM_EDGE
                }
            else ->
                // A length miss. A leading edge is a face-turning fault: it
                // needs a horizontal bat across the line of a ball that arrived
                // fuller than he played for. With a straight bat the same error
                // just mistimes the shot off the middle or the bottom of the
                // blade - which the contact quality already reflects.
                when {
                    dLength < 0 && shot.crossBat -> ContactPoint.LEADING_EDGE
                    dLength < 0 -> ContactPoint.BOTTOM_EDGE
                    ball.heightAtStumpsMetres > 1.05 -> ContactPoint.SPLICE
                    else -> ContactPoint.MIDDLE
                }
        }
    }
}
