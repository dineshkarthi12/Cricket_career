package com.cricketcareer.presentation

import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.AttributeGroup
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.Player
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One attribute bar. */
data class AttributeRow(val name: String, val value: Int, val fraction: Double)

/** One group of attribute bars, with the headline number the group shows. */
data class AttributeGroupRow(val name: String, val overall: Int, val rows: List<AttributeRow>)

/** The player profile screen. */
data class PlayerProfileState(
    val name: String,
    val role: String,
    val age: Int,
    val dateOfBirth: String,
    val battingStyle: String,
    val bowlingStyle: String,
    val country: String,
    val region: String,
    val groups: List<AttributeGroupRow>,
    /** What a coach would say about his ceiling. Never a number — see below. */
    val potentialDescription: String,
    val form: String,
    val fitness: String,
)

/**
 * The player profile.
 *
 * The rule this file exists to keep: **no hidden attribute reaches a screen.**
 * Potential, injury proneness, temperament, big-match factor and learning rate
 * are the things a cricketer never sees a number for, and the brief is explicit
 * about it.
 *
 * So there is no function here that returns one. [potentialDescription] is a
 * band, and a deliberately vague one — what a coach would tell him, which is
 * also the only honest answer, because nobody knows a young player's ceiling to
 * two significant figures either.
 */
fun playerProfileState(player: Player, today: LocalDate): PlayerProfileState = PlayerProfileState(
    name = player.name.full,
    role = player.role.displayName,
    age = ChronoUnit.YEARS.between(player.dateOfBirth, today).toInt(),
    dateOfBirth = player.dateOfBirth.toString(),
    battingStyle = player.battingHand.displayName,
    bowlingStyle = player.bowlingStyle.displayName,
    country = player.country,
    region = player.region,
    groups = attributeGroups(player.attributes),
    potentialDescription = describePotential(player),
    form = describeForm(player.state.form),
    fitness = describeFitness(player.state.fatigue, player.state.injury != null),
)

/**
 * Attributes grouped as the UI shows them.
 *
 * Groups appear in enum order rather than sorted by value, so a player's
 * profile does not rearrange itself as he improves. A screen whose rows move
 * between visits is one nobody can learn to read.
 */
fun attributeGroups(attributes: Attributes): List<AttributeGroupRow> =
    AttributeGroup.ALL.map { group ->
        val members = Attribute.inGroup(group)
        AttributeGroupRow(
            name = group.displayName,
            overall = Math.round(attributes.groupAverage(group)).toInt(),
            rows = members.map { attribute ->
                AttributeRow(
                    name = attribute.displayName,
                    value = attributes[attribute],
                    fraction = attributes.normalised(attribute),
                )
            },
        )
    }

/**
 * A band, never a number.
 *
 * Deliberately coarse and deliberately hedged. The player is being told what
 * his coaching staff think, and coaching staff are often wrong about
 * nineteen-year-olds.
 */
private fun describePotential(player: Player): String {
    val current = player.attributes.groupAverage(AttributeGroup.BATTING)
        .coerceAtLeast(player.attributes.groupAverage(AttributeGroup.BOWLING))
    val room = player.hidden.potential - current
    return when {
        room >= 25 -> "The staff think there is a lot more to come."
        room >= 12 -> "They believe he can go further than this."
        room >= 5 -> "About as good as he is likely to get, they reckon."
        else -> "They think this is the finished article."
    }
}

private fun describeForm(form: Double): String = when {
    form >= 0.55 -> "In excellent touch"
    form >= 0.2 -> "Going along nicely"
    form > -0.2 -> "Neither one thing nor the other"
    form > -0.55 -> "Short of runs"
    else -> "Badly out of form"
}

private fun describeFitness(fatigue: Double, injured: Boolean): String = when {
    injured -> "Unavailable"
    fatigue >= 0.75 -> "Running on empty"
    fatigue >= 0.45 -> "Feeling the workload"
    fatigue >= 0.2 -> "A little tired"
    else -> "Fresh"
}
