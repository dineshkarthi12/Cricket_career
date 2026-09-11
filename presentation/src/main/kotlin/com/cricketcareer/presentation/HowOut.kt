package com.cricketcareer.presentation

import com.cricketcareer.engine.match.state.Dismissal
import com.cricketcareer.engine.match.state.DismissalMode

/**
 * How a batter got out, in scorecard notation.
 *
 * Small, fiddly, and the thing a cricket supporter notices first. Every mode
 * has its own shape and they are not interchangeable:
 *
 * ```
 * b Kadam                  bowled
 * c Rane b Kadam           caught
 * c & b Kadam              caught and bowled - the bowler took it himself
 * lbw b Kadam              leg before
 * st Rane b Kadam          stumped: the keeper's name, then the bowler's
 * run out (Rane)           no bowler at all
 * hit wicket b Kadam
 * not out
 * did not bat
 * ```
 *
 * The naive version - the dismissal's own short name, then "b", then the
 * bowler - produces `b b Kadam` for a bowled dismissal and `lbw b Kadam`
 * correctly, which is exactly the kind of bug that survives to release because
 * it is right three quarters of the time. This module exists so that it is
 * decided once and tested.
 */
object HowOut {

    /** A batter who is still there. */
    const val NOT_OUT: String = "not out"

    /** A batter who never got in. */
    const val DID_NOT_BAT: String = "did not bat"

    /**
     * [faced] distinguishes a batter who is unbeaten from one who never came
     * to the wicket - both have no dismissal, and a scorecard must not confuse
     * 0* off 14 balls with a man who was padded up all afternoon.
     */
    fun describe(dismissal: Dismissal?, names: Names, faced: Boolean = true): String {
        if (dismissal == null) return if (faced) NOT_OUT else DID_NOT_BAT
        // Surnames: a dismissal line reads "c Rane b Kadam", while the same
        // two men appear as "V Rane" and "M Kadam" in their own rows.
        val bowler = dismissal.bowler?.let { names.surname(it) }
        val fielder = dismissal.fielder?.let { names.surname(it) }
        return when (dismissal.mode) {
            // "b Kadam", never "b b Kadam": the mode's own name IS the "b".
            DismissalMode.BOWLED -> bowled(bowler)

            DismissalMode.CAUGHT -> when {
                // The bowler caught it himself, which has its own notation and
                // is not written "c Kadam b Kadam".
                fielder != null && fielder == bowler -> "c & b $fielder"
                fielder != null && bowler != null -> "c $fielder b $bowler"
                bowler != null -> "c b $bowler"
                else -> "caught"
            }

            DismissalMode.LBW -> if (bowler != null) "lbw b $bowler" else "lbw"

            // The keeper is credited before the bowler, and both are named.
            DismissalMode.STUMPED -> when {
                fielder != null && bowler != null -> "st $fielder b $bowler"
                bowler != null -> "st b $bowler"
                else -> "stumped"
            }

            DismissalMode.HIT_WICKET -> if (bowler != null) "hit wicket b $bowler" else "hit wicket"

            // No bowler is credited for any of these, so none of them takes a
            // "b" clause however tempting the symmetry.
            DismissalMode.RUN_OUT -> if (fielder != null) "run out ($fielder)" else "run out"

            DismissalMode.OBSTRUCTING_THE_FIELD -> "obstructing the field"
            DismissalMode.TIMED_OUT -> "timed out"
            DismissalMode.HIT_THE_BALL_TWICE -> "hit the ball twice"
            DismissalMode.RETIRED_OUT -> "retired out"
        }
    }

    private fun bowled(bowler: String?): String = if (bowler != null) "b $bowler" else "bowled"
}
