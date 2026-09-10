package com.cricketcareer.engine.model.player

import com.cricketcareer.engine.util.LocalDateSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Stable identifier. A `String` so the seed database can use readable ids a person can edit. */
@JvmInline
@Serializable
value class PlayerId(val value: String) {
    init {
        require(value.isNotBlank()) { "player id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A person's name, kept as parts so the UI can render "R. Kulkarni" on a
 * scorecard and "Rohan Kulkarni" on a profile, and so the generator can draw
 * given names and surnames from separate regional pools.
 */
@Serializable
data class PersonName(val given: String, val family: String) {
    init {
        require(given.isNotBlank()) { "given name must not be blank" }
        require(family.isNotBlank()) { "family name must not be blank" }
    }

    /** "Rohan Kulkarni" */
    val full: String get() = "$given $family"

    /** "R Kulkarni" — the scorecard form. */
    val scorecard: String get() = "${given.first()} $family"

    override fun toString(): String = full
}

/**
 * A cricketer — the user's or anyone else's. There is no separate type for the
 * player's own cricketer, deliberately: the moment the user's player has fields
 * or code paths that AI players do not, the simulation stops being a fair test
 * of him and every statistic becomes incomparable.
 */
@Serializable
data class Player(
    val id: PlayerId,
    val name: PersonName,
    @Serializable(with = LocalDateSerializer::class)
    val dateOfBirth: LocalDate,
    /** Country id, resolved against the seed database. */
    val country: String,
    /** Home state or region id within [country]. */
    val region: String,
    val battingHand: BattingHand,
    val bowlingStyle: BowlingStyle,
    val role: PlayerRole,
    val attributes: Attributes,
    val hidden: HiddenAttributes,
    val state: PlayerState = PlayerState.FRESH,
) {
    init {
        require(country.isNotBlank()) { "player ${id.value} has a blank country" }
        require(region.isNotBlank()) { "player ${id.value} has a blank region" }
        require(!(role.keeps && role != PlayerRole.WICKETKEEPER_BAT)) {
            "only WICKETKEEPER_BAT may keep, but ${role.name} is marked as keeping"
        }
    }

    /** Whole years old on [date]. */
    fun ageOn(date: LocalDate): Int = ChronoUnit.YEARS.between(dateOfBirth, date).toInt()

    /**
     * Age including the part-year, for growth and decline curves.
     *
     * Growth is fastest at 17-23 and reverses after 32-34; stepping a player's
     * age in whole years would make development happen in visible annual jumps
     * on his birthday.
     */
    fun exactAgeOn(date: LocalDate): Double =
        ChronoUnit.DAYS.between(dateOfBirth, date).toDouble() / DAYS_PER_YEAR

    /** Whether this player keeps wicket. */
    val keeps: Boolean get() = role.keeps

    /** Whether this player is a genuine bowling option. */
    val bowls: Boolean get() = bowlingStyle.bowls && role.bowls

    /** Convenience for the engine: an attribute on the working [0, 1] scale. */
    fun skill(attribute: Attribute): Double = attributes.normalised(attribute)

    override fun toString(): String =
        "${name.scorecard} (${battingHand.short}${if (bowlingStyle.bowls) ", ${bowlingStyle.short}" else ""})"

    companion object {
        /** Mean tropical year. Only used for fractional ages, where exactness is irrelevant. */
        const val DAYS_PER_YEAR: Double = 365.2425
    }
}
