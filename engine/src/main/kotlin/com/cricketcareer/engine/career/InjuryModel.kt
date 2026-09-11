package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.InjuryTuning
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.BowlingKind
import com.cricketcareer.engine.model.player.Injury
import com.cricketcareer.engine.model.player.InjurySeverity
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerState
import com.cricketcareer.engine.rng.SimRandom
import kotlin.math.roundToInt

/**
 * What a player was doing when the hazard was rolled.
 *
 * A match and a training week are the same event to this model, differing only
 * in base rate — which is right, because a side strain in the nets and a side
 * strain in the middle are the same injury.
 */
enum class InjuryContext { MATCH, TRAINING }

/**
 * When a cricketer breaks down, and how badly.
 *
 * See docs/CAREER_MODEL.md §6.
 *
 * The load-bearing term is fatigue. Without it, resting a player is a pure
 * loss and no sane manager ever would; with it, over-scheduling punishes
 * itself and the rest decision on the training screen is a real one.
 */
object InjuryModel {

    /**
     * Probability that this player breaks down in this one event.
     *
     * Multiplicative rather than additive so the terms compound the way they
     * do in a body: an old, tired, brittle fast bowler is not "a bit more"
     * likely to break than a young fresh one.
     */
    fun hazard(
        player: Player,
        age: Int,
        context: InjuryContext,
        intensity: Double,
        tuning: InjuryTuning,
    ): Double {
        require(intensity in 0.0..1.0) { "intensity $intensity must be in 0..1" }
        val base = when (context) {
            InjuryContext.MATCH -> baseForRole(player, tuning)
            InjuryContext.TRAINING -> baseForRole(player, tuning) * tuning.trainingRiskRelativeToMatch
        }
        val fatigueTerm = 1.0 + tuning.fatigueRiskMultiplier * player.state.fatigue
        val proneness = player.hidden.injuryProneness.toDouble() / Attributes.MAX
        val pronenessTerm = 1.0 + tuning.pronenessRiskMultiplier * proneness
        val yearsPast = (age - tuning.riskRisesFromAge).coerceAtLeast(0)
        val ageTerm = 1.0 + tuning.riskPerYearPastPeak * yearsPast
        // Resistance is the visible counterpart of proneness and pulls the
        // other way: two players can share a fitness number and still have
        // very different injury records.
        val resistance = player.attributes.normalised(Attribute.INJURY_RESISTANCE)
        val resistanceTerm = 1.3 - 0.6 * resistance
        return (base * intensity * fatigueTerm * pronenessTerm * ageTerm * resistanceTerm).coerceIn(0.0, 1.0)
    }

    private fun baseForRole(player: Player, tuning: InjuryTuning): Double =
        when (player.bowlingStyle.kind) {
            BowlingKind.PACE -> tuning.baseRiskPerMatchPace
            BowlingKind.SPIN -> tuning.baseRiskPerMatchSpin
            BowlingKind.NONE -> tuning.baseRiskPerMatchOutfield
        }

    /**
     * Roll once. Returns null when the player came through it.
     *
     * [strain] in [0, 1] shifts the severity draw toward the serious end —
     * playing a spent 34-year-old does not only make an injury likelier, it
     * makes it a worse one.
     */
    fun roll(
        player: Player,
        age: Int,
        context: InjuryContext,
        intensity: Double,
        random: SimRandom,
        tuning: InjuryTuning,
    ): Injury? {
        val p = hazard(player, age, context, intensity, tuning)
        if (random.nextDouble() >= p) return null
        val strain = strainOf(player.state, age, tuning)
        val severity = drawSeverity(random.nextDouble(), strain, tuning)
        val days = rehabDays(severity, player.attributes, tuning)
        return Injury(type = bodyPart(severity, random), severity = severity, daysRemaining = days)
    }

    /** How hard this player was being pushed when it happened, in [0, 1]. */
    internal fun strainOf(state: PlayerState, age: Int, tuning: InjuryTuning): Double {
        val fromAge = ((age - tuning.riskRisesFromAge).coerceAtLeast(0) / 10.0).coerceAtMost(1.0)
        return ((state.fatigue + fromAge) / 2.0).coerceIn(0.0, 1.0)
    }

    /**
     * Draw a severity from the cumulative weights, with [strain] pushing the
     * draw up the scale. Implemented as a shift on the uniform rather than a
     * reweighting so that the ordering of the bands is preserved exactly and
     * only their boundaries move.
     */
    internal fun drawSeverity(uniform: Double, strain: Double, tuning: InjuryTuning): InjurySeverity {
        val shifted = (uniform + strain * tuning.severityShiftFromStrain).coerceIn(0.0, 0.999999)
        var cumulative = 0.0
        val bands = InjurySeverity.entries
        tuning.severityWeights.forEachIndexed { index, weight ->
            cumulative += weight
            if (shifted < cumulative) return bands[index]
        }
        return bands.last()
    }

    /** Rehab length, shortened by fitness. */
    internal fun rehabDays(severity: InjurySeverity, attributes: Attributes, tuning: InjuryTuning): Int {
        val base = when (severity) {
            InjurySeverity.NIGGLE -> tuning.rehabDaysNiggle
            InjurySeverity.MINOR -> tuning.rehabDaysMinor
            InjurySeverity.MODERATE -> tuning.rehabDaysModerate
            InjurySeverity.SERIOUS -> tuning.rehabDaysSerious
            InjurySeverity.SEVERE -> tuning.rehabDaysSevere
        }
        val fitness = attributes.normalised(Attribute.FITNESS)
        return (base * (1.0 - tuning.rehabFitnessRange * fitness)).roundToInt().coerceAtLeast(1)
    }

    /**
     * The body part, drawn from a list weighted by how severe it is.
     *
     * Cosmetic for the simulation and not for the player: "grade 2 hamstring"
     * and "stress fracture of the lower back" carry completely different
     * meanings to someone reading his own career, even when the rehab days are
     * the same number.
     */
    internal fun bodyPart(severity: InjurySeverity, random: SimRandom): String {
        val options = when (severity) {
            InjurySeverity.NIGGLE -> NIGGLES
            InjurySeverity.MINOR, InjurySeverity.MODERATE -> SOFT_TISSUE
            InjurySeverity.SERIOUS, InjurySeverity.SEVERE -> STRUCTURAL
        }
        return options[random.nextInt(options.size)]
    }

    private val NIGGLES = listOf(
        "Tight hamstring", "Sore shoulder", "Bruised finger", "Stiff back", "Rolled ankle",
    )
    private val SOFT_TISSUE = listOf(
        "Hamstring strain", "Side strain", "Groin strain", "Calf strain",
        "Intercostal tear", "Quad strain", "Rotator cuff strain",
    )
    private val STRUCTURAL = listOf(
        "Stress fracture of the lower back", "Anterior cruciate ligament tear",
        "Achilles rupture", "Shoulder reconstruction", "Stress fracture of the foot",
    )

    /**
     * One day of rehab. Returns the state with the injury counted down, and
     * the injury cleared entirely when the count reaches zero.
     *
     * Permanent damage from a severe injury is applied by the caller at the
     * moment [Injury.daysRemaining] hits zero — this function does not touch
     * attributes, because state and attributes are deliberately separate.
     */
    fun rehabDay(state: PlayerState): PlayerState {
        val injury = state.injury ?: return state
        val remaining = injury.daysRemaining - 1
        return if (remaining <= 0) state.copy(injury = null)
        else state.copy(injury = injury.copy(daysRemaining = remaining))
    }

    /** True when this injury, on resolving, permanently costs the player something. */
    fun leavesPermanentDamage(injury: Injury): Boolean = injury.severity == InjurySeverity.SEVERE
}
