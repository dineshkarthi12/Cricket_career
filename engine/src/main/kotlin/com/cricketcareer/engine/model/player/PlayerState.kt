package com.cricketcareer.engine.model.player

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Everything about a player that changes match to match.
 *
 * Kept strictly apart from [Attributes]: an attribute is what a player *is*,
 * state is how he happens to be. Conflating them is the classic sports-sim
 * mistake — it makes a purple patch indistinguishable from genuine improvement,
 * and it makes the career layer feel like a hidden slider rather than a story.
 *
 * All fields are on fixed scales so the engine can read them without knowing
 * where they came from:
 *  - [form], [confidence], [morale] in [-1, 1], zero being neutral
 *  - [fatigue], [sharpness] in [0, 1]
 */
@Serializable
data class PlayerState(
    /** Rolling recent performance relative to the player's own standard. Decays toward 0. */
    val form: Double = 0.0,

    /** Self-belief. Moves faster than form and recovers faster. */
    val confidence: Double = 0.0,

    /** Accumulated tiredness. 0 is fresh, 1 is spent. The largest single term in effective skill. */
    val fatigue: Double = 0.0,

    /** Happiness with his situation: selection, role, contract, the dressing room. */
    val morale: Double = 0.0,

    /**
     * Match sharpness. Falls while out of the side and recovers with time in
     * the middle. This is what makes a returning player rusty even when his
     * attributes are intact, and it is why a bench year in a franchise league
     * hurts.
     */
    val sharpness: Double = 1.0,

    /** Current injury, if any. A player with an injury may still be selectable if it is minor. */
    val injury: Injury? = null,
) {
    init {
        requireSigned("form", form)
        requireSigned("confidence", confidence)
        requireSigned("morale", morale)
        requireUnit("fatigue", fatigue)
        requireUnit("sharpness", sharpness)
    }

    val isInjured: Boolean get() = injury != null
    val isAvailable: Boolean get() = injury == null || !injury.rulesOutSelection

    /**
     * Move [form], [confidence] and [morale] a fraction of the way back to
     * neutral, and recover a fraction of [fatigue].
     *
     * Called once per rest period rather than per day, so [rate] is the
     * proportion of the gap closed. Form that never decayed would make one good
     * season carry a player for a decade.
     */
    fun decayTowardNeutral(rate: Double): PlayerState {
        require(rate in 0.0..1.0) { "decay rate $rate must be in 0..1" }
        return copy(
            form = form * (1.0 - rate),
            confidence = confidence * (1.0 - rate),
            morale = morale * (1.0 - rate),
            fatigue = fatigue * (1.0 - rate),
        )
    }

    /** Nudge form by [delta], keeping it in range. */
    fun withFormChange(delta: Double): PlayerState = copy(form = (form + delta).coerceIn(-1.0, 1.0))

    /** Nudge confidence by [delta], keeping it in range. */
    fun withConfidenceChange(delta: Double): PlayerState =
        copy(confidence = (confidence + delta).coerceIn(-1.0, 1.0))

    /** Add [delta] fatigue, keeping it in range. */
    fun withFatigueChange(delta: Double): PlayerState = copy(fatigue = (fatigue + delta).coerceIn(0.0, 1.0))

    /** Move sharpness by [delta], keeping it in range. */
    fun withSharpnessChange(delta: Double): PlayerState =
        copy(sharpness = (sharpness + delta).coerceIn(0.0, 1.0))

    private fun requireSigned(name: String, value: Double) {
        require(value.isFinite() && abs(value) <= 1.0) { "$name = $value must be in -1..1" }
    }

    private fun requireUnit(name: String, value: Double) {
        require(value.isFinite() && value in 0.0..1.0) { "$name = $value must be in 0..1" }
    }

    companion object {
        /** Neutral form, no fatigue, fully sharp, uninjured. */
        val FRESH: PlayerState = PlayerState()
    }
}

/** How serious an injury is, and what it costs. */
@Serializable
enum class InjurySeverity(val displayName: String, val rulesOutSelection: Boolean) {
    /** Playable through, but it costs fitness and raises the risk of something worse. */
    NIGGLE("Niggle", rulesOutSelection = false),
    MINOR("Minor injury", rulesOutSelection = true),
    MODERATE("Injury", rulesOutSelection = true),
    SERIOUS("Serious injury", rulesOutSelection = true),

    /**
     * Career-threatening. These are the ones that permanently shave points off
     * physical attributes, per the brief's ageing section.
     */
    SEVERE("Severe injury", rulesOutSelection = true),
}

@Serializable
data class Injury(
    val type: String,
    val severity: InjurySeverity,
    /** Days of rehab remaining. Counted down by the career layer, not by the match engine. */
    val daysRemaining: Int,
    /** Permanent attribute damage already applied, recorded so a career summary can show it. */
    val permanentDamage: Int = 0,
) {
    init {
        require(daysRemaining >= 0) { "daysRemaining $daysRemaining cannot be negative" }
        require(permanentDamage >= 0) { "permanentDamage $permanentDamage cannot be negative" }
        require(type.isNotBlank()) { "injury type must not be blank" }
    }

    val rulesOutSelection: Boolean get() = severity.rulesOutSelection
}
