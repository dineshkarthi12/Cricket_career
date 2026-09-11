package com.cricketcareer.engine.commentary

import com.cricketcareer.engine.match.delivery.ContactPoint
import com.cricketcareer.engine.match.delivery.DeliveryType
import com.cricketcareer.engine.match.delivery.LengthBand
import com.cricketcareer.engine.match.delivery.LineBand
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.state.DismissalMode
import com.cricketcareer.engine.model.player.PlayerId
import kotlin.math.abs

/**
 * Turns a delivery into a sentence.
 *
 * The rule the brief sets, and the reason this class is not a lookup table:
 * **the commentary is assembled from what actually happened, never chosen by
 * outcome.** "Thick outside edge, flew at catchable height through a vacant
 * fourth slip, four" is four separate facts read off the [BallEvent] — the
 * contact point, the trajectory's elevation, the absence of a fielder on that
 * bearing, and the runs. A canned string picked by "four" could not say any of
 * it, and would say the same thing about a slog to cow corner.
 *
 * Pure: it reads an event and a name lookup and returns text. That is why it
 * lives in :engine rather than in the UI.
 */
class CommentaryGenerator(private val names: (PlayerId) -> String) {

    fun describe(event: BallEvent): String {
        val outcome = outcomePhrase(event)
        // "and ..." continues the sentence; anything else starts a new clause.
        val join = if (outcome.startsWith("and ")) " " else ", "
        return "${event.id}  ${names(event.bowler)} to ${names(event.striker)}, " +
            deliveryPhrase(event) + shotPhrase(event) + join + outcome
    }

    /** What the bowler actually bowled — length, line and, when it is not a stock ball, what kind. */
    private fun deliveryPhrase(event: BallEvent): String {
        val ball = event.delivered
        val length = when {
            ball.isFullToss -> "a full toss"
            else -> ball.lengthBand.displayName.let { if (it.startsWith("a ")) it else "$it" }
        }
        val line = when (ball.lineBand) {
            LineBand.DOWN_LEG -> "down the leg side"
            LineBand.LEG_STUMP -> "on leg stump"
            LineBand.MIDDLE -> "on middle"
            LineBand.OFF_STUMP -> "on off stump"
            LineBand.FOURTH_STUMP -> "just outside off"
            LineBand.CHANNEL -> "in the channel"
            LineBand.WIDE_OUTSIDE_OFF -> "wide outside off"
        }
        val variation = when (event.intent.deliveryType) {
            DeliveryType.STOCK -> ""
            DeliveryType.BOUNCER -> ", the bouncer"
            else -> ", the ${event.intent.deliveryType.displayName}"
        }
        // Movement is only worth mentioning when there was some.
        val movement = movementPhrase(event)
        return "$length $line$variation$movement, "
    }

    private fun movementPhrase(event: BallEvent): String {
        val ball = event.delivered
        val swing = abs(ball.swingMetres)
        val drift = abs(ball.driftMetres)
        val seam = abs(ball.seamMetres)
        val turn = abs(ball.turnMetres)
        val biggest = maxOf(swing, drift, seam, turn)
        if (biggest < NOTABLE_MOVEMENT_M) return ""
        val strength = if (biggest > BIG_MOVEMENT_M) "a long way" else "a touch"
        // Turn is named before drift, and drift is never called swing: a
        // spinner does not swing the ball, and saying so would be the exact
        // failure this class exists to avoid.
        return when (biggest) {
            turn -> if (ball.turnMetres > 0) ", turning away $strength" else ", turning in $strength"
            swing -> if (ball.swingMetres > 0) ", swinging away $strength" else ", swinging in $strength"
            seam -> if (ball.seamMetres > 0) ", seaming away $strength" else ", nipping back $strength"
            else -> if (ball.driftMetres > 0) ", drifting away $strength" else ", drifting in $strength"
        }
    }

    /** What the batter tried, and what the bat did about it. */
    private fun shotPhrase(event: BallEvent): String {
        val shot = event.shot.shot
        val contact = event.contact.point
        if (!shot.makesContact) {
            return if (contact == ContactPoint.PAD) "left alone and struck on the pad" else "shouldered arms"
        }
        return when (contact) {
            ContactPoint.MISSED -> "beaten playing the ${shot.displayName}"
            ContactPoint.PAD -> "struck on the pad going for the ${shot.displayName}"
            ContactPoint.BODY -> "thumped into the body"
            ContactPoint.MIDDLE -> {
                val quality = event.contact.quality
                when {
                    quality > 0.85 -> "middled, the ${shot.displayName}"
                    quality > 0.55 -> "the ${shot.displayName}"
                    else -> "mistimed, the ${shot.displayName}"
                }
            }
            ContactPoint.OUTSIDE_EDGE -> {
                val thickness = if (abs(event.contact.lineMismatch) > THICK_EDGE) "a thick" else "a thin"
                "$thickness outside edge"
            }
            ContactPoint.INSIDE_EDGE -> "an inside edge"
            ContactPoint.TOP_EDGE -> "a top edge"
            ContactPoint.BOTTOM_EDGE -> "off the bottom of the bat"
            ContactPoint.LEADING_EDGE -> "a leading edge"
            ContactPoint.SPLICE -> "off the splice"
            ContactPoint.GLOVE -> "off the glove"
        }
    }

    /** Where it went and what it was worth. */
    private fun outcomePhrase(event: BallEvent): String {
        val outcome = event.outcome
        val dismissal = outcome.dismissal
        if (dismissal != null) {
            return when (dismissal.mode) {
                DismissalMode.BOWLED -> "and it crashes into the stumps. BOWLED."
                DismissalMode.LBW -> "and the finger goes up. LBW."
                DismissalMode.CAUGHT -> {
                    val where = event.fielding?.nearestFielder?.position?.displayName ?: "a fielder"
                    val height = event.trajectory?.let {
                        if (it.carryMetres > DEEP_CATCH_M) "held in the deep" else "taken"
                    } ?: "taken"
                    "$height by $where. CAUGHT."
                }
                DismissalMode.STUMPED -> "and he is out of his ground. STUMPED."
                DismissalMode.RUN_OUT -> "and the throw beats him in. RUN OUT."
                else -> "${dismissal.mode.displayName}. OUT."
            }
        }

        if (outcome.wides > 0) return "too far down the leg side to reach. Wide."
        if (outcome.noBall) {
            return "and he has overstepped. No ball, " + runsText(outcome.runsOffBat) + "."
        }
        if (outcome.byes > 0) return "past the keeper, ${outcome.byes} bye${plural(outcome.byes)}."
        if (outcome.legByes > 0) return "off the pad, ${outcome.legByes} leg bye${plural(outcome.legByes)}."

        val fielding = event.fielding
        return when {
            fielding?.clearedBoundary == true -> "and that is out of the ground. SIX."
            fielding?.reachedBoundary == true -> "and it races away to the rope. FOUR."
            fielding?.dropped == true ->
                "put down by ${fielding.nearestFielder?.position?.displayName ?: "the fielder"}. Dropped, " +
                    runsText(outcome.runsOffBat) + "."
            outcome.runsOffBat == 0 -> {
                val stopper = fielding?.nearestFielder?.position?.displayName
                if (stopper != null) "straight to $stopper, no run." else "no run."
            }
            else -> {
                val gap = fielding?.nearestFielder?.position?.displayName
                if (gap != null) "to $gap, ${runsText(outcome.runsOffBat)}." else "${runsText(outcome.runsOffBat)}."
            }
        }
    }

    private fun runsText(runs: Int): String = when (runs) {
        0 -> "no run"
        1 -> "one run"
        2 -> "two runs"
        3 -> "three runs"
        else -> "$runs runs"
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"

    private companion object {
        /** Lateral movement worth remarking on, in metres. */
        const val NOTABLE_MOVEMENT_M = 0.09
        const val BIG_MOVEMENT_M = 0.24

        /** Line mismatch above which an outside edge is a thick one. */
        const val THICK_EDGE = 1.1

        /** Carry beyond which a catch was taken in the deep. */
        const val DEEP_CATCH_M = 45.0
    }
}
