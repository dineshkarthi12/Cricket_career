package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.TrainingTuning
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerState
import com.cricketcareer.engine.rng.SimRandom
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What a player can spend a training week on.
 *
 * Focus areas are coarser than attributes on purpose: a week of "batting
 * technique" moves technique, footwork and the two foot-play attributes
 * together, because that is what a week in the nets actually does. Letting the
 * user allocate to single attributes would turn a coaching decision into a
 * spreadsheet optimisation.
 */
enum class TrainingFocus(val displayName: String, val attributes: List<Attribute>) {
    BATTING_TECHNIQUE(
        "Batting technique",
        listOf(
            Attribute.TECHNIQUE, Attribute.FOOTWORK,
            Attribute.FRONTFOOT_PLAY, Attribute.BACKFOOT_PLAY, Attribute.TIMING,
        ),
    ),
    BATTING_POWER(
        "Power hitting",
        listOf(Attribute.POWER, Attribute.RANGE_HITTING, Attribute.TIMING),
    ),
    PLAYING_SPIN(
        "Playing spin",
        listOf(Attribute.SPIN_PLAY, Attribute.FOOTWORK, Attribute.PATIENCE),
    ),
    PLAYING_PACE(
        "Playing pace",
        listOf(Attribute.PACE_PLAY, Attribute.SHORT_BALL_PLAY, Attribute.SWING_PLAY),
    ),
    SEAM_BOWLING(
        "Seam bowling",
        listOf(
            Attribute.ACCURACY, Attribute.SEAM_MOVEMENT, Attribute.SWING,
            Attribute.NEW_BALL_SKILL, Attribute.OLD_BALL_SKILL,
        ),
    ),
    SPIN_BOWLING(
        "Spin bowling",
        listOf(Attribute.TURN, Attribute.DRIFT, Attribute.ACCURACY, Attribute.VARIATIONS),
    ),
    DEATH_BOWLING(
        "Death bowling",
        listOf(Attribute.DEATH_BOWLING, Attribute.VARIATIONS, Attribute.ACCURACY),
    ),
    FIELDING(
        "Fielding",
        listOf(Attribute.CATCHING, Attribute.GROUND_FIELDING, Attribute.THROW_ARM, Attribute.REFLEXES),
    ),
    KEEPING(
        "Wicketkeeping",
        listOf(Attribute.GLOVEWORK, Attribute.STANDING_UP, Attribute.LEG_SIDE_COLLECTION, Attribute.REFLEXES),
    ),
    FITNESS(
        "Fitness",
        listOf(Attribute.FITNESS, Attribute.STAMINA, Attribute.SPEED, Attribute.INJURY_RESISTANCE),
    ),
    MENTAL(
        "Mental",
        listOf(Attribute.CONCENTRATION, Attribute.COMPOSURE, Attribute.DISCIPLINE, Attribute.ADAPTABILITY),
    ),

    /**
     * Not a skill. Points spent here buy recovery instead of improvement, and
     * they are the reason maximum intensity forever is not the right answer.
     */
    REST("Rest", emptyList()),
}

/**
 * A week's allocation: focus area to points, summing to 100.
 *
 * Validated on construction rather than normalised silently, because a plan
 * that does not add up is a bug in the caller and quietly rescaling it would
 * hide the fact that some of the week went nowhere.
 */
data class TrainingWeek(val allocation: Map<TrainingFocus, Int>) {
    init {
        require(allocation.values.all { it >= 0 }) { "training points cannot be negative: $allocation" }
        val total = allocation.values.sum()
        require(total == TOTAL_POINTS) { "a training week is $TOTAL_POINTS points, got $total" }
    }

    fun pointsFor(focus: TrainingFocus): Int = allocation[focus] ?: 0

    /** Fraction of the week spent on something other than rest. */
    val intensity: Double get() = (TOTAL_POINTS - pointsFor(TrainingFocus.REST)).toDouble() / TOTAL_POINTS

    companion object {
        const val TOTAL_POINTS: Int = 100

        /** Everything on one thing. The test control and a reasonable off-season default. */
        fun allOn(focus: TrainingFocus): TrainingWeek = TrainingWeek(mapOf(focus to TOTAL_POINTS))
    }
}

/**
 * What a week of training did.
 *
 * [carry] is the fractional progress not yet worth a whole attribute point. It
 * has to be threaded from one week to the next by the caller: a week's gain is
 * a fifth of a point, so rounding each week independently would round every
 * week to nothing and training would do literally nothing at all. Carrying it
 * is what makes eight weeks add up to something an integer attribute can show.
 */
data class TrainingResult(
    val attributes: Attributes,
    val state: PlayerState,
    val carry: Map<Attribute, Double> = emptyMap(),
)

/**
 * Training.
 *
 * See docs/CAREER_MODEL.md §7.
 *
 * The `(1 - fatigue)` term is the whole design. Without it the optimal plan is
 * "everything, always", the rest allocation is dead weight and the screen is a
 * single button. With it, a tired player gets almost nothing from a hard week
 * and takes a much higher injury roll for the privilege.
 */
object TrainingModel {

    fun train(
        player: Player,
        week: TrainingWeek,
        coaching: Double,
        random: SimRandom,
        tuning: TrainingTuning,
        carry: Map<Attribute, Double> = emptyMap(),
    ): TrainingResult {
        require(coaching in 0.0..1.0) { "coaching $coaching must be in 0..1" }

        val span = (Attributes.MAX - Attributes.MIN).toDouble()
        val learning = player.hidden.learningRate / Attributes.MAX.toDouble()
        val ceiling = player.hidden.potential.toDouble()
        val effort = (1.0 - player.state.fatigue).coerceAtLeast(0.0)
        val coachingTerm = 0.55 + 0.45 * coaching

        // Points landing on each attribute. An attribute covered by two focus
        // areas in the same week gets both, which is why "playing spin" and
        // "batting technique" together move footwork twice.
        val pointsPerAttribute = LinkedHashMap<Attribute, Int>()
        for (focus in TrainingFocus.entries) {
            val points = week.pointsFor(focus)
            if (points == 0) continue
            for (attribute in focus.attributes) {
                pointsPerAttribute[attribute] = (pointsPerAttribute[attribute] ?: 0) + points
            }
        }

        // Whole points move the attribute; the remainder is carried. A week is
        // worth about a fifth of a point, so rounding here without a carry
        // would round every week to zero and training would do nothing.
        val nextCarry = LinkedHashMap<Attribute, Double>()
        val attributes = player.attributes.map { attribute, current ->
            val points = pointsPerAttribute[attribute] ?: return@map current
            val headroom = ((ceiling - current) / span).coerceAtLeast(0.0).pow(tuning.headroomExponent)
            val weeks = points.toDouble() / TrainingWeek.TOTAL_POINTS
            val noise = random.nextGaussian() * tuning.weeklyNoiseSigma
            // A bad week is a week that taught you nothing, never a week that
            // made you worse: an attribute is a skill, and skills do not
            // un-learn themselves in seven days.
            val gain = (tuning.pointsPerFullWeek * weeks * headroom * learning * coachingTerm * effort + noise)
                .coerceAtLeast(0.0)
            val total = (carry[attribute] ?: 0.0) + gain
            val whole = floor(total).toInt()
            nextCarry[attribute] = total - whole
            current + whole
        }

        val restShare = week.pointsFor(TrainingFocus.REST).toDouble() / TrainingWeek.TOTAL_POINTS
        val added = tuning.fatiguePerFullWeek * week.intensity
        val cleared = player.state.fatigue * tuning.restWeekRecovery * restShare
        val state = player.state.copy(
            fatigue = (player.state.fatigue + added - cleared).coerceIn(0.0, 1.0),
        )
        return TrainingResult(attributes, state, nextCarry)
    }

    /**
     * Expected gain from repeating [week] for [weeks] weeks, ignoring noise and
     * ignoring the fatigue the weeks themselves generate.
     *
     * For the projection on the training screen. Deliberately optimistic about
     * fatigue and honest about headroom, and labelled as an estimate wherever
     * it is shown: the real answer depends on what else the player does.
     */
    fun project(
        player: Player,
        week: TrainingWeek,
        coaching: Double,
        weeks: Int,
        attribute: Attribute,
        tuning: TrainingTuning,
    ): Int {
        require(weeks >= 0) { "weeks $weeks cannot be negative" }
        val span = (Attributes.MAX - Attributes.MIN).toDouble()
        val learning = player.hidden.learningRate / Attributes.MAX.toDouble()
        val ceiling = player.hidden.potential.toDouble()
        val effort = (1.0 - player.state.fatigue).coerceAtLeast(0.0)
        val coachingTerm = 0.55 + 0.45 * coaching
        val points = TrainingFocus.entries
            .filter { attribute in it.attributes }
            .sumOf { week.pointsFor(it) }
            .toDouble() / TrainingWeek.TOTAL_POINTS

        var current = player.attributes[attribute].toDouble()
        repeat(weeks) {
            val headroom = ((ceiling - current) / span).coerceAtLeast(0.0).pow(tuning.headroomExponent)
            current += tuning.pointsPerFullWeek * points * headroom * learning * coachingTerm * effort
        }
        return current.roundToInt().coerceIn(Attributes.MIN, Attributes.MAX)
    }
}
