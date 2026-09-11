package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.FatigueTuning
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.PlayerState
import kotlin.math.pow

/**
 * What a player actually did in a match.
 *
 * Fatigue is charged on this rather than on a match count, so that a Test
 * seamer's twenty-eight-over day and a T20 opener's forty balls cost what they
 * really cost. A per-match constant would make a rest-day decision meaningless.
 */
data class MatchWorkload(
    val oversBowledPace: Double = 0.0,
    val oversBowledSpin: Double = 0.0,
    val ballsFaced: Int = 0,
    val fieldingHours: Double = 0.0,
) {
    init {
        require(oversBowledPace >= 0.0 && oversBowledPace.isFinite()) { "oversBowledPace $oversBowledPace" }
        require(oversBowledSpin >= 0.0 && oversBowledSpin.isFinite()) { "oversBowledSpin $oversBowledSpin" }
        require(ballsFaced >= 0) { "ballsFaced $ballsFaced cannot be negative" }
        require(fieldingHours >= 0.0 && fieldingHours.isFinite()) { "fieldingHours $fieldingHours" }
    }
}

/**
 * Workload and recovery.
 *
 * See docs/CAREER_MODEL.md §5.
 */
object FatigueModel {

    /**
     * Fatigue added by one appearance.
     *
     * The flat appearance cost is separate from the workload terms on purpose:
     * a player who turns up, fields for a session and does nothing else is
     * still more tired than one who stayed at home, and that is what makes a
     * congested calendar bite even for a specialist batter.
     */
    fun afterMatch(state: PlayerState, workload: MatchWorkload, tuning: FatigueTuning): PlayerState {
        val added = tuning.fatiguePerMatchAppearance +
            workload.oversBowledPace * tuning.fatiguePerOverPace +
            workload.oversBowledSpin * tuning.fatiguePerOverSpin +
            workload.ballsFaced * tuning.fatiguePerBallFaced +
            workload.fieldingHours * tuning.fatiguePerFieldingHour
        return state.withFatigueChange(added)
    }

    /**
     * Recovery over [days] of rest.
     *
     * Exponential in days rather than linear: coming down from 0.8 is fast and
     * the last 0.1 is slow, which is why a player can look fine after a week
     * off and still be short of his best in a fifth Test.
     */
    fun rest(state: PlayerState, days: Int, attributes: Attributes, tuning: FatigueTuning): PlayerState {
        require(days >= 0) { "rest days $days cannot be negative" }
        if (days == 0) return state
        val rate = tuning.recoveryPerRestDay * recoveryScale(attributes, tuning)
        val remaining = (1.0 - rate).coerceIn(0.0, 1.0).pow(days)
        return state.copy(fatigue = state.fatigue * remaining)
    }

    /**
     * How fast this player clears fatigue, relative to an average one.
     *
     * Fitness and stamina weighted equally: one is how well conditioned he is,
     * the other is how long he can keep going, and both matter to overnight
     * recovery. A player at 100 on both recovers (1 + fitnessRecoveryRange)
     * times as fast as one at 1.
     */
    fun recoveryScale(attributes: Attributes, tuning: FatigueTuning): Double {
        val fitness = attributes.normalised(Attribute.FITNESS)
        val stamina = attributes.normalised(Attribute.STAMINA)
        return 1.0 + tuning.fitnessRecoveryRange * ((fitness + stamina) / 2.0 - 0.5) * 2.0
    }
}
