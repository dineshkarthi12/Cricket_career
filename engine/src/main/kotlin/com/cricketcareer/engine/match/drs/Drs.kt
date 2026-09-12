package com.cricketcareer.engine.match.drs

import com.cricketcareer.engine.config.DrsTuning
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.rng.SimRandom

/** Which side went upstairs. */
enum class ReviewingSide(val displayName: String) {
    BATTING("Batting side"),
    FIELDING("Fielding side"),
}

/** How a review came back. */
enum class ReviewOutcome(val displayName: String) {
    /** The technology disagreed with the umpire. The decision changes. */
    OVERTURNED("Overturned"),

    /** The technology agreed. The decision stands and the review is gone. */
    STRUCK_DOWN("Struck down"),

    /**
     * Too close to call. The on-field decision stands and the review is kept.
     *
     * The most misunderstood rule in cricket and the most important one here: it
     * is what stops a marginal lbw being a coin flip, and it is why a side can
     * be "out" at one end and "not out" at the other on identical tracking.
     */
    UMPIRES_CALL("Umpire's call"),
}

/** One review, kept so a scorecard can say what happened and why. */
data class Review(
    val by: ReviewingSide,
    val outcome: ReviewOutcome,
    val tracking: BallTracking,
    /** The decision before the review, and after it. */
    val wasOut: Boolean,
    val isOut: Boolean,
    /** Reviews the reviewing side has left afterwards. */
    val reviewsLeft: Int,
) {
    val changedTheDecision: Boolean get() = wasOut != isOut
}

/**
 * Whether a side goes upstairs, and what comes back.
 *
 * Two separate models, and keeping them separate is the point:
 *
 * 1. **What the ball did.** [BallTracking], measured in Stage 6. Not a
 *    judgement — it is what a camera would show.
 * 2. **What the players thought it did.** A batter halfway down the pitch and a
 *    bowler in his follow-through are both guessing, and they guess worse lower
 *    down the ladder. If sides could see the truth they would never waste a
 *    review, and holding the last one into the final session would carry no
 *    tension at all.
 *
 * Draws come from the review stream, so adding this could not move a single
 * ball of any existing regression baseline.
 *
 * See docs/SIMULATION_MODEL.md §13.
 */
object Drs {

    /** Whether the technology exists at this rung at all. */
    fun availableAt(level: LadderLevel, tuning: DrsTuning): Boolean =
        level.standard >= tuning.minimumLevelStandard

    /**
     * Consider a review of an lbw decision, and adjudicate it if one is taken.
     *
     * Returns null when nobody goes upstairs — no reviews left, nothing at this
     * level, or neither side believes the umpire got it wrong.
     *
     * [judgement] is how well the reviewing side reads the ball, 0 to 1. The
     * level's standard is the natural thing to pass; a specific player's
     * cricket brain would be better still.
     */
    fun consider(
        onFieldOut: Boolean,
        tracking: BallTracking,
        reviewsRemaining: Int,
        judgement: Double,
        desperate: Boolean,
        random: SimRandom,
        tuning: DrsTuning = DrsTuning(),
    ): Review? {
        if (reviewsRemaining <= 0) return null

        // Whoever the decision went against is the side that can review it.
        val by = if (onFieldOut) ReviewingSide.BATTING else ReviewingSide.FIELDING

        // What that side thinks it saw.
        //
        // Not one noise for all three questions. A batter knows where he was
        // hit and whether it pitched outside leg, because he felt it; nobody
        // knows whether it was going on to hit, which is the question the
        // technology was invented for. Reading all three equally badly made
        // reviews almost entirely noise on marginal wicket-hitting, and three
        // in four came back umpire's call.
        val sigma = tuning.perceptionSigmaWorst -
            (tuning.perceptionSigmaWorst - tuning.perceptionSigmaBest) * judgement.coerceIn(0.0, 1.0)
        val positionSigma = sigma * tuning.positionSigmaScale
        val perceived = minOf(
            tracking.pitchingMargin + random.nextGaussian() * positionSigma,
            tracking.impactMargin + random.nextGaussian() * positionSigma,
            tracking.wicketsMargin + random.nextGaussian() * sigma,
        )

        // A side reviews when it believes the umpire is wrong by enough to be
        // worth a resource - and "enough" has to clear the umpire's-call band,
        // because a decision that comes back umpire's call has cost a review's
        // worth of time and changed nothing. Captains say this out loud on the
        // stump mic: "it's going to be umpire's call, don't."
        //
        // Without that, sides reviewed everything marginal and over half the
        // reviews came back umpire's call, against about a quarter in real
        // cricket. Threshold, not a coin flip: hope is not a review.
        val threshold = tuning.umpiresCallBand + tuning.reviewThreshold -
            if (desperate) tuning.desperationBonus else 0.0
        val believesWrong = if (onFieldOut) -perceived else perceived
        if (believesWrong < threshold) return null

        return adjudicate(onFieldOut, tracking, by, reviewsRemaining, tuning)
    }

    /**
     * What the third umpire decides, given that a review has been taken.
     *
     * Separated from [consider] because it is the half with no judgement in it:
     * the same tracking always comes back the same way, whoever asked and for
     * whatever reason.
     */
    fun adjudicate(
        onFieldOut: Boolean,
        tracking: BallTracking,
        by: ReviewingSide,
        reviewsRemaining: Int,
        tuning: DrsTuning = DrsTuning(),
    ): Review {
        val marginal = kotlin.math.abs(tracking.outMargin) < tuning.umpiresCallBand

        val outcome = when {
            marginal -> ReviewOutcome.UMPIRES_CALL
            tracking.isOut == onFieldOut -> ReviewOutcome.STRUCK_DOWN
            else -> ReviewOutcome.OVERTURNED
        }

        val isOut = if (outcome == ReviewOutcome.OVERTURNED) tracking.isOut else onFieldOut

        // A review survives an overturn and an umpire's call, and is lost only
        // when the technology agreed with the umpire all along.
        val left = if (outcome == ReviewOutcome.STRUCK_DOWN) reviewsRemaining - 1 else reviewsRemaining

        return Review(
            by = by,
            outcome = outcome,
            tracking = tracking,
            wasOut = onFieldOut,
            isOut = isOut,
            reviewsLeft = left,
        )
    }
}
