package com.cricketcareer.presentation

import com.cricketcareer.engine.career.SelectionScore
import com.cricketcareer.engine.model.player.PlayerId

/** One line of the reasoning behind a selection. */
data class SelectionReason(
    val label: String,
    /** The contribution, positive or negative, already weighted. */
    val value: Double,
    /** "+0.34" / "-0.12" */
    val display: String,
    val helps: Boolean,
)

/** One name on the team sheet, or one left off it. */
data class SelectionRow(
    val player: PlayerId,
    val name: String,
    val role: String,
    val picked: Boolean,
    /** 1-based, across the whole squad. */
    val rank: Int,
    val reasons: List<SelectionReason>,
)

/** Everything the selection screen draws. */
data class SelectionState(
    val rows: List<SelectionRow>,
    /**
     * The sentence a player actually wants: why *he* is or is not in the side.
     *
     * Null when he was not under consideration at all — injured, or not in the
     * squad — because "you were ranked nowhere" is not an explanation.
     */
    val verdict: String?,
    /** The best player left out. A selection story is made of this. */
    val unluckiest: SelectionRow?,
)

/**
 * The selection screen.
 *
 * The point is that a player is never told "not selected" and left to guess.
 * The panel's own terms are shown, weighted, in the order that decided it, so
 * "you were sixth-best on ability and the side needed a fourth seamer" is
 * readable off the screen rather than inferred from a number.
 *
 * Nothing here re-decides anything. The scores come from the same
 * `Selection.score` the panel ran; a screen that recomputed them with its own
 * weights would eventually disagree with the team sheet, and the disagreement
 * would be invisible.
 */
fun selectionState(
    ranked: List<SelectionScore>,
    pickedIds: Set<PlayerId>,
    subject: PlayerId? = null,
): SelectionState {
    // Suitability is not centred on a half. For a bowler it is, but for a
    // batter it is how well *he* plays what the pitch will do, which carries
    // his ability in it - so showing "suitability minus 0.5" labelled every
    // good player as suited to every surface. The conditions effect is the part
    // that separates him from the rest of the candidates for this match, so
    // that is what the screen shows.
    val averageSuitability = if (ranked.isEmpty()) NEUTRAL else ranked.map { it.suitability }.average()

    val rows = ranked.mapIndexed { index, score ->
        SelectionRow(
            player = score.player.id,
            name = score.player.name.scorecard,
            role = score.player.role.displayName,
            picked = score.player.id in pickedIds,
            rank = index + 1,
            reasons = reasons(score, averageSuitability),
        )
    }

    val mine = rows.firstOrNull { it.player == subject }
    // The best player left out. Not simply the highest-ranked omission: a side
    // that had to leave out its third-best batter to fit a fourth seamer in is
    // the story, and the highest-ranked omission is exactly that man.
    val unluckiest = rows.firstOrNull { !it.picked }

    return SelectionState(
        rows = rows,
        verdict = mine?.let { verdict(it, rows) },
        unluckiest = unluckiest,
    )
}

private fun verdict(mine: SelectionRow, rows: List<SelectionRow>): String {
    val xi = rows.count { it.picked }
    return when {
        mine.picked && mine.rank <= 3 -> "Picked — one of the first names on the sheet."
        mine.picked -> "Picked, ranked ${mine.rank} of ${rows.size}."
        // Ranked inside the XI and still left out means balance did it: the
        // side could not afford his absence less than somebody else's.
        mine.rank <= xi -> "Left out despite ranking ${mine.rank}. The side needed the balance elsewhere."
        mine.rank <= xi + 3 -> "Left out, ranked ${mine.rank} of ${rows.size}. Close."
        else -> "Left out, ranked ${mine.rank} of ${rows.size}."
    }
}

/**
 * The panel's terms, weighted and in the order that decided it.
 *
 * Ability first because it is the largest term and should be; risk last because
 * it is the only one that subtracts, and a reader scanning down the list should
 * meet the reason he was marked *down* at the end of it.
 */
private fun reasons(score: SelectionScore, averageSuitability: Double): List<SelectionReason> = listOf(
    reason("Ability for this format", score.standard),
    reason("Recent form", score.form),
    reason("Suits these conditions", score.suitability - averageSuitability),
    reason("Standing in the side", score.reputation),
    // Always shown, even at zero. "No fitness concern" is an answer a player
    // wants, and a line that vanishes when it is fine reads as an omission.
    reason("Fitness and sharpness", -score.risk),
).filterIndexed { index, entry -> index == RISK_LINE || entry.value != 0.0 }

/** The last line, which is shown whatever its value. */
private const val RISK_LINE = 4

/** Where a suitability sits when nothing is known about the cohort. */
private const val NEUTRAL = 0.5

private fun reason(label: String, value: Double) = SelectionReason(
    label = label,
    value = value,
    display = (if (value >= 0) "+" else "") + "%.2f".format(value),
    helps = value >= 0.0,
)
