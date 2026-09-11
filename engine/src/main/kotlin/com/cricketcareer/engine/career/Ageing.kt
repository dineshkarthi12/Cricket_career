package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.AgeingTuning
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.AttributeGroup
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.rng.SimRandom
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Which curve an attribute develops on.
 *
 * Deliberately *not* the same axis as [AttributeGroup]. A group says what the
 * skill is for; a development class says when it peaks. Bowling accuracy and
 * batting technique sit in different groups and improve on the same curve;
 * bowling pace and bowling accuracy sit in the same group and do not.
 */
enum class DevelopmentClass {
    /** Peaks first, declines hardest. Everything that depends on the body. */
    PHYSICAL,

    /** Built by repetition. Slow to acquire, slow to lose. */
    TECHNICAL,

    /** Still rising when the legs have gone. */
    MENTAL,
}

/**
 * Development and decline across a career.
 *
 * Applied once, on a player's birthday, rather than smeared across the year:
 * it is cheaper, it is testable, and a career summary can say "he lost a yard
 * the year he turned 33" and mean it exactly.
 *
 * See docs/CAREER_MODEL.md §3.
 */
object Ageing {

    /**
     * The curve an attribute sits on.
     *
     * Exhaustive on purpose — adding an attribute should not silently inherit
     * a default, it should fail to compile until someone decides when the new
     * skill peaks.
     */
    fun developmentClass(attribute: Attribute): DevelopmentClass = when (attribute) {
        // Pure athleticism. First to arrive, first to go.
        Attribute.PACE, Attribute.SPEED, Attribute.STAMINA, Attribute.FITNESS,
        Attribute.POWER, Attribute.RANGE_HITTING, Attribute.REFLEXES,
        Attribute.THROW_ARM, Attribute.INJURY_RESISTANCE,
        -> DevelopmentClass.PHYSICAL

        // Method. Grooved over years in the nets and in the middle.
        Attribute.TECHNIQUE, Attribute.TIMING, Attribute.FOOTWORK,
        Attribute.BACKFOOT_PLAY, Attribute.FRONTFOOT_PLAY, Attribute.SPIN_PLAY,
        Attribute.PACE_PLAY, Attribute.SHORT_BALL_PLAY, Attribute.SWING_PLAY,
        Attribute.STRIKE_ROTATION, Attribute.RUNNING_BETWEEN_WICKETS,
        Attribute.TURN, Attribute.ACCURACY, Attribute.SEAM_MOVEMENT, Attribute.SWING,
        Attribute.REVERSE_SWING, Attribute.BOUNCE, Attribute.DRIFT, Attribute.VARIATIONS,
        Attribute.DEATH_BOWLING, Attribute.NEW_BALL_SKILL, Attribute.OLD_BALL_SKILL,
        Attribute.CATCHING, Attribute.GROUND_FIELDING, Attribute.GLOVEWORK,
        Attribute.STANDING_UP, Attribute.LEG_SIDE_COLLECTION,
        -> DevelopmentClass.TECHNICAL

        // Judgement. The part of a cricketer that is still getting better at 36.
        Attribute.PATIENCE, Attribute.CONCENTRATION, Attribute.DISCIPLINE,
        Attribute.AGGRESSION, Attribute.COMPOSURE, Attribute.LEADERSHIP,
        Attribute.ADAPTABILITY,
        -> DevelopmentClass.MENTAL
    }

    fun peakAge(cls: DevelopmentClass, tuning: AgeingTuning): Double = when (cls) {
        DevelopmentClass.PHYSICAL -> tuning.peakAgePhysical
        DevelopmentClass.TECHNICAL -> tuning.peakAgeTechnical
        DevelopmentClass.MENTAL -> tuning.peakAgeMental
    }

    /**
     * One year of development for one player.
     *
     * [exposure] is the fraction of a full season's minutes the player spent on
     * the field *at or above his own standard*, in [0, 1]. A season of
     * second-XI cricket is a low number and develops almost nobody, which is
     * the mechanism that makes getting picked matter rather than just feeling
     * nice.
     *
     * [coaching] is the coaching quality of the environment he spent the year
     * in, in [0, 1].
     */
    fun ageOneYear(
        player: Player,
        age: Int,
        exposure: Double,
        coaching: Double,
        random: SimRandom,
        tuning: AgeingTuning,
    ): Attributes {
        require(exposure in 0.0..1.0) { "exposure $exposure must be in 0..1" }
        require(coaching in 0.0..1.0) { "coaching $coaching must be in 0..1" }
        if (age < tuning.minAge) return player.attributes

        val span = (Attributes.MAX - Attributes.MIN).toDouble()
        val learning = player.hidden.learningRate / Attributes.MAX.toDouble()
        // A soft ceiling. Playing above your level lets you pass it a little,
        // because a career that can never surprise its own generator is a
        // spreadsheet with a story pasted on top.
        val ceiling = player.hidden.potential + tuning.potentialOvershoot * span

        return player.attributes.map { attribute, current ->
            val cls = developmentClass(attribute)
            val peak = peakAge(cls, tuning)
            val delta = if (age <= peak && age < tuning.growthStopsAt) {
                growth(current, ceiling, span, learning, exposure, coaching, tuning)
            } else {
                -decline(age, peak, current, tuning)
            }
            val noise = random.nextGaussian() * tuning.yearlyNoiseSigma
            (current + delta + noise).roundToInt().coerceIn(Attributes.MIN, Attributes.MAX)
        }
    }

    /**
     * Growth slows to nothing as an attribute approaches its ceiling, so a
     * player near his potential improves in small change while a raw one of the
     * same age improves fast. Multiplying the four terms rather than adding
     * them means any one of them being absent stops development: no headroom,
     * no exposure, or no learning rate each independently flatten the year.
     */
    private fun growth(
        current: Int,
        ceiling: Double,
        span: Double,
        learning: Double,
        exposure: Double,
        coaching: Double,
        tuning: AgeingTuning,
    ): Double {
        val headroom = ((ceiling - current) / span).coerceAtLeast(0.0).pow(tuning.headroomExponent)
        val exposureTerm = min(1.0, exposure / tuning.fullExposureMinutes)
        // Coaching is a multiplier on a floor rather than a raw multiplier:
        // a player with no coaching at all still improves by playing.
        val coachingTerm = 0.55 + 0.45 * coaching
        return tuning.growthPerYearAtFullHeadroom * headroom * learning * exposureTerm * coachingTerm
    }

    /**
     * Quadratic in years past peak, so the first two years past peak cost
     * almost nothing and the tenth is brutal — nobody notices a 31-year-old
     * slowing down and everybody notices a 37-year-old.
     *
     * Scaled by what the player has left to lose. Absolute decline takes the
     * same points off a 90 and a 20, which over a long career wipes out
     * anything that did not start high: a bowler with power 26 at 26 reaches
     * the floor by 39, having lost a hundred per cent of an attribute the same
     * way a great player loses a third of his. You do not lose what you never
     * had.
     */
    private fun decline(age: Int, peak: Double, current: Int, tuning: AgeingTuning): Double {
        val past = age - peak
        if (past <= 0.0) return 0.0
        val headroomToLose = (current - Attributes.MIN).toDouble() /
            (tuning.declineReferenceRating - Attributes.MIN)
        return tuning.declinePerYearAtTenPastPeak * (past * past) / 100.0 * headroomToLose
    }

    /**
     * Permanent damage from a severe injury, applied when rehab ends.
     *
     * Physical only: a blown-out knee costs pace and speed, not composure. The
     * magnitude is [points] on every physical attribute, which is harsher than
     * spreading it around and is meant to be — this is the event that ends
     * careers.
     */
    fun applyPermanentDamage(attributes: Attributes, points: Int): Attributes {
        require(points >= 0) { "damage $points cannot be negative" }
        return attributes.map { attribute, current ->
            if (developmentClass(attribute) == DevelopmentClass.PHYSICAL) {
                (current - points).coerceIn(Attributes.MIN, Attributes.MAX)
            } else {
                current
            }
        }
    }

    /** True when every attribute is within [tolerance] points of the other set. Test support. */
    internal fun closeTo(a: Attributes, b: Attributes, tolerance: Int): Boolean =
        Attribute.ALL.all { abs(a[it] - b[it]) <= tolerance }
}
