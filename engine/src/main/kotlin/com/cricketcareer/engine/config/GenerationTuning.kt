package com.cricketcareer.engine.config

import com.cricketcareer.engine.model.world.LadderLevel

/**
 * Every constant the player generator uses.
 *
 * Per CLAUDE.md §5 no magic number lives at a call site: each one is here, with
 * a comment saying what it is and what moving it does, so the world's shape can
 * be tuned without reading the generator.
 */
data class GenerationTuning(
    /**
     * Mean potential, in attribute points, of a player who belongs at each
     * level. This is the pyramid: it is what makes an international-class
     * cricketer rare and a college player ordinary.
     *
     * Raising a level's mean makes that standard of cricket better across the
     * board, which lifts everyone's bowling averages and lowers batting ones.
     */
    val potentialMeanByLevel: Map<LadderLevel, Double> = mapOf(
        LadderLevel.COLLEGE to 34.0,
        LadderLevel.DISTRICT_CLUB to 43.0,
        LadderLevel.STATE_AGE_GROUP to 52.0,
        LadderLevel.STATE_FIRST_CLASS to 62.0,
        LadderLevel.STATE_WHITE_BALL to 61.0,
        LadderLevel.NATIONAL_A to 72.0,
        LadderLevel.FRANCHISE_T20 to 74.0,
        LadderLevel.INTERNATIONAL to 79.0,
    ),

    /**
     * Spread of potential within a level.
     *
     * Deliberately wide. A first-class squad is not eleven interchangeable
     * 62s — it holds a couple of future internationals, a core of honest pros
     * and a tail. Narrowing this is the fastest way to produce the failure
     * docs/CALIBRATION.md warns about, where every batting average converges
     * on 30.
     */
    val potentialSpread: Double = 9.5,

    /**
     * How much of a player's potential is realised at his peak, on average.
     * Below 1.0 because most players never quite get there: injury, coaching,
     * opportunity. This is what makes `potential` a soft ceiling rather than a
     * promise.
     */
    val realisationMean: Double = 0.93,

    /** Spread of realisation. Two players of equal potential do not arrive equally. */
    val realisationSpread: Double = 0.07,

    /**
     * Correlation between an attribute group and the player's overall quality,
     * as a weight in [0, 1]. At 1.0 every group tracks overall ability exactly
     * and nobody has a weakness; at 0 the groups are unrelated and a player is
     * a bag of random numbers.
     *
     * 0.72 leaves room for the specialist — a wonderful player of spin who
     * cannot handle pace — while keeping good players broadly good.
     */
    val groupCoherence: Double = 0.72,

    /** Spread of a group's centre around the player's overall quality, in points. */
    val groupSpread: Double = 7.0,

    /**
     * Spread of an individual attribute around its group centre, in points.
     *
     * This is what gives a player a shape rather than a level: the batter whose
     * pull shot is a liability, the seamer who swings it but cannot bowl at the
     * death.
     */
    val attributeSpread: Double = 6.5,

    /**
     * Share of batters who are left-handed.
     *
     * Around 25%, well above the ~10% in the general population: left-handers
     * are over-represented in cricket, partly through the advantage of the
     * angle and partly through selection at junior level.
     */
    val leftHandedShare: Double = 0.25,

    /** Youngest and oldest a generated professional can be. */
    val minAge: Int = 16,
    val maxAge: Int = 41,

    /** Mean and spread of squad age at each level, in years. */
    val ageMeanByLevel: Map<LadderLevel, Double> = mapOf(
        LadderLevel.COLLEGE to 19.5,
        LadderLevel.DISTRICT_CLUB to 24.0,
        LadderLevel.STATE_AGE_GROUP to 18.5,
        LadderLevel.STATE_FIRST_CLASS to 26.5,
        LadderLevel.STATE_WHITE_BALL to 26.0,
        LadderLevel.NATIONAL_A to 24.5,
        LadderLevel.FRANCHISE_T20 to 27.5,
        LadderLevel.INTERNATIONAL to 28.5,
    ),

    val ageSpread: Double = 4.0,

    /**
     * Headroom above realised ability that a player's potential sits at, as a
     * multiplier. Applied so that a 19-year-old at a level has visible room to
     * grow and a 30-year-old does not.
     */
    val potentialFloorAboveCurrent: Double = 1.02,
) {
    init {
        LadderLevel.ALL.forEach { level ->
            require(potentialMeanByLevel.containsKey(level)) { "no potential mean for $level" }
            require(ageMeanByLevel.containsKey(level)) { "no age mean for $level" }
        }
        require(potentialSpread > 0.0) { "potentialSpread must be positive" }
        require(groupCoherence in 0.0..1.0) { "groupCoherence must be in 0..1" }
        require(leftHandedShare in 0.0..1.0) { "leftHandedShare must be in 0..1" }
        require(minAge < maxAge) { "minAge must be below maxAge" }
    }

    companion object {
        val DEFAULT: GenerationTuning = GenerationTuning()
    }
}
