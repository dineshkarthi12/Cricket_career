package com.cricketcareer.engine.config

import com.cricketcareer.engine.model.world.MatchFormat

/**
 * How batters approach each format.
 *
 * The physics does not change between formats — a cover drive is a cover drive
 * and a pitch is a pitch. What changes is **what players are trying to do**, and
 * that is the only thing that should be format-dependent. Encoding the
 * difference anywhere else (in the movement model, say, or in a per-format
 * "scoring rate") would be making the simulation lie about why Test cricket is
 * slower than Twenty20.
 *
 * [baseRisk] is where an unremarkable batter starts. The phase multipliers move
 * him through the innings: a Twenty20 attacks from the first ball and
 * accelerates hard, a fifty-over innings has a long flat middle, and a Test
 * innings barely changes at all.
 */
data class FormatIntent(
    val baseRisk: Double,
    val earlyPhaseRisk: Double,
    val middlePhaseRisk: Double,
    val latePhaseRisk: Double,
    /**
     * How much a batter values leaving the ball, relative to a Test opener.
     *
     * Nobody leaves in a Twenty20 because a delivery is 1/120th of the innings.
     * In a Test it costs nothing and may well be the right shot.
     */
    val leavePatience: Double,
    /** What leaving a ball costs, in the same units as a shot's utility. */
    val leaveBallCost: Double,

    /**
     * Extra willingness to scamper a tight single.
     *
     * Running between the wickets is genuinely more urgent in a short format: a
     * Twenty20 pair will take one the same batters would refuse in a Test,
     * because a dot ball costs them far more. It is a difference of intent, not
     * of physics, so it belongs here rather than in the fielding model.
     */
    val singleAppetiteBonus: Double,
)

/** Per-format batting intent, looked up by format id. */
data class FormatIntentTuning(
    val byFormatId: Map<String, FormatIntent> = mapOf(
        MatchFormat.T20.id to FormatIntent(
            baseRisk = 0.62,
            earlyPhaseRisk = 0.28,
            middlePhaseRisk = 0.52,
            latePhaseRisk = 0.98,
            leavePatience = 0.20,
            leaveBallCost = 2.6,
            singleAppetiteBonus = 0.88,
        ),
        MatchFormat.FORTY_OVER.id to FormatIntent(
            baseRisk = 0.28,
            earlyPhaseRisk = 0.02,
            middlePhaseRisk = 0.16,
            latePhaseRisk = 0.66,
            leavePatience = 0.40,
            leaveBallCost = 2.1,
            singleAppetiteBonus = 0.02,
        ),
        MatchFormat.LIST_A.id to FormatIntent(
            baseRisk = 0.30,
            earlyPhaseRisk = 0.02,
            middlePhaseRisk = 0.18,
            latePhaseRisk = 0.86,
            leavePatience = 0.45,
            leaveBallCost = 2.0,
            singleAppetiteBonus = 0.18,
        ),
    ),

    /**
     * Multi-day cricket, where there is no clock on the innings at all.
     *
     * A Test batter's job is to not get out; runs are what happens while he is
     * doing it. That single difference is what produces a 70% dot rate out of
     * the same shot-selection maths that gives Twenty20 a 36% one.
     */
    val multiDay: FormatIntent = FormatIntent(
        baseRisk = 0.05,
        earlyPhaseRisk = -0.04,
        middlePhaseRisk = 0.00,
        latePhaseRisk = 0.02,
        leavePatience = 1.00,
        leaveBallCost = 1.1,
        singleAppetiteBonus = -0.78,
    ),
) {
    fun forFormat(format: MatchFormat): FormatIntent =
        if (format.isMultiDay) multiDay else byFormatId[format.id] ?: byFormatId.getValue(MatchFormat.LIST_A.id)
}
