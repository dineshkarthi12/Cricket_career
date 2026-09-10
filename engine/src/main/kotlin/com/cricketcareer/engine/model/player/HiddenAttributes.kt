package com.cricketcareer.engine.model.player

import kotlinx.serialization.Serializable

/**
 * The qualities a player never sees a number for.
 *
 * The brief is explicit: these are inferred from performance and from coach
 * feedback, never displayed. Nothing in the UI layer may render these values,
 * and no `toString` here should tempt anyone — see docs/OPEN_QUESTIONS.md Q8.
 *
 * All on the same 1-100 scale as visible attributes, so the progression maths
 * can normalise them the same way.
 */
@Serializable
data class HiddenAttributes(
    /**
     * Soft ceiling on development. Not a hard cap: growth slows sharply as an
     * attribute approaches it, and a player who is trained well and plays above
     * his level can exceed it slightly. A hard cap would make a career feel
     * pre-written, which is exactly the feeling to avoid.
     */
    val potential: Int,

    /**
     * Tendency to break down, independent of the visible `injuryResistance`.
     * Two players with identical fitness can have very different injury
     * records, and that difference is something you only learn by living it.
     */
    val injuryProneness: Int,

    /** Reaction to setbacks and to success. Drives how far form and confidence swing. */
    val temperament: Int,

    /**
     * Performance on the big occasion, relative to the ordinary day. Feeds the
     * `occasion` term of the pressure index: a high value means less pressure
     * felt in a final, a low one means a flat-track bully.
     */
    val bigMatchFactor: Int,

    /** How fast training and match minutes convert into attribute growth. */
    val learningRate: Int,
) {
    init {
        listOf(
            "potential" to potential,
            "injuryProneness" to injuryProneness,
            "temperament" to temperament,
            "bigMatchFactor" to bigMatchFactor,
            "learningRate" to learningRate,
        ).forEach { (name, value) ->
            require(value in Attributes.MIN..Attributes.MAX) {
                "$name = $value is outside ${Attributes.MIN}..${Attributes.MAX}"
            }
        }
    }

    /**
     * Deliberately opaque.
     *
     * These values reach the UI through the Player object, and a stray
     * `"$player"` in a debug row would leak the one thing the design says the
     * player must never see. Debug output uses [describeForDebug] instead, which
     * is easy to grep for and impossible to print by accident.
     */
    override fun toString(): String = "HiddenAttributes(hidden)"

    /** For engine tests and the harness only. Never call this from :app. */
    fun describeForDebug(): String =
        "potential=$potential injuryProneness=$injuryProneness temperament=$temperament " +
            "bigMatchFactor=$bigMatchFactor learningRate=$learningRate"

    companion object {
        /** All five at the given value. The calibration baseline. */
        fun uniform(value: Int): HiddenAttributes = HiddenAttributes(value, value, value, value, value)
    }
}
