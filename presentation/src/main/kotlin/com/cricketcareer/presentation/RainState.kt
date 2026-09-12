package com.cricketcareer.presentation

import com.cricketcareer.engine.match.state.MatchInterruption

/** One stoppage, as a match centre reports it. */
data class StoppageLine(
    /** "Rain stopped play — 36 overs, 180/2" */
    val headline: String,
    /** "Innings reduced to 46 overs" */
    val overs: String,
    /** "Target revised to 292", or null in a first innings. */
    val target: String?,
    /** "Required rate 6.25 → 5.00" — both sides of it, or null. */
    val requiredRate: String?,
)

/** Everything a rain-affected match has to explain. */
data class RainState(
    val stoppages: List<StoppageLine>,
    /** "DLS target: 292 from 46 overs", or null in a match nobody rained on. */
    val targetLine: String?,
) {
    val wasAffected: Boolean get() = stoppages.isNotEmpty()
}

/**
 * The rain panel.
 *
 * Exists because a revised target is a number nobody can account for without
 * it. "Seven overs lost, target revised to 292" is the whole story, and a
 * scoreboard that shows 292 against an opposition score of 322 and says nothing
 * else looks broken.
 *
 * The required rate is shown on both sides of the stoppage on purpose: it is
 * the number that tells a player whether the rain helped him or hurt him, and
 * it goes both ways. A side well ahead of par is asked for *less* per over
 * afterwards, which is why a captain in that position wants the covers on.
 */
fun rainState(
    interruptions: List<MatchInterruption>,
    finalTarget: Int? = null,
    finalOvers: Int? = null,
): RainState = RainState(
    stoppages = interruptions.map { stoppage ->
        StoppageLine(
            headline = "Rain stopped play — ${stoppage.afterOvers} overs, " +
                "${stoppage.runs}/${stoppage.wicketsLost}",
            overs = "Innings reduced to ${stoppage.oversAfter} overs " +
                "(${stoppage.oversLost} lost)",
            target = stoppage.revisedTarget?.let { "Target revised to $it" },
            requiredRate = rateLine(stoppage),
        )
    },
    targetLine = if (finalTarget == null || finalOvers == null) {
        null
    } else {
        "DLS target: $finalTarget from $finalOvers overs"
    },
)

private fun rateLine(stoppage: MatchInterruption): String? {
    val before = stoppage.requiredRateBefore() ?: return null
    val after = stoppage.requiredRateAfter() ?: return null
    return "Required rate %.2f → %.2f".format(before, after)
}
