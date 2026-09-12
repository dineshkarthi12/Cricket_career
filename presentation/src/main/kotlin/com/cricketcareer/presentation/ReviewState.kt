package com.cricketcareer.presentation

import com.cricketcareer.engine.match.drs.BallTracking
import com.cricketcareer.engine.match.drs.Review
import com.cricketcareer.engine.match.drs.ReviewOutcome
import com.cricketcareer.engine.match.drs.ReviewingSide
import kotlin.math.abs

/**
 * One of the three lbw questions, as a screen shows it.
 *
 * A screen does not show a margin of 0.42; it shows the words on the big screen
 * at the ground. `label` is the question, `verdict` is the answer, and
 * [marginal] is what turns the answer yellow.
 */
data class ReviewFinding(
    val label: String,
    val verdict: String,
    /** True when this is the leg that made it umpire's call. */
    val marginal: Boolean,
)

/** The review, ready to draw. */
data class ReviewState(
    /** "India review" / "Australia review". Who asked. */
    val heading: String,
    val findings: List<ReviewFinding>,
    /** "Overturned", "Struck down", "Umpire's call". */
    val outcome: String,
    /** "OUT" or "NOT OUT" — the decision that stands afterwards. */
    val decision: String,
    /** Whether the decision changed, which is what the animation keys off. */
    val changed: Boolean,
    /** "2 reviews remaining" */
    val reviewsLeft: String,
) {
    /** One line, for a scorecard footnote or a feed entry. */
    val summary: String
        get() = "$heading — $outcome, $decision"
}

/**
 * The review screen.
 *
 * All three questions are shown whatever the answer, because that is what the
 * big screen does and because "pitching in line, impact in line, missing leg
 * stump" is the sentence that explains the decision. Showing only the leg that
 * failed would leave a viewer who has seen it on television wondering what the
 * other two said.
 *
 * Which leg is marked marginal matters: an lbw is only as clear as its least
 * clear question, so the umpire's-call band applies to the *weakest* of the
 * three and never to more than one.
 */
fun reviewState(
    review: Review,
    battingTeam: String,
    fieldingTeam: String,
    umpiresCallBand: Double,
): ReviewState {
    val tracking = review.tracking
    val weakest = tracking.outMargin
    val umpiresCall = review.outcome == ReviewOutcome.UMPIRES_CALL

    fun finding(label: String, margin: Double, yes: String, no: String) = ReviewFinding(
        label = label,
        verdict = if (margin > 0.0) yes else no,
        // Only the question that decided it is marked, and only when the
        // verdict actually came back umpire's call.
        marginal = umpiresCall && margin == weakest && abs(margin) < umpiresCallBand,
    )

    return ReviewState(
        heading = when (review.by) {
            ReviewingSide.BATTING -> "$battingTeam review"
            ReviewingSide.FIELDING -> "$fieldingTeam review"
        },
        findings = listOf(
            finding("Pitching", tracking.pitchingMargin, "In line", "Outside leg"),
            finding("Impact", tracking.impactMargin, "In line", "Outside line"),
            finding("Wickets", tracking.wicketsMargin, "Hitting", "Missing"),
        ),
        outcome = review.outcome.displayName,
        decision = if (review.isOut) "OUT" else "NOT OUT",
        changed = review.changedTheDecision,
        reviewsLeft = when (review.reviewsLeft) {
            1 -> "1 review remaining"
            else -> "${review.reviewsLeft} reviews remaining"
        },
    )
}

/** The tracking on its own, for a replay a screen draws before the verdict. */
fun trackingFindings(tracking: BallTracking): List<ReviewFinding> = listOf(
    ReviewFinding("Pitching", if (tracking.pitchingMargin > 0) "In line" else "Outside leg", false),
    ReviewFinding("Impact", if (tracking.impactMargin > 0) "In line" else "Outside line", false),
    ReviewFinding("Wickets", if (tracking.wicketsMargin > 0) "Hitting" else "Missing", false),
)
