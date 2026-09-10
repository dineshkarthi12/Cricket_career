package com.cricketcareer.engine.model.world

import com.cricketcareer.engine.model.player.PlayerId
import kotlinx.serialization.Serializable

/**
 * The rungs of the career ladder.
 *
 * [standard] is the quality of cricket played, on the same 0-1 scale as a
 * normalised attribute, and it is what the progression model compares a
 * player's own level against: growth comes from playing *above* your standard.
 *
 * [coachingQuality] is separate, and deliberately not monotonic with standard —
 * a well-run state academy can out-coach a franchise dressing room that is
 * only interested in this season.
 */
@Serializable
enum class LadderLevel(
    val displayName: String,
    val standard: Double,
    val coachingQuality: Double,
    /** Media and public attention, feeding reputation and the pressure index. */
    val attention: Double,
) {
    COLLEGE("College", standard = 0.22, coachingQuality = 0.25, attention = 0.02),
    DISTRICT_CLUB("District league", standard = 0.34, coachingQuality = 0.35, attention = 0.05),
    STATE_AGE_GROUP("State age-group", standard = 0.45, coachingQuality = 0.60, attention = 0.12),
    STATE_FIRST_CLASS("State first-class", standard = 0.62, coachingQuality = 0.65, attention = 0.25),
    STATE_WHITE_BALL("State white-ball", standard = 0.60, coachingQuality = 0.62, attention = 0.28),
    FRANCHISE_T20("Franchise T20", standard = 0.80, coachingQuality = 0.72, attention = 0.90),
    NATIONAL_A("National A", standard = 0.78, coachingQuality = 0.82, attention = 0.40),
    INTERNATIONAL("International", standard = 0.92, coachingQuality = 0.88, attention = 1.00),
    ;

    companion object {
        /** In ladder order, weakest first. */
        val ALL: List<LadderLevel> = entries.toList()
    }
}

/**
 * A team.
 *
 * Names are cities and regions only. No franchise names, no crests, no logos —
 * see the licensing note in README.md. The seed database is validated against
 * this on load.
 */
@Serializable
data class Team(
    val id: String,
    val name: String,
    val shortName: String,
    val country: String,
    val region: String,
    val level: LadderLevel,
    val homeVenue: String,
    /** Every player contracted to the team, not the XI. Selection picks from here. */
    val squad: List<PlayerId> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "team id must not be blank" }
        require(name.isNotBlank()) { "team $id has a blank name" }
        require(shortName.isNotBlank()) { "team $id has a blank short name" }
        require(homeVenue.isNotBlank()) { "team $id has no home venue" }
        require(squad.size == squad.distinct().size) {
            "team $id lists a player more than once in its squad"
        }
    }
}

/** A country, and the regions beneath it that supply its domestic teams. */
@Serializable
data class Country(
    val id: String,
    val name: String,
    val regions: List<Region> = emptyList(),
    /**
     * How this country's bowling stocks are distributed, used by the generator.
     * A subcontinental country produces more spinners; a country with hard
     * bouncy pitches produces more genuine pace.
     */
    val bowlingMix: BowlingMix = BowlingMix(),
    /** Whether the full domestic pyramid is modelled here, or only the national side. */
    val fullyModelled: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "country id must not be blank" }
        require(name.isNotBlank()) { "country $id has a blank name" }
        require(regions.map { it.id }.distinct().size == regions.size) {
            "country $id has duplicate region ids"
        }
        require(!fullyModelled || regions.isNotEmpty()) {
            "country $id is fully modelled but has no regions"
        }
    }
}

/** A state or province: the unit a domestic first-class team is built on. */
@Serializable
data class Region(
    val id: String,
    val name: String,
    /** Relative share of the country's players who come from here. */
    val playerShare: Double = 1.0,
) {
    init {
        require(id.isNotBlank()) { "region id must not be blank" }
        require(name.isNotBlank()) { "region $id has a blank name" }
        require(playerShare.isFinite() && playerShare >= 0.0) { "region $id playerShare is $playerShare" }
    }
}

/**
 * Relative frequencies of bowling types produced by a country.
 *
 * Deliberately about *supply*, not effectiveness. A country that produces many
 * spinners does so because its pitches reward them, and the pitch model already
 * handles the rewarding — encoding it twice would double-count.
 */
@Serializable
data class BowlingMix(
    val fast: Double = 1.0,
    val fastMedium: Double = 2.0,
    val medium: Double = 1.2,
    val offSpin: Double = 1.0,
    val legSpin: Double = 0.6,
    val leftArmSpin: Double = 0.7,
) {
    init {
        listOf(
            "fast" to fast, "fastMedium" to fastMedium, "medium" to medium,
            "offSpin" to offSpin, "legSpin" to legSpin, "leftArmSpin" to leftArmSpin,
        ).forEach { (name, value) ->
            require(value.isFinite() && value >= 0.0) { "bowling mix $name is $value" }
        }
        require(fast + fastMedium + medium + offSpin + legSpin + leftArmSpin > 0.0) {
            "bowling mix must have at least one positive weight"
        }
    }
}
