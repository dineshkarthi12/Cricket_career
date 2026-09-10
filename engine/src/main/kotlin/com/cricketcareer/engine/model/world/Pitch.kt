package com.cricketcareer.engine.model.world

import com.cricketcareer.engine.rng.SimRandom
import kotlinx.serialization.Serializable

/**
 * The strip, as a first-class object that changes during a match.
 *
 * Every field is in [0, 1] and is a *physical* property rather than an outcome:
 * there is deliberately no "batting friendliness" or "seam bonus" here. What a
 * pitch does to a delivery is worked out in the movement and bounce models from
 * these properties. Adding a shortcut field would let two parts of the engine
 * disagree about the same pitch.
 *
 * Evolution across a match (day 1 seam, days 2-3 flat, days 4-5 spin and
 * variable bounce) arrives in Phase 3; this is the state it evolves.
 *
 * See docs/SIMULATION_MODEL.md §10.
 */
@Serializable
data class Pitch(
    /** Compaction. High hardness means true bounce and carry to the cordon. */
    val hardness: Double,

    /** Live grass. Feeds seam movement and keeps moisture in for longer. */
    val grassCover: Double,

    /** Water in the surface. The day-one seam-and-swing factor; burns off in sunshine. */
    val moisture: Double,

    /** Surface cracking. The variable-bounce term: `σ_vb` grows with this. */
    val cracks: Double,

    /** Wear of the surface, from footmarks and from the ball. Drives spin grip and reverse swing. */
    val abrasion: Double,

    /** How quickly the ball comes on to the bat. */
    val pace: Double,

    /** Height off a length. Separate from [pace]: slow-and-bouncy and quick-and-low both exist. */
    val bounce: Double,

    /** Consistency of bounce. Falls as [cracks] and [deterioration] rise. */
    val evenness: Double,

    /** Lateral grip available to a spinner. */
    val turn: Double,

    /** Grip available to a seamer off the pitch, distinct from [turn]. */
    val gripSeam: Double,

    /** Overall breakdown so far. 0 on the first morning, high on day five. */
    val deterioration: Double,

    val soilType: SoilType,
) {
    init {
        listOf(
            "hardness" to hardness, "grassCover" to grassCover, "moisture" to moisture,
            "cracks" to cracks, "abrasion" to abrasion, "pace" to pace, "bounce" to bounce,
            "evenness" to evenness, "turn" to turn, "gripSeam" to gripSeam,
            "deterioration" to deterioration,
        ).forEach { (name, value) ->
            require(value.isFinite() && value in 0.0..1.0) { "pitch $name = $value must be in 0..1" }
        }
    }

    companion object {
        /**
         * The calibration reference: every parameter at its midpoint.
         *
         * "Average pitch" in docs/CALIBRATION.md means exactly this object, so
         * that a target band measured today is comparable with one measured in
         * Phase 6. Clay soil because it is the most neutral of the four.
         */
        val AVERAGE: Pitch = Pitch(
            hardness = 0.5, grassCover = 0.5, moisture = 0.5, cracks = 0.5, abrasion = 0.5,
            pace = 0.5, bounce = 0.5, evenness = 0.5, turn = 0.5, gripSeam = 0.5,
            deterioration = 0.0, soilType = SoilType.CLAY,
        )
    }
}

/**
 * Soil sets the *character* of a pitch's evolution, not just its starting
 * numbers — which is why it is an enum here rather than three more doubles.
 *
 * The multipliers are applied when a pitch is generated from an archetype and
 * again (from Phase 3) when it evolves session by session.
 */
@Serializable
enum class SoilType(
    val displayName: String,
    /** Multiplier on bounce and carry. */
    val bounceFactor: Double,
    /** Multiplier on how fast the ball comes on. */
    val paceFactor: Double,
    /** Multiplier on spin grip. */
    val turnFactor: Double,
    /** How fast the surface breaks up over a long match. */
    val deteriorationRate: Double,
) {
    /** Red soil: bounce and carry, holds together, cracks late but wide. */
    RED("Red soil", bounceFactor = 1.18, paceFactor = 1.12, turnFactor = 0.90, deteriorationRate = 0.90),

    /** Black soil: slow and low, grips for spin from early, little carry. */
    BLACK("Black soil", bounceFactor = 0.82, paceFactor = 0.84, turnFactor = 1.25, deteriorationRate = 1.05),

    /** Sandy: crumbles quickly; the dramatic day-four collapse of a surface. */
    SANDY("Sandy", bounceFactor = 0.95, paceFactor = 0.98, turnFactor = 1.15, deteriorationRate = 1.45),

    /** Clay: binds hard, even bounce, slow to break up. The most neutral. */
    CLAY("Clay", bounceFactor = 1.05, paceFactor = 1.02, turnFactor = 1.00, deteriorationRate = 0.75),
    ;

    companion object {
        val ALL: List<SoilType> = entries.toList()
    }
}

/**
 * The recognisable kinds of pitch a groundsman produces.
 *
 * An archetype is a *distribution*, not a fixed pitch: two green seamers are
 * not identical, and a captain who has read the surface should still be
 * occasionally wrong. [generate] samples one.
 *
 * Home advantage in this engine emerges from venues favouring particular
 * archetypes plus squads built to suit them — never from a "home bonus" term.
 */
@Serializable
enum class PitchArchetype(val displayName: String) {
    GREEN_SEAMER("Green seamer"),
    FLAT_ROAD("Flat road"),
    RANK_TURNER("Rank turner"),
    BOUNCY_HARD_DECK("Hard and bouncy"),
    SLOW_LOW_DECK("Slow, low deck"),
    BALANCED("Good cricket wicket"),
    ;

    /**
     * Sample a pitch of this archetype for the first morning of a match.
     *
     * [rng] must come from the conditions stream so that re-rolling the pitch
     * cannot disturb any other part of the simulation.
     */
    fun generate(rng: SimRandom, soil: SoilType): Pitch {
        fun draw(centre: Double, spread: Double): Double =
            (centre + rng.nextGaussian() * spread).coerceIn(0.0, 1.0)

        val base = when (this) {
            GREEN_SEAMER -> PitchCentres(
                hardness = 0.55, grass = 0.82, moisture = 0.68, cracks = 0.10, abrasion = 0.06,
                pace = 0.62, bounce = 0.60, evenness = 0.78, turn = 0.22, gripSeam = 0.84,
            )
            FLAT_ROAD -> PitchCentres(
                hardness = 0.80, grass = 0.14, moisture = 0.18, cracks = 0.10, abrasion = 0.08,
                pace = 0.60, bounce = 0.55, evenness = 0.90, turn = 0.24, gripSeam = 0.18,
            )
            RANK_TURNER -> PitchCentres(
                hardness = 0.34, grass = 0.05, moisture = 0.12, cracks = 0.55, abrasion = 0.48,
                pace = 0.30, bounce = 0.38, evenness = 0.50, turn = 0.88, gripSeam = 0.30,
            )
            BOUNCY_HARD_DECK -> PitchCentres(
                hardness = 0.90, grass = 0.38, moisture = 0.26, cracks = 0.12, abrasion = 0.10,
                pace = 0.86, bounce = 0.88, evenness = 0.84, turn = 0.30, gripSeam = 0.46,
            )
            SLOW_LOW_DECK -> PitchCentres(
                hardness = 0.36, grass = 0.20, moisture = 0.30, cracks = 0.30, abrasion = 0.30,
                pace = 0.24, bounce = 0.22, evenness = 0.62, turn = 0.56, gripSeam = 0.34,
            )
            BALANCED -> PitchCentres(
                hardness = 0.62, grass = 0.42, moisture = 0.40, cracks = 0.18, abrasion = 0.14,
                pace = 0.54, bounce = 0.54, evenness = 0.80, turn = 0.42, gripSeam = 0.48,
            )
        }

        // Spread is small on the controllable properties (a groundsman asked for
        // a turner produces a turner) and larger on moisture, which is weather.
        return Pitch(
            hardness = draw(base.hardness, 0.06),
            grassCover = draw(base.grass, 0.07),
            moisture = draw(base.moisture, 0.11),
            cracks = draw(base.cracks, 0.05),
            abrasion = draw(base.abrasion, 0.04),
            pace = draw(base.pace * soil.paceFactor, 0.06),
            bounce = draw(base.bounce * soil.bounceFactor, 0.06),
            evenness = draw(base.evenness, 0.06),
            turn = draw(base.turn * soil.turnFactor, 0.07),
            gripSeam = draw(base.gripSeam, 0.07),
            deterioration = 0.0,
            soilType = soil,
        )
    }

    private data class PitchCentres(
        val hardness: Double, val grass: Double, val moisture: Double, val cracks: Double,
        val abrasion: Double, val pace: Double, val bounce: Double, val evenness: Double,
        val turn: Double, val gripSeam: Double,
    )

    companion object {
        val ALL: List<PitchArchetype> = entries.toList()
    }
}
