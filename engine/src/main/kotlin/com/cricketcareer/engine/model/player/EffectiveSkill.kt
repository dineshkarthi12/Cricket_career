package com.cricketcareer.engine.model.player

/**
 * An attribute as it actually performs today, on the engine's [0, 1] scale.
 *
 * Folds dynamic state into the raw attribute at the point of use (see
 * docs/SIMULATION_MODEL.md §2). The coefficients are deliberately small — form
 * is a real effect but a modest one, and if form could swing a player from
 * "good" to "poor" the career layer would stop being about ability and become a
 * hidden slider.
 *
 * Fatigue is the largest single term because it is the one with the clearest
 * observable effect in real cricket: a bowler in his fourth over of a spell is
 * measurably worse.
 */
object EffectiveSkill {

    private const val FORM_WEIGHT = 0.080
    private const val CONFIDENCE_WEIGHT = 0.050
    private const val FATIGUE_WEIGHT = 0.120
    private const val SHARPNESS_WEIGHT = 0.060
    private const val NIGGLE_PENALTY = 0.040

    private const val FLOOR = 0.02
    private const val CEILING = 0.99

    /**
     * @param extraFatigue fatigue accumulated inside this match — a bowler's
     *   spell, a batter's long innings — on top of the season-level fatigue
     *   already in [PlayerState].
     */
    fun of(player: Player, attribute: Attribute, extraFatigue: Double = 0.0): Double {
        val state = player.state
        val fatigue = (state.fatigue + extraFatigue).coerceIn(0.0, 1.0)
        val niggle = if (state.injury?.severity == InjurySeverity.NIGGLE) 1.0 else 0.0
        return (
            player.attributes.normalised(attribute) +
                FORM_WEIGHT * state.form +
                CONFIDENCE_WEIGHT * state.confidence -
                FATIGUE_WEIGHT * fatigue -
                SHARPNESS_WEIGHT * (1.0 - state.sharpness) -
                NIGGLE_PENALTY * niggle
            ).coerceIn(FLOOR, CEILING)
    }
}
