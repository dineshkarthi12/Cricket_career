package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.FormTuning
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.PlayerState
import kotlin.math.tanh

/**
 * What a performance was worth, before form knows anything about it.
 *
 * [expected] and [expectedStdDev] are the distribution a player of these
 * attributes would produce *in these conditions at this level*, measured from
 * engine output rather than hand-authored — the same discipline as
 * docs/CALIBRATION.md. Dividing by the spread is what makes 30 in a
 * low-scoring Test worth more than 30 in a T20, without anyone writing a rule
 * that says so.
 */
data class Performance(
    val actual: Double,
    val expected: Double,
    val expectedStdDev: Double,
) {
    init {
        require(actual.isFinite()) { "actual $actual must be finite" }
        require(expected.isFinite()) { "expected $expected must be finite" }
        require(expectedStdDev.isFinite() && expectedStdDev > 0.0) {
            "expectedStdDev $expectedStdDev must be finite and positive"
        }
    }

    /** How many standard deviations above expectation this was. Signed. */
    val surprise: Double get() = (actual - expected) / expectedStdDev
}

/**
 * Form, confidence and sharpness.
 *
 * Three numbers rather than one, because they behave differently and
 * collapsing them is the thing that makes a sports sim feel like a hidden
 * slider. Form is a rolling judgement of a career, confidence is a mood, and
 * sharpness is time in the middle.
 *
 * See docs/CAREER_MODEL.md §4.
 */
object FormModel {

    /**
     * Apply one performance.
     *
     * `tanh` rather than a linear response so that one triple century does not
     * buy a decade of form: the second standard deviation is worth much less
     * than the first, and beyond about three the curve is flat. Without it,
     * a single outlier innings dominates a whole season of evidence.
     */
    fun afterPerformance(
        state: PlayerState,
        performance: Performance,
        temperament: Int,
        tuning: FormTuning,
    ): PlayerState {
        val swing = temperamentSwing(temperament, tuning)
        val response = tanh(performance.surprise / tuning.surpriseSaturation)
        val formDelta = tuning.formGainPerSigma * response * swing
        return state
            .withFormChange(formDelta)
            .withConfidenceChange(formDelta * tuning.confidenceToFormRatio)
            .withSharpnessChange(tuning.sharpnessGainPerAppearance)
    }

    /**
     * A low-temperament player swings further in both directions — he is the
     * one who is unplayable for a month and then cannot buy a run. A high one
     * is metronomic. Scaled so that temperament 100 swings exactly 1.0 and
     * temperament 1 swings (1 + temperamentSwingRange).
     */
    fun temperamentSwing(temperament: Int, tuning: FormTuning): Double {
        require(temperament in Attributes.MIN..Attributes.MAX) {
            "temperament $temperament is outside ${Attributes.MIN}..${Attributes.MAX}"
        }
        val normalised = (temperament - Attributes.MIN).toDouble() / (Attributes.MAX - Attributes.MIN)
        return 1.0 + tuning.temperamentSwingRange * (1.0 - normalised)
    }

    /**
     * One day in which the player did not play.
     *
     * Form and confidence ebb toward neutral at different rates, and sharpness
     * falls. Applied per day rather than per match so that a two-month injury
     * and a two-month suspension cost the same rustiness, which is correct.
     */
    fun restDay(state: PlayerState, tuning: FormTuning): PlayerState = state.copy(
        form = state.form * (1.0 - tuning.formDecayPerDay),
        confidence = state.confidence * (1.0 - tuning.confidenceDecayPerDay),
        sharpness = (state.sharpness - tuning.sharpnessLossPerDay).coerceIn(0.0, 1.0),
    )
}
