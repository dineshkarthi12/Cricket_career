package com.cricketcareer.presentation

import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.model.world.MatchFormat

/** One delivery in the live feed. */
data class FeedBall(
    /** "14.2" */
    val over: String,
    val text: String,
    /** "W", "4", "6", "1", or "•" for a dot. */
    val chip: String,
    val wicket: Boolean,
    val boundary: Boolean,
)

/** One pip in the this-over strip. */
data class OverPip(val label: String, val wicket: Boolean, val four: Boolean, val six: Boolean)

/** What the chase needs, or null in the first innings. */
data class ChaseEquation(
    val runsNeeded: Int,
    val ballsLeft: Int,
    val wicketsLeft: Int,
    /** Required runs per over. Null once the chase is over either way. */
    val requiredRate: Double?,
) {
    /** "Tamil Nadu need 72 from 34 balls" is assembled by the screen; this is the middle of it. */
    val summary: String get() = "$runsNeeded from $ballsLeft"
}

/** Everything the match centre draws. */
data class MatchCentreState(
    val score: String,
    val overs: String,
    val currentRunRate: Double?,
    val chase: ChaseEquation?,
    val thisOver: List<OverPip>,
    val overNumber: Int,
    val feed: List<FeedBall>,
    val result: String?,
)

/**
 * The live match screen.
 *
 * `balls` is the innings so far, oldest first — the caller passes a prefix of
 * the event list to replay, which is what makes "next ball" and "skip to the
 * wicket" work without re-simulating anything.
 *
 * The two numbers worth being careful about:
 *
 *  - **balls left** counts *legal* deliveries bowled, not deliveries. Counting
 *    deliveries makes a chase end a ball early for every wide, which is the
 *    sort of thing nobody notices until the last over of a tight game.
 *  - **the required rate** is per over, over the balls *remaining*. Dividing by
 *    the balls bowled gives a number that looks plausible and is wrong.
 */
fun matchCentreState(
    balls: List<BallEvent>,
    format: MatchFormat,
    target: Int? = null,
    feedLength: Int = 6,
    result: String? = null,
    // Commentary generation is the engine's - event to text is a pure function
    // of the delivery, so it belongs one module down. Presentation only decides
    // which lines are shown and in what order.
    commentary: (BallEvent) -> String,
): MatchCentreState {
    require(feedLength >= 0) { "feedLength $feedLength cannot be negative" }

    val last = balls.lastOrNull()
    val score = last?.scoreAfter ?: 0
    val wickets = last?.wicketsAfter ?: 0
    val legalBalls = balls.count { it.outcome.isLegalBall }
    val perInnings = format.ballsPerInnings

    val chase = target?.let {
        val needed = it - score
        val ballsLeft = (perInnings ?: 0) - legalBalls
        ChaseEquation(
            runsNeeded = needed,
            ballsLeft = ballsLeft,
            wicketsLeft = TEAM_SIZE - 1 - wickets,
            requiredRate = if (ballsLeft > 0 && needed > 0) {
                needed * MatchFormat.BALLS_PER_OVER.toDouble() / ballsLeft
            } else {
                null
            },
        )
    }

    // The over a scorer would be writing on now: every delivery since the last
    // completed over, wides and no-balls included, which is why this strip can
    // legitimately hold more than six pips.
    val overNumber = last?.id?.over ?: 0
    val thisOver = balls.filter { it.id.over == overNumber }.map { pipFor(it) }

    return MatchCentreState(
        score = "$score-$wickets",
        overs = oversDisplay(legalBalls),
        currentRunRate = if (legalBalls == 0) null else {
            score * MatchFormat.BALLS_PER_OVER.toDouble() / legalBalls
        },
        chase = chase,
        thisOver = thisOver,
        overNumber = overNumber + 1,
        // Newest first: a live feed reads downwards from what just happened.
        feed = balls.takeLast(feedLength).reversed().map { feedBall(it, commentary) },
        result = result,
    )
}

/** "14.2" — completed overs and legal balls into the current one. */
fun oversDisplay(legalBalls: Int): String =
    "${legalBalls / MatchFormat.BALLS_PER_OVER}.${legalBalls % MatchFormat.BALLS_PER_OVER}"

private fun feedBall(event: BallEvent, commentary: (BallEvent) -> String): FeedBall {
    val outcome = event.outcome
    val offBat = outcome.runsOffBat
    return FeedBall(
        over = event.id.toString(),
        text = commentary(event),
        chip = when {
            outcome.dismissal != null -> "W"
            outcome.totalRuns == 0 -> DOT
            else -> outcome.totalRuns.toString()
        },
        wicket = outcome.dismissal != null,
        boundary = offBat >= 4,
    )
}

private fun pipFor(event: BallEvent): OverPip {
    val outcome = event.outcome
    return OverPip(
        label = if (outcome.dismissal != null) "W" else outcome.totalRuns.toString(),
        wicket = outcome.dismissal != null,
        four = outcome.runsOffBat == 4,
        six = outcome.runsOffBat == 6,
    )
}

/** A dot ball. A typographic bullet, not the digit zero — scorers write a dot. */
private const val DOT = "•"

private const val TEAM_SIZE = 11
