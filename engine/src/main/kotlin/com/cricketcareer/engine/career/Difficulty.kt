package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.CareerTuning
import kotlinx.serialization.Serializable

/**
 * How hard a career is to have.
 *
 * **Difficulty changes the career, never the cricket.** Nothing here reaches
 * the ball-by-ball engine: on Hard the ball does not swing further, the fielders
 * do not catch better, and the user's own attributes are not quietly shaded
 * down. A simulation that lies to the player about what happened on the field is
 * not worth building, and it would make every statistic in the game meaningless
 * (docs/OPEN_QUESTIONS.md Q6).
 *
 * What changes is everything *around* the cricket, which is where a career
 * actually gets made or lost:
 *
 * - **How patient the selectors are.** The single biggest lever. A harder game
 *   gives the incumbent more benefit of the doubt, so a place has to be taken
 *   rather than inherited.
 * - **How good the opposition is.** The same player, same engine, against
 *   better sides — so his numbers fall for a reason that is visible on the
 *   scorecard rather than hidden in a multiplier.
 * - **How often he breaks down**, and how much a crowded season costs him.
 * - **How harshly form swings**, which decides how long one bad run lasts.
 * - **How fast he develops**, which decides whether he gets a second chance at
 *   a level he failed at first time.
 *
 * The whole of it is expressed as a transform on [CareerTuning]. There is no
 * `if (difficulty == HARD)` anywhere in the career layer and there must never
 * be one: the career runs the same code at every setting, on different numbers.
 * That is the same rule Q3 applies to the user's batting posture, for the same
 * reason — a parallel code path is a second, worse model of the thing it
 * duplicates.
 */
@Serializable
enum class Difficulty(
    val displayName: String,
    /**
     * Multiplier on the weight selectors put on reputation — the term that
     * makes an incumbent hard to shift and a debut feel earned.
     */
    val selectorPatience: Double,
    /**
     * Shift in the standard of opposition a fixture list draws, in the same
     * 0..1 units as [Fixture.oppositionStandard].
     *
     * Applied by whoever builds the fixture list rather than by the tuning
     * transform, because the standard of a side is a property of the world, not
     * of a model constant.
     */
    val oppositionStandardShift: Double,
    /** Multiplier on every injury hazard in the game. */
    val injuryRisk: Double,
    /**
     * Multiplier on how far one performance moves form.
     *
     * Above one is *harsher*, not kinder: a bad trot arrives faster and bites
     * deeper, and the selectors are reading that form figure.
     */
    val formVolatility: Double,
    /** Multiplier on how much a player improves from training and from playing. */
    val development: Double,
) {
    /**
     * For a player who wants the career rather than the fight.
     *
     * Not a cheat mode: he still has to score runs, and the engine is the same
     * engine. He is simply given more chances to do it.
     */
    AMATEUR("Amateur", 0.70, -0.08, 0.70, 0.80, 1.25),

    /** The calibration reference. Every band in docs/CALIBRATION.md is measured here. */
    PROFESSIONAL("Professional", 1.00, 0.00, 1.00, 1.00, 1.00),

    /**
     * A place has to be taken.
     *
     * Selectors back the man in possession, the opposition is stronger, bodies
     * break more often and form turns faster. A good player still gets there;
     * a merely decent one spends his career in second-class cricket, which is
     * what happens to most cricketers.
     */
    ELITE("Elite", 1.35, 0.08, 1.35, 1.25, 0.82),
    ;

    /**
     * The tuning this difficulty runs on.
     *
     * A pure function of [base], so two careers on the same setting are
     * identical and a save file can store the enum rather than a tuning blob.
     */
    fun applyTo(base: CareerTuning): CareerTuning = base.copy(
        selection = base.selection.copy(
            weightReputation = base.selection.weightReputation * selectorPatience,
        ),
        injury = base.injury.copy(
            baseRiskPerMatchPace = base.injury.baseRiskPerMatchPace * injuryRisk,
            baseRiskPerMatchSpin = base.injury.baseRiskPerMatchSpin * injuryRisk,
            baseRiskPerMatchOutfield = base.injury.baseRiskPerMatchOutfield * injuryRisk,
        ),
        form = base.form.copy(
            formGainPerSigma = base.form.formGainPerSigma * formVolatility,
        ),
        training = base.training.copy(
            pointsPerFullWeek = base.training.pointsPerFullWeek * development,
        ),
    )

    companion object {
        /** What a career starts on when nobody has chosen. */
        val DEFAULT: Difficulty = PROFESSIONAL

        /** Looked up by stored name, falling back rather than failing a load. */
        fun ofOrDefault(name: String?): Difficulty =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
