package com.cricketcareer.presentation

import com.cricketcareer.engine.career.TrainingFocus
import com.cricketcareer.engine.career.TrainingModel
import com.cricketcareer.engine.career.TrainingWeek
import com.cricketcareer.engine.config.TrainingTuning
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Player

/** One slider on the training screen. */
data class FocusSlider(
    val focus: TrainingFocus,
    val label: String,
    /** Points allocated, 0 to 100. */
    val points: Int,
    /** The attributes it moves, named, so a player can see what he is buying. */
    val covers: List<String>,
)

/** What a block of weeks on this plan is projected to do to one attribute. */
data class TrainingProjection(
    val attribute: Attribute,
    val label: String,
    val now: Int,
    val projected: Int,
    /**
     * The ceiling this work is heading towards.
     *
     * One number for the whole player, because potential in this model is one
     * number: a cricketer has a level he can reach, not forty separate ones.
     * Shown per attribute anyway, because "technique 71, ceiling 78" is what a
     * player wants to read, and "your potential is 78" on its own is not.
     */
    val ceiling: Int,
) {
    val gain: Int get() = projected - now
    /** True when he is already at his ceiling here and the work will not show. */
    val atCeiling: Boolean get() = now >= ceiling
}

/** Everything the training screen draws. */
data class TrainingState(
    val sliders: List<FocusSlider>,
    val projections: List<TrainingProjection>,
    /** "100 of 100 points allocated" — or what is left. */
    val allocation: String,
    /** Share of the week that is not rest, 0 to 1. */
    val intensity: Double,
    /** The warning a tired player needs: hard weeks on top of fatigue cost more than they buy. */
    val overloadWarning: String?,
) {
    val isLegal: Boolean get() = sliders.sumOf { it.points } == TrainingWeek.TOTAL_POINTS
}

/**
 * The training screen.
 *
 * Projections rather than promises: the screen shows what this plan is expected
 * to be worth over a block of weeks, because a single week moves an attribute
 * by about a fifth of a point and a screen that showed *that* would look
 * broken. `TrainingModel.project` is the same arithmetic the model actually
 * runs, so the number on the screen and the number in the save file cannot
 * drift apart.
 *
 * The ceiling is shown because it is the thing a player most needs to know and
 * the thing no amount of work changes: an attribute already at its potential
 * does not move, however many weeks go into it.
 */
fun trainingState(
    player: Player,
    week: TrainingWeek,
    coaching: Double,
    weeks: Int,
    tuning: TrainingTuning,
): TrainingState {
    val sliders = TrainingFocus.entries.map { focus ->
        FocusSlider(
            focus = focus,
            label = focus.displayName,
            points = week.pointsFor(focus),
            covers = focus.attributes.map { it.displayName },
        )
    }

    // Only the attributes this plan actually touches. A screen listing all
    // forty, thirty-six of them projecting no change, is a screen nobody reads.
    val touched = TrainingFocus.entries
        .filter { week.pointsFor(it) > 0 }
        .flatMap { it.attributes }
        .distinct()

    val projections = touched.map { attribute ->
        TrainingProjection(
            attribute = attribute,
            label = attribute.displayName,
            now = player.attributes[attribute],
            projected = TrainingModel.project(player, week, coaching, weeks, attribute, tuning),
            ceiling = player.hidden.potential,
        )
    }

    val allocated = sliders.sumOf { it.points }
    return TrainingState(
        sliders = sliders,
        projections = projections,
        allocation = "$allocated of ${TrainingWeek.TOTAL_POINTS} points allocated",
        intensity = week.intensity,
        overloadWarning = overload(player, week),
    )
}

/**
 * Whether to tell him to ease off.
 *
 * Not a rule the model enforces — he is allowed to flog himself — but a tired
 * player training hard gains less and breaks down more, and a screen that let
 * him do it without saying so would be hiding the mechanism rather than
 * modelling it.
 */
private fun overload(player: Player, week: TrainingWeek): String? = when {
    player.state.isInjured -> "Injured. Training now will not help and may set the rehab back."
    player.state.fatigue > 0.75 && week.intensity > 0.6 ->
        "Heavily fatigued. A hard week here will cost more than it buys."
    player.state.fatigue > 0.55 && week.intensity > 0.85 ->
        "Tired. Consider a lighter week."
    else -> null
}
