package com.cricketcareer.engine.match.delivery

import com.cricketcareer.engine.config.MovementTuning
import kotlin.math.exp

/**
 * The state of the cricket ball itself.
 *
 * Derived from overs bowled rather than stored, so it can never drift out of
 * step with the innings. Hardness is the one that quietly shapes a whole
 * innings: a soft fifty-over-old ball does not carry to second slip, which is
 * why cordons thin out.
 */
data class BallCondition(
    /** Overs bowled with this ball. */
    val ageOvers: Double,
    val shine: Double,
    val roughness: Double,
    val hardness: Double,
) {
    companion object {
        fun at(ageOvers: Double, tuning: MovementTuning, outfieldAbrasion: Double): BallCondition {
            // Abrasive outfields scuff a ball faster and polish off its shine.
            val wear = 0.75 + 0.5 * outfieldAbrasion
            return BallCondition(
                ageOvers = ageOvers,
                shine = exp(-ageOvers * wear / tuning.shineDecayOvers).coerceIn(0.0, 1.0),
                roughness = (1.0 - exp(-ageOvers * wear / tuning.roughnessGrowthOvers)).coerceIn(0.0, 1.0),
                hardness = (
                    tuning.hardnessFloor +
                        (1.0 - tuning.hardnessFloor) * exp(-ageOvers / tuning.hardnessDecayOvers)
                    ).coerceIn(0.0, 1.0),
            )
        }
    }
}

/** Conditions overhead and underfoot that the movement and perception models read. */
data class Weather(
    val humidity: Double = 0.5,
    val cloudCover: Double = 0.35,
    /** 1.0 is bright sunshine, 0 is unplayable gloom. */
    val lightQuality: Double = 1.0,
    /** Dew on the outfield and the ball. Kills spin, makes the ball skid on. */
    val dew: Double = 0.0,
    /** How abrasive the square and outfield are, which drives ball wear and reverse swing. */
    val outfieldAbrasion: Double = 0.4,
) {
    init {
        listOf(
            "humidity" to humidity, "cloudCover" to cloudCover, "lightQuality" to lightQuality,
            "dew" to dew, "outfieldAbrasion" to outfieldAbrasion,
        ).forEach { (name, value) ->
            require(value.isFinite() && value in 0.0..1.0) { "weather $name = $value must be in 0..1" }
        }
    }

    companion object {
        /** The calibration reference: mild, dry, clear. */
        val AVERAGE: Weather = Weather()
    }
}
