package com.cricketcareer.engine.match.pitch

import com.cricketcareer.engine.config.PitchTuning
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.rng.SimRandom
import kotlin.math.pow

/** A session of play. Three to a day in multi-day cricket. */
enum class Session { MORNING, AFTERNOON, EVENING }

/**
 * How the pitch changes as a match goes on.
 *
 * The brief's requirement in one sentence: day one morning belongs to the
 * seamers, days two and three are the best time to bat, and days four and five
 * belong to the spinners and to variable bounce. None of that is a bonus
 * awarded to anybody — it is the pitch's physical properties moving, and the
 * movement and bounce models reading them exactly as they did on the first
 * morning.
 *
 * Called once per session, not per ball: a pitch does not change inside an over
 * and evolving it per ball would cost the per-ball budget for nothing.
 *
 * See docs/SIMULATION_MODEL.md §10.
 */
object PitchEvolution {

    /**
     * Advance the pitch by one session.
     *
     * @param oversThisSession how much play there was; an abandoned session
     *   still dries the surface but does not scuff it.
     * @param uncoveredRain rain that got onto the square, as a fraction. Covered
     *   rain does far less to a pitch than uncovered rain, which is why the
     *   covers matter.
     */
    fun afterSession(
        pitch: Pitch,
        weather: Weather,
        session: Session,
        oversThisSession: Double,
        uncoveredRain: Double,
        tuning: PitchTuning,
        rng: SimRandom,
    ): Pitch {
        val soil = pitch.soilType
        val wear = (oversThisSession / tuning.sessionOvers).coerceIn(0.0, 2.0)

        // Sun and wind take the moisture out; the morning session holds it
        // longest, which is why the first hour is the one that seams.
        val drying = tuning.dryingPerSession *
            (1.0 - 0.55 * weather.cloudCover) *
            (1.0 - 0.35 * weather.humidity) *
            if (session == Session.MORNING) 0.6 else 1.0
        val moisture = (pitch.moisture - drying + uncoveredRain * tuning.rainSoaking)
            .coerceIn(0.0, 1.0)

        // Grass is worn away by play and cut back between days.
        val grass = (pitch.grassCover - tuning.grassWearPerSession * wear).coerceIn(0.0, 1.0)

        // Cracks open as the surface dries out, and faster on soil that breaks up.
        val crackGrowth = tuning.crackGrowthPerSession * soil.deteriorationRate *
            (1.0 - moisture).pow(tuning.crackDrynessExponent) *
            (0.5 + 0.5 * wear)
        val cracks = (pitch.cracks + crackGrowth).coerceIn(0.0, 1.0)

        // Abrasion is purely a function of play: footmarks and the ball.
        val abrasion = (pitch.abrasion + tuning.abrasionPerSession * wear * soil.deteriorationRate)
            .coerceIn(0.0, 1.0)

        val deterioration = (pitch.deterioration + tuning.deteriorationPerSession * wear * soil.deteriorationRate)
            .coerceIn(0.0, 1.0)

        // A drying, worn, cracked surface grips more for the spinner and less
        // for the seamer. The same two numbers the movement model already reads.
        val turn = (pitch.turn + tuning.turnGainPerSession * (abrasion + cracks) / 2.0 * soil.turnFactor)
            .coerceIn(0.0, 1.0)
        val gripSeam = (pitch.gripSeam - tuning.seamLossPerSession * (1.0 - moisture))
            .coerceIn(0.0, 1.0)

        // A surface loosening under foot loses its pace and its carry.
        val hardness = (pitch.hardness - tuning.hardnessLossPerSession * wear).coerceIn(0.0, 1.0)
        val pace = (pitch.pace - tuning.paceLossPerSession * wear * (1.0 - hardness)).coerceIn(0.0, 1.0)
        val bounce = (pitch.bounce - tuning.bounceLossPerSession * wear * (1.0 - hardness)).coerceIn(0.0, 1.0)

        // Evenness is what makes day five frightening: as the cracks open, the
        // same length stops doing the same thing twice.
        val evenness = (pitch.evenness - tuning.evennessLossPerSession * (cracks + deterioration) / 2.0)
            .coerceIn(0.0, 1.0)

        // A little groundsman-to-groundsman variation so two identical matches
        // do not wear identically.
        val jitter = 1.0 + rng.nextGaussian() * tuning.sessionJitter

        return pitch.copy(
            hardness = hardness,
            grassCover = grass,
            moisture = moisture,
            cracks = (cracks * jitter).coerceIn(0.0, 1.0),
            abrasion = (abrasion * jitter).coerceIn(0.0, 1.0),
            pace = pace,
            bounce = bounce,
            evenness = evenness,
            turn = (turn * jitter).coerceIn(0.0, 1.0),
            gripSeam = gripSeam,
            deterioration = deterioration,
        )
    }

    /**
     * Dew on the ground during a day-night innings, 0 to 1.
     *
     * Rises through the evening and is one of the biggest single swings in the
     * game: a wet ball grips nothing, so the spinners are neutered and
     * everything skids on. It belongs in the toss decision.
     */
    fun dewAt(fractionOfInningsElapsed: Double, venueTendency: Double, dayNight: Boolean): Double {
        if (!dayNight) return 0.0
        // Nothing early, then it comes down quickly once the light goes.
        val onset = ((fractionOfInningsElapsed - 0.35) / 0.45).coerceIn(0.0, 1.0)
        return (venueTendency * onset * onset).coerceIn(0.0, 1.0)
    }
}
