package com.cricketcareer.presentation

import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.model.world.MatchFormat

/**
 * A match being watched, one ball at a time.
 *
 * Immutable, like everything else that crosses a module boundary here: each
 * control returns a new session rather than mutating this one. That is not
 * ceremony — it is what lets a Compose screen treat the session as ordinary
 * state, and it is why the playback logic can be tested on a bare JDK instead
 * of being tangled up in a `@Composable`.
 *
 * The whole innings is simulated up front and [cursor] says how much of it the
 * viewer has been shown. Nothing is re-simulated by pressing play: the match
 * already happened, and the screen is catching up with it. That is also why
 * skipping to the end is instant and why the same session replays identically
 * however the viewer scrubs through it.
 */
data class MatchSession(
    private val balls: List<BallEvent>,
    private val format: MatchFormat,
    private val commentary: (BallEvent) -> String,
    /** Runs needed to win, in a second innings. Null in the first. */
    val target: Int? = null,
    /** Shown once the match is over. */
    val result: String? = null,
    /** How many deliveries have been shown. 0 is "before the first ball". */
    val cursor: Int = 0,
    val feedLength: Int = 6,
) {
    init {
        require(cursor in 0..balls.size) { "cursor $cursor is outside 0..${balls.size}" }
    }

    val totalBalls: Int get() = balls.size

    /** Every ball has been shown, or the chase has been won. */
    val isFinished: Boolean
        get() = cursor >= balls.size || (target != null && cursor > 0 && balls[cursor - 1].scoreAfter >= target)

    /** What the screen draws. Recomputed from the prefix, never accumulated. */
    val state: MatchCentreState
        get() = matchCentreState(
            balls = balls.take(cursor),
            format = format,
            target = target,
            feedLength = feedLength,
            result = if (isFinished) result else null,
            commentary = commentary,
        )

    /** Show one more delivery, or stay put if there are none left. */
    fun advance(deliveries: Int = 1): MatchSession {
        require(deliveries >= 0) { "cannot advance by $deliveries" }
        if (isFinished) return this
        return copy(cursor = (cursor + deliveries).coerceAtMost(balls.size))
    }

    /**
     * Run on until the next wicket falls.
     *
     * The point of the control is to skip the singles and arrive at the moment
     * the innings turned, so it stops *on* the wicket rather than after it —
     * the delivery that took it is the one worth reading.
     */
    fun toNextWicket(): MatchSession {
        if (isFinished) return this
        for (index in cursor until balls.size) {
            if (balls[index].outcome.dismissal != null) return copy(cursor = index + 1)
        }
        return toEnd()
    }

    /** Skip to the end of the innings. */
    fun toEnd(): MatchSession = copy(cursor = balls.size)

    /** Back to before the first ball. */
    fun reset(): MatchSession = copy(cursor = 0)

    companion object {
        /** An empty session, for a screen with no match loaded yet. */
        fun empty(format: MatchFormat): MatchSession =
            MatchSession(balls = emptyList(), format = format, commentary = { "" })
    }
}
