package com.cricketcareer.engine.match.state

import com.cricketcareer.engine.model.player.PlayerId
import kotlinx.serialization.Serializable

/**
 * How a wicket fell, and who gets the credit.
 *
 * [creditedToBowler] is a Law, not a preference: a run out, an obstruction or a
 * timed-out dismissal costs the batting side a wicket but does not appear in
 * anyone's bowling figures. Getting this wrong quietly corrupts every career
 * bowling average in the game.
 */
@Serializable
enum class DismissalMode(val displayName: String, val creditedToBowler: Boolean) {
    BOWLED("b", creditedToBowler = true),
    CAUGHT("c", creditedToBowler = true),
    LBW("lbw", creditedToBowler = true),
    STUMPED("st", creditedToBowler = true),
    HIT_WICKET("hit wicket", creditedToBowler = true),
    RUN_OUT("run out", creditedToBowler = false),
    OBSTRUCTING_THE_FIELD("obstructing the field", creditedToBowler = false),
    TIMED_OUT("timed out", creditedToBowler = false),
    HIT_THE_BALL_TWICE("hit the ball twice", creditedToBowler = false),
    RETIRED_OUT("retired out", creditedToBowler = false),
    ;

    /** Retired hurt is not a dismissal; it is handled separately from this enum. */
    val isWicket: Boolean get() = true
}

/** A wicket. [batterOut] is named explicitly because a run out can take the non-striker. */
@Serializable
data class Dismissal(
    val mode: DismissalMode,
    val batterOut: PlayerId,
    val bowler: PlayerId?,
    /** Catcher, stumper, or the fielder who effected the run out. */
    val fielder: PlayerId? = null,
) {
    init {
        if (mode.creditedToBowler) {
            requireNotNull(bowler) { "$mode must name the bowler who gets the wicket" }
        }
        if (mode == DismissalMode.CAUGHT || mode == DismissalMode.STUMPED) {
            requireNotNull(fielder) { "$mode must name the fielder" }
        }
    }
}

/**
 * The scoring result of one delivery.
 *
 * This is the *accounting* view of a ball — what the scorer writes down. Phase
 * 2's `BallEvent` carries the full causal chain and contains one of these; the
 * bookkeeping in [InningsState] needs only this much, which keeps the scorer
 * testable long before there is a simulation to feed it.
 *
 * The Laws encoded here, each of which is easy to get subtly wrong:
 *  - A wide is not a ball faced and not a legal ball. A no-ball IS a ball faced
 *    (the batter had to play it) but is not a legal ball.
 *  - Byes and leg byes count against the team but not against the bowler, and
 *    do not spoil a maiden. Wides and no-balls do both.
 *  - Runs run on a wide beyond the first are further wides, not run credit.
 */
@Serializable
data class DeliveryOutcome(
    /** Runs scored off the bat and credited to the striker. Always 0 on a wide. */
    val runsOffBat: Int = 0,

    /** Total wide runs including the one-run penalty. 0 when the ball was not a wide. */
    val wides: Int = 0,

    /** Whether this was a no-ball. Adds [NO_BALL_PENALTY] to the team and to the bowler. */
    val noBall: Boolean = false,

    /** Byes: the ball beat bat and keeper and the batters ran. Not charged to the bowler. */
    val byes: Int = 0,

    /** Leg byes: off the body. Not charged to the bowler. */
    val legByes: Int = 0,

    /** Five-run and other penalties awarded to the batting side. */
    val penaltyRuns: Int = 0,

    val dismissal: Dismissal? = null,

    /**
     * Whether the batters crossed before the dismissal completed.
     *
     * Only relevant when the striker is caught: if they crossed, the new batter
     * comes in at the non-striker's end. Ignored otherwise.
     */
    val battersCrossed: Boolean = false,
) {
    init {
        require(runsOffBat >= 0) { "runsOffBat $runsOffBat cannot be negative" }
        require(wides >= 0) { "wides $wides cannot be negative" }
        require(byes >= 0) { "byes $byes cannot be negative" }
        require(legByes >= 0) { "legByes $legByes cannot be negative" }
        require(penaltyRuns >= 0) { "penaltyRuns $penaltyRuns cannot be negative" }
        require(!(wides > 0 && runsOffBat > 0)) {
            "a wide cannot be scored off the bat; runs run on a wide are further wides"
        }
        require(!(wides > 0 && (byes > 0 || legByes > 0))) {
            "runs on a wide are recorded as wides, not as byes or leg byes"
        }
        require(!(byes > 0 && legByes > 0)) { "a delivery yields byes or leg byes, never both" }
        require(!(runsOffBat > 0 && (byes > 0 || legByes > 0))) {
            "a ball hit off the bat cannot also yield byes or leg byes"
        }
        require(!(wides > 0 && noBall)) { "a delivery cannot be both a wide and a no-ball" }
    }

    /** Does this count towards the over? Wides and no-balls do not. */
    val isLegalBall: Boolean get() = wides == 0 && !noBall

    /** Does the striker have this recorded as a ball faced? A no-ball yes, a wide no. */
    val isBallFaced: Boolean get() = wides == 0

    /** Total runs added to the batting side's score. */
    val totalRuns: Int
        get() = runsOffBat + wides + noBallPenalty + byes + legByes + penaltyRuns

    /** Runs charged to the bowler. Byes, leg byes and penalties are not his fault. */
    val runsChargedToBowler: Int get() = runsOffBat + wides + noBallPenalty

    /** Runs the batters physically ran or hit, which decides who is on strike next. */
    val runsRun: Int get() = runsOffBat + byes + legByes + (wides - 1).coerceAtLeast(0)

    private val noBallPenalty: Int get() = if (noBall) NO_BALL_PENALTY else 0

    companion object {
        /** One run. Some competitions use two; that would be a format parameter. */
        const val NO_BALL_PENALTY: Int = 1

        /** A dot ball. */
        val DOT: DeliveryOutcome = DeliveryOutcome()

        /** [runs] off the bat. */
        fun offBat(runs: Int): DeliveryOutcome = DeliveryOutcome(runsOffBat = runs)

        /** A wide, plus [additionalRun]s run or overthrown beyond the penalty. */
        fun wide(additionalRuns: Int = 0): DeliveryOutcome = DeliveryOutcome(wides = 1 + additionalRuns)

        /** A no-ball, with [runs] scored off the bat from it. */
        fun noBall(runs: Int = 0): DeliveryOutcome = DeliveryOutcome(noBall = true, runsOffBat = runs)
    }
}
