package com.cricketcareer.engine.match.drs

/**
 * What the third umpire sees, for one lbw appeal.
 *
 * The three questions, each as a **signed margin** rather than a yes or no:
 * positive means the condition is satisfied and by how clearly, negative means
 * it is not. That is the whole point — "pitched in line" and "pitched in line by
 * two millimetres" are different facts, and only the second explains umpire's
 * call.
 *
 * One unit is one tolerance width, the same units Stage 6 works in.
 */
data class BallTracking(
    /** Positive when it did not pitch outside leg. */
    val pitchingMargin: Double,
    /** Positive when impact was in line, or outside off with no stroke offered. */
    val impactMargin: Double,
    /** Positive when it was going on to hit. */
    val wicketsMargin: Double,
) {
    /**
     * How clearly the batter is out, as one number: the weakest of the three.
     *
     * Negative means not out, and the size says how obviously. A chain is as
     * strong as its weakest link and an lbw is as clear as its least clear
     * question, which is why one marginal leg is enough for umpire's call even
     * when the other two are plumb.
     */
    val outMargin: Double get() = minOf(pitchingMargin, impactMargin, wicketsMargin)

    /** What the technology says, ignoring umpire's call. */
    val isOut: Boolean get() = outMargin > 0.0
}
