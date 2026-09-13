package com.cricketcareer.engine.config

/**
 * When a cricketer stops.
 *
 * Distinct from `WorldAgeingTuning`'s retirement, and deliberately so. The rest
 * of the world retires *to* the player: a hazard roll behind the scenes, a name
 * off a squad list. He retires by **decision**, and a decision needs reasons he
 * can read. The same numbers would not do for both jobs — one is a population
 * model and the other is the last screen of a career.
 *
 * Each term below is a pressure. They add up, and the sum is how close he is to
 * walking away.
 *
 * See docs/CAREER_MODEL.md §12.
 */
data class RetirementTuning(
    /** Nobody considers stopping before this. */
    val earliestAge: Int = 30,

    /** By this age everybody has, whatever is left in the tank. */
    val latestAge: Int = 44,

    /**
     * Pressure from age alone, per year past [earliestAge].
     *
     * The slowest of the pressures and the only one nobody escapes. On its own
     * it would take a player to about forty before he thought seriously about
     * it, which is roughly right for somebody still worth his place.
     */
    val agePressure: Double = 0.035,

    /**
     * Pressure from having been better once, at a full loss of everything he
     * had at his peak.
     *
     * The one that ends most careers. A player is not driven out by birthdays;
     * he is driven out by being unable to do what he could do, and knowing it.
     */
    val declinePressure: Double = 1.10,

    /**
     * Pressure from not being picked, per season without cricket.
     *
     * Sharper than decline, because a season in the nets is a long time and two
     * of them is an answer. This is what retires the player who is still good
     * enough and cannot get in.
     */
    val idlePressure: Double = 0.22,

    /**
     * Pressure from a body that has stopped mending — permanent damage carried
     * out of past injuries, at total loss.
     *
     * Not the injury he has now, which heals. The ones that did not.
     */
    val damagePressure: Double = 0.85,

    /**
     * How much a great career *reduces* the pressure to go on.
     *
     * A player with nothing left to prove goes out at the top, and one still
     * chasing a first cap hangs on past the point of sense. Small, because the
     * second effect is the more common one and this term carries both.
     */
    val fulfilmentPressure: Double = 0.18,

    /** Caps at which a career counts as fulfilled, for [fulfilmentPressure]. */
    val fulfilledAtMatches: Int = 150,
) {
    init {
        require(earliestAge in 20..60) { "earliestAge $earliestAge" }
        require(latestAge > earliestAge) { "latestAge $latestAge" }
        listOf(
            "agePressure" to agePressure, "declinePressure" to declinePressure,
            "idlePressure" to idlePressure, "damagePressure" to damagePressure,
            "fulfilmentPressure" to fulfilmentPressure,
        ).forEach { (name, value) ->
            require(value.isFinite() && value >= 0.0) { "$name = $value" }
        }
        require(fulfilledAtMatches > 0) { "fulfilledAtMatches $fulfilledAtMatches" }
    }
}
