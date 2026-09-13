package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.config.RetirementTuning
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.SimRandom

/** One thing pushing a cricketer towards the end, and how hard. */
data class RetirementPressure(
    val reason: String,
    /** How much of the total this accounts for. */
    val weight: Double,
) {
    val isReal: Boolean get() = weight > 0.0
}

/** How close a cricketer is to walking away, and why. */
data class RetirementProspect(
    /** Strongest first. Empty when he is nowhere near it. */
    val pressures: List<RetirementPressure>,
    /** 0 to 1. Not a probability of retiring *this* season so much as how done he is. */
    val likelihood: Double,
    /**
     * Whether the decision has been taken out of his hands.
     *
     * Nobody at any level will have him, or his body has. The one case where
     * "he chose to retire" would be a lie.
     */
    val forced: Boolean,
) {
    /** The reason he would give, or null when nobody would ask. */
    val headline: String? get() = pressures.firstOrNull { it.isReal }?.reason
}

/**
 * The end of a career.
 *
 * Deliberately not `WorldAgeing.retires`, which is a hazard roll behind the
 * scenes: a name comes off a squad list and nobody asks why. This is the last
 * screen of somebody's career, and it owes him an answer. Every term produces a
 * **sentence**, so that "you are thirty-seven, you have not played a first-class
 * match in two seasons, and two hamstrings have left damage that is not going
 * to mend" is something the game can say.
 *
 * The pressures add. None of them alone ends a career at a plausible age, which
 * is the point: cricketers stop for two or three reasons at once.
 *
 * See docs/CAREER_MODEL.md §12.
 */
object Retirement {

    /**
     * How done he is, and why.
     *
     * [peakStandard] is the best he ever was, on the same 0-1 scale as
     * `Selection.standardFor` — the career layer keeps it, because a player is
     * driven out by being unable to do what he *could* do, and that needs a
     * memory of what that was.
     */
    fun consider(
        player: Player,
        age: Int,
        level: LadderLevel,
        peakStandard: Double,
        seasonsWithoutCricket: Int,
        careerMatches: Int,
        /**
         * Attribute points lost for good to past injuries, across the career.
         *
         * Passed in rather than read off the player because it lives nowhere on
         * him: `Injury.permanentDamage` records what one injury took, and the
         * running total is the career layer's memory, like [peakStandard].
         */
        accumulatedDamage: Int,
        nowhereLeftToPlay: Boolean,
        tuning: CareerTuning = CareerTuning.DEFAULT,
    ): RetirementProspect {
        val config = tuning.retirement
        if (nowhereLeftToPlay) {
            return RetirementProspect(
                pressures = listOf(RetirementPressure("No side at any level will have you", 1.0)),
                likelihood = 1.0,
                forced = true,
            )
        }
        if (age < config.earliestAge) {
            return RetirementProspect(emptyList(), likelihood = 0.0, forced = false)
        }
        if (age >= config.latestAge) {
            return RetirementProspect(
                pressures = listOf(RetirementPressure("You are $age. Nobody goes on from here", 1.0)),
                likelihood = 1.0,
                forced = true,
            )
        }

        val raw = listOf(
            agePressure(age, config),
            declinePressure(player, peakStandard, level, config),
            idlePressure(seasonsWithoutCricket, config),
            damagePressure(accumulatedDamage, config),
            fulfilmentPressure(careerMatches, config),
        ).filter { it.isReal }

        val total = raw.sumOf { it.weight }
        val likelihood = total.coerceIn(0.0, 1.0)
        // Reported as shares of the whole, so a screen can say which of them is
        // doing the work rather than printing five raw numbers.
        val pressures = raw
            .sortedByDescending { it.weight }
            .map { it.copy(weight = if (total <= 0.0) 0.0 else it.weight / total) }

        return RetirementProspect(pressures, likelihood, forced = false)
    }

    /** Whether he actually goes, this off-season. */
    fun decide(prospect: RetirementProspect, random: SimRandom): Boolean =
        prospect.forced || random.chance(prospect.likelihood)

    private fun agePressure(age: Int, tuning: RetirementTuning) = RetirementPressure(
        reason = "You are $age",
        weight = tuning.agePressure * (age - tuning.earliestAge),
    )

    /**
     * Being worse than you were.
     *
     * Measured against his own peak rather than against the standard of his
     * rung, because that is how a cricketer experiences it: the problem is not
     * that the bowling got better, it is that the hands did not move like that
     * before.
     */
    private fun declinePressure(
        player: Player,
        peakStandard: Double,
        level: LadderLevel,
        tuning: RetirementTuning,
    ): RetirementPressure {
        val now = Selection.standardFor(player, MatchFormat.LIST_A)
        val lost = ((peakStandard - now) / peakStandard.coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
        // `level.standard` is the standard of the *cricket*, not a bar every
        // player in it clears - an international side is 0.92 and most of it is
        // under that. Saying "no longer up to this level" on that alone told a
        // forty-two-year-old averaging fifty-three that he had been found out.
        // It needs a real gap, and it needs him to have fallen from somewhere.
        val clearlyBelow = now < level.standard - BELOW_LEVEL_MARGIN && lost > 0.05
        return RetirementPressure(
            reason = if (clearlyBelow) {
                "You are no longer up to this level"
            } else {
                "You are not the player you were"
            },
            weight = tuning.declinePressure * lost,
        )
    }

    private fun idlePressure(seasons: Int, tuning: RetirementTuning) = RetirementPressure(
        reason = when (seasons) {
            0 -> ""
            1 -> "You did not play at all last season"
            else -> "You have not played in $seasons seasons"
        },
        weight = tuning.idlePressure * seasons,
    )

    /**
     * A body that stopped mending.
     *
     * Not the injury he has now — that heals. The ones that did not.
     */
    private fun damagePressure(accumulatedDamage: Int, tuning: RetirementTuning): RetirementPressure {
        val damage = (accumulatedDamage / MAX_MEANINGFUL_DAMAGE).coerceIn(0.0, 1.0)
        return RetirementPressure(
            reason = "Old injuries that are not going to mend",
            weight = tuning.damagePressure * damage,
        )
    }

    /**
     * A career that got where it was going.
     *
     * Cuts both ways, and the second half is the commoner one: a player still
     * chasing a first cap hangs on well past the point of sense.
     */
    private fun fulfilmentPressure(matches: Int, tuning: RetirementTuning): RetirementPressure {
        val done = (matches.toDouble() / tuning.fulfilledAtMatches).coerceIn(0.0, 1.0)
        return RetirementPressure(
            reason = "You have had the career you set out to have",
            weight = tuning.fulfilmentPressure * done,
        )
    }

    /**
     * Permanent damage at which a body is as broken as the model tracks.
     *
     * Twenty points off the attributes he was born with. Beyond that the
     * pressure is already total and counting further would not change anything.
     */
    private const val MAX_MEANINGFUL_DAMAGE = 20.0

    /**
     * How far under a rung's standard counts as being found out.
     *
     * A tenth. Below the rung by less than that is most of any side, and
     * telling a player he is finished on that basis is simply wrong.
     */
    private const val BELOW_LEVEL_MARGIN = 0.10
}
