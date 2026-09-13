package com.cricketcareer.presentation

import com.cricketcareer.engine.career.Difficulty
import com.cricketcareer.engine.save.SaveIndex

/** One career on the "load career" screen. */
data class CareerSlotRow(
    val id: String,
    val name: String,
    val playerName: String,
    /** "State first-class, Southern" — where he is, not where he started. */
    val standing: String,
    val difficulty: String,
    /** "3 seasons - 41 matches - 1,204 runs at 34.40" */
    val summary: String,
    val date: String,
    val isCurrent: Boolean,
    val isRetired: Boolean,
)

/** One difficulty on the new-career screen, with what it actually changes. */
data class DifficultyChoice(
    val id: String,
    val name: String,
    val description: String,
    val isDefault: Boolean,
)

/** The career-select screen. */
data class CareerSelectState(
    val careers: List<CareerSlotRow>,
    val difficulties: List<DifficultyChoice>,
    /** Shown instead of the list when there is nothing to load. */
    val emptyMessage: String?,
)

/**
 * Choosing a career, and choosing how hard the next one will be.
 *
 * The difficulty descriptions here are the one place the game tells the player
 * what the setting does, and they say it plainly — including the part that
 * matters most, which is what it does *not* do. A player who suspects the game
 * is shading his attributes on Elite has no reason to trust any number it shows
 * him afterwards, and the statistics are the product.
 */
fun careerSelectState(index: SaveIndex): CareerSelectState {
    val rows = index.ordered().map { career ->
        CareerSlotRow(
            id = career.id,
            name = career.name,
            playerName = career.playerName,
            standing = "${career.level.displayName}, ${career.teamId}",
            difficulty = career.difficulty.displayName,
            summary = summarise(career.seasonsPlayed, career.matches, career.runs, career.battingAverage),
            date = career.today.toString(),
            isCurrent = career.id == index.currentId,
            isRetired = career.isRetired,
        )
    }
    return CareerSelectState(
        careers = rows,
        difficulties = Difficulty.entries.map {
            DifficultyChoice(
                id = it.name,
                name = it.displayName,
                description = describe(it),
                isDefault = it == Difficulty.DEFAULT,
            )
        },
        emptyMessage = if (rows.isEmpty()) "No careers yet. Start one." else null,
    )
}

/**
 * "3 seasons · 41 matches · 1,204 runs at 34.40".
 *
 * Singulars matter here: "1 seasons" on the screen a player sees first is the
 * kind of thing that makes a whole game feel unfinished.
 */
private fun summarise(seasons: Int, matches: Int, runs: Int, average: Double?): String {
    val parts = mutableListOf(
        plural(seasons, "season"),
        plural(matches, "match", "matches"),
    )
    parts += when {
        matches == 0 -> "yet to play"
        average == null -> "${grouped(runs)} runs, not out every time"
        else -> "${grouped(runs)} runs at %.2f".format(average)
    }
    return parts.joinToString(" - ")
}

private fun plural(n: Int, singular: String, plural: String = singular + "s"): String =
    if (n == 1) "1 $singular" else "$n $plural"

/** 1204 -> "1,204". A career total is read, not parsed. */
internal fun grouped(n: Int): String {
    val digits = n.toString()
    if (digits.length <= 3) return digits
    return digits.reversed().chunked(3).joinToString(",").reversed()
}

private fun describe(difficulty: Difficulty): String = when (difficulty) {
    Difficulty.AMATEUR ->
        "Selectors give you the benefit of the doubt, the opposition is weaker, " +
            "you break down less and you improve faster."
    Difficulty.PROFESSIONAL ->
        "The game as it is measured. Everything the manual says about averages and " +
            "strike rates was measured here."
    Difficulty.ELITE ->
        "A place has to be taken. Selectors back the man in possession, the opposition " +
            "is stronger, bodies break more often and one bad run lasts longer."
} + " Nothing about the cricket itself changes: the ball does not swing further and " +
    "your attributes are not touched."
