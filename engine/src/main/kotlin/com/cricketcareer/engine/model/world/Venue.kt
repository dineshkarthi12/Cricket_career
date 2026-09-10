package com.cricketcareer.engine.model.world

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Boundary distances, in metres, as four cardinal measurements.
 *
 * Real grounds are not circles and are usually not symmetric either — one
 * square boundary is often materially shorter than the other, which is exactly
 * the kind of thing a leg-side hitter is picked for. Four cardinals with smooth
 * elliptical interpolation between them captures that with four numbers a
 * database editor can measure off a ground plan.
 *
 * Azimuth convention (batter-relative, mirrored for left-handers elsewhere):
 * 0° straight down the ground past the bowler, 90° square on the off side,
 * 180° straight behind the keeper, 270° square leg.
 */
@Serializable
data class BoundaryShape(
    val straightMetres: Double,
    val squareOffMetres: Double,
    val behindMetres: Double,
    val squareLegMetres: Double,
) {
    init {
        listOf(
            "straight" to straightMetres, "squareOff" to squareOffMetres,
            "behind" to behindMetres, "squareLeg" to squareLegMetres,
        ).forEach { (name, value) ->
            require(value.isFinite() && value in MIN_METRES..MAX_METRES) {
                "$name boundary $value m is outside $MIN_METRES..$MAX_METRES"
            }
        }
    }

    /** Boundary distance along [azimuthDegrees]. */
    fun distanceAt(azimuthDegrees: Double): Double {
        val theta = normalise(azimuthDegrees)
        val (a, b, offsetDegrees) = when {
            theta < 90.0 -> Triple(straightMetres, squareOffMetres, 0.0)
            theta < 180.0 -> Triple(behindMetres, squareOffMetres, 180.0)
            theta < 270.0 -> Triple(behindMetres, squareLegMetres, 180.0)
            else -> Triple(straightMetres, squareLegMetres, 360.0)
        }
        // Quarter-ellipse with semi-axis `a` along the straight/behind line and
        // `b` along the square line: r = ab / sqrt(a² sin²φ + b² cos²φ).
        val phi = abs(theta - offsetDegrees) * PI / 180.0
        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        return a * b / sqrt(a * a * sinPhi * sinPhi + b * b * cosPhi * cosPhi)
    }

    /** Mean boundary distance, for quick comparisons between grounds. */
    val averageMetres: Double
        get() = (straightMetres + squareOffMetres + behindMetres + squareLegMetres) / 4.0

    private fun normalise(degrees: Double): Double {
        val wrapped = degrees % 360.0
        return if (wrapped < 0) wrapped + 360.0 else wrapped
    }

    companion object {
        const val MIN_METRES: Double = 45.0
        const val MAX_METRES: Double = 100.0

        /** A mid-sized, symmetric ground. The calibration reference. */
        val AVERAGE: BoundaryShape = BoundaryShape(
            straightMetres = 72.0,
            squareOffMetres = 66.0,
            behindMetres = 68.0,
            squareLegMetres = 66.0,
        )
    }
}

/**
 * A ground.
 *
 * [archetypeWeights] is what makes home advantage emerge rather than be
 * granted: a venue that mostly produces rank turners will, over a season,
 * reward a squad full of spinners without any code ever adding a home bonus.
 */
@Serializable
data class Venue(
    val id: String,
    val name: String,
    val city: String,
    val country: String,
    val region: String,
    val boundary: BoundaryShape = BoundaryShape.AVERAGE,
    val soilType: SoilType = SoilType.CLAY,
    /** Relative likelihood of each pitch archetype here. Must not be empty. */
    val archetypeWeights: Map<PitchArchetype, Double> = mapOf(PitchArchetype.BALANCED to 1.0),
    /** Metres above sea level. Thin air carries the ball further. */
    val altitudeMetres: Double = 0.0,
    /**
     * How badly this ground dews up in a day-night game, 0 to 1. Heavy dew kills
     * spin, makes the ball skid on and wrecks the fielding side's grip — one of
     * the largest single swings in the game, and a major input to the toss.
     */
    val dewTendency: Double = 0.3,
) {
    init {
        require(id.isNotBlank()) { "venue id must not be blank" }
        require(name.isNotBlank()) { "venue $id has a blank name" }
        require(archetypeWeights.isNotEmpty()) { "venue $id has no pitch archetypes" }
        archetypeWeights.forEach { (archetype, weight) ->
            require(weight.isFinite() && weight >= 0.0) { "venue $id weight for $archetype is $weight" }
        }
        require(archetypeWeights.values.sum() > 0.0) { "venue $id has all-zero archetype weights" }
        require(altitudeMetres in -500.0..4000.0) { "venue $id altitude $altitudeMetres m is implausible" }
        require(dewTendency in 0.0..1.0) { "venue $id dewTendency $dewTendency must be in 0..1" }
    }
}
