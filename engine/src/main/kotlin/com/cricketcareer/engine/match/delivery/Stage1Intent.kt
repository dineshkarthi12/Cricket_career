package com.cricketcareer.engine.match.delivery

import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.rng.SimRandom
import kotlin.math.exp

/**
 * A bowler's standing plan for one batter.
 *
 * Bowlers bowl to a plan; they do not resample their intentions every ball.
 * Holding a plan across an over makes bowler intent autocorrelated, which is
 * both realistic and what makes "he's worked me out" legible to the player.
 */
data class BowlingPlan(
    val preferredLength: LengthBand,
    val preferredLine: LineBand,
    /** How often this plan calls for a short ball. */
    val shortBallRate: Double,
    /** How much the bowler believes his read of this batter, 0 to 1. */
    val beliefStrength: Double,
) {
    companion object {
        /** The default plan before a bowler has any read: good length, fourth stump. */
        val NEUTRAL: BowlingPlan = BowlingPlan(
            preferredLength = LengthBand.GOOD,
            preferredLine = LineBand.FOURTH_STUMP,
            shortBallRate = 0.12,
            beliefStrength = 0.0,
        )
    }
}

/** What the bowler was trying to bowl. */
data class BowlerIntent(
    val deliveryType: DeliveryType,
    val lengthBand: LengthBand,
    val lineBand: LineBand,
    val targetLengthMetres: Double,
    val targetLineMetres: Double,
    val targetPaceKph: Double,
)

/**
 * Stage 1 — bowler intent.
 *
 * Chosen sequentially (type, then length, then line) rather than as one softmax
 * over the full cross product. Three small softmaxes cost a fraction of 150
 * candidate evaluations per ball, which matters against the per-ball budget in
 * docs/ARCHITECTURE.md §7, and it reads the way a bowler actually thinks: what
 * am I bowling, how full, and where.
 */
object Stage1Intent {

    fun choose(context: DeliveryContext, rng: SimRandom): BowlerIntent {
        val tuning = context.tuning
        val temperature = temperatureFor(context)

        val type = chooseType(context, rng, temperature)
        val length = chooseLength(context, type, rng, temperature)
        val line = chooseLine(context, type, rng, temperature)

        val basePace = context.bowler.bowlingStyle.typicalPaceKph *
            (0.86 + 0.28 * context.bowlerSkill(Attribute.PACE))
        val targetPace = basePace * type.paceFactor

        // A bouncer and a yorker are length instructions, not suggestions.
        val targetLength = when (type) {
            DeliveryType.BOUNCER -> LengthBand.SHORT.centreMetres + 1.2
            DeliveryType.YORKER -> LengthBand.YORKER.centreMetres
            else -> lengthTargetFor(length, context.bowlerIsSpin)
        }

        return BowlerIntent(
            deliveryType = type,
            lengthBand = length,
            lineBand = line,
            targetLengthMetres = targetLength,
            targetLineMetres = line.centreMetres,
            targetPaceKph = targetPace,
        ).also { require(tuning.intent.baseTemperature > 0.0) }
    }

    /**
     * A disciplined bowler is *predictable* — low temperature — which is a real
     * trade-off: the batter can set up against him.
     */
    private fun temperatureFor(context: DeliveryContext): Double {
        val intent = context.tuning.intent
        return intent.baseTemperature *
            (1.0 + intent.disciplineTemperatureWeight * (1.0 - context.bowlerSkill(Attribute.DISCIPLINE))) *
            (1.0 + intent.aggressionTemperatureWeight * context.bowlerSkill(Attribute.AGGRESSION))
    }

    private fun chooseType(context: DeliveryContext, rng: SimRandom, temperature: Double): DeliveryType {
        val available = if (context.bowlerIsSpin) DeliveryType.SPIN_TYPES else DeliveryType.PACE_TYPES
        val utilities = DoubleArray(available.size) { i -> typeUtility(context, available[i]) }
        return rng.pickWeighted(available, softmax(utilities, temperature))
    }

    private fun typeUtility(context: DeliveryContext, type: DeliveryType): Double {
        val variations = context.bowlerSkill(Attribute.VARIATIONS)
        val deathOvers = context.situation.totalOvers?.let {
            context.situation.over >= it * 0.8
        } ?: false

        // The stock ball is most of what anyone bowls. Everything else has to
        // earn its place, and costs more the worse the bowler is at variations.
        val base = when (type) {
            DeliveryType.STOCK -> 1.60
            DeliveryType.BOUNCER ->
                // A bouncer is worth more to a batter who cannot handle one, and
                // only if there is somebody back for the top edge.
                0.30 + 0.9 * context.bowlerSkill(Attribute.BOUNCE) +
                    0.8 * (1.0 - context.strikerSkill(Attribute.SHORT_BALL_PLAY)) +
                    context.plan.shortBallRate * context.plan.beliefStrength
            DeliveryType.YORKER ->
                0.10 + 1.4 * context.bowlerSkill(Attribute.DEATH_BOWLING) + if (deathOvers) 1.1 else -0.5
            DeliveryType.SLOWER_BALL ->
                -0.25 + 1.3 * variations + if (deathOvers) 0.7 else 0.0
            DeliveryType.CUTTER -> -0.35 + 1.1 * variations
            DeliveryType.FLIGHTED -> 0.55 + 0.6 * context.bowlerSkill(Attribute.DRIFT)
            DeliveryType.TOP_SPINNER -> -0.20 + 0.9 * variations
            DeliveryType.GOOGLY, DeliveryType.DOOSRA, DeliveryType.CARROM ->
                // Only worth it if he has the variation, and it is wasted on a
                // batter who reads spin well.
                -0.90 + 1.7 * variations + 0.6 * (1.0 - context.strikerSkill(Attribute.SPIN_PLAY))
            DeliveryType.ARM_BALL -> -0.30 + 0.9 * variations
            DeliveryType.QUICKER_BALL -> -0.40 + 0.9 * variations
        }

        // A finger-spinner cannot bowl a googly and a wrist-spinner does not
        // bowl a doosra or an arm ball; the action decides which deceptions are
        // even on the table.
        val style = context.bowler.bowlingStyle
        val styleAllows = when (type) {
            DeliveryType.GOOGLY -> style.wristSpin
            DeliveryType.DOOSRA, DeliveryType.CARROM, DeliveryType.ARM_BALL -> style.isFingerSpin
            else -> true
        }
        return if (styleAllows) base else -6.0
    }

    private fun chooseLength(
        context: DeliveryContext,
        type: DeliveryType,
        rng: SimRandom,
        temperature: Double,
    ): LengthBand {
        if (type == DeliveryType.BOUNCER) return LengthBand.SHORT
        if (type == DeliveryType.YORKER) return LengthBand.YORKER

        val options = if (context.bowlerIsSpin) SPIN_LENGTHS else PACE_LENGTHS
        val utilities = DoubleArray(options.size) { i -> lengthUtility(context, options[i]) }
        return rng.pickWeighted(options, softmax(utilities, temperature))
    }

    private fun lengthUtility(context: DeliveryContext, band: LengthBand): Double {
        val plan = context.plan
        val deathOvers = context.situation.totalOvers?.let { context.situation.over >= it * 0.8 } ?: false
        val powerplay = context.situation.over < 6

        // The good length is the default for a reason: it is the hardest to
        // score off and the likeliest to take a wicket.
        var utility = when (band) {
            LengthBand.YORKER -> if (deathOvers) 0.5 else -1.4
            LengthBand.HALF_VOLLEY -> -0.7 + 0.5 * context.bowlerSkill(Attribute.SWING) * context.ball.shine
            LengthBand.FULLISH -> 0.5 + 0.6 * context.ball.shine
            LengthBand.GOOD -> 1.5
            LengthBand.BACK_OF_LENGTH -> 0.9 + if (deathOvers) 0.4 else 0.0
            LengthBand.SHORT -> -0.2 + 0.7 * (1.0 - context.strikerSkill(Attribute.SHORT_BALL_PLAY))
            else -> -3.0
        }
        // Attacking early with a hard ball; containing later.
        if (powerplay && (band == LengthBand.FULLISH || band == LengthBand.GOOD)) utility += 0.3
        if (band == plan.preferredLength) utility += 1.1 * plan.beliefStrength
        return utility
    }

    private fun chooseLine(
        context: DeliveryContext,
        type: DeliveryType,
        rng: SimRandom,
        temperature: Double,
    ): LineBand {
        val utilities = DoubleArray(LINES.size) { i -> lineUtility(context, type, LINES[i]) }
        return rng.pickWeighted(LINES, softmax(utilities, temperature))
    }

    private fun lineUtility(context: DeliveryContext, type: DeliveryType, band: LineBand): Double {
        val plan = context.plan
        // Left-handers reverse the angle, but the frame is already mirrored, so
        // the same preferences hold: attack the stumps and the channel outside
        // off, and never stray down the leg side.
        var utility = when (band) {
            LineBand.DOWN_LEG -> -4.0
            LineBand.LEG_STUMP -> -1.2
            LineBand.MIDDLE -> 0.9
            LineBand.OFF_STUMP -> 1.4
            LineBand.FOURTH_STUMP -> 1.2 + 0.6 * context.bowlerSkill(Attribute.SEAM_MOVEMENT)
            LineBand.CHANNEL -> 0.3 + 0.9 * context.bowlerSkill(Attribute.SWING) * context.ball.shine
            LineBand.WIDE_OUTSIDE_OFF -> -2.2
        }
        // The wide yorker: a death-overs tactic in white-ball cricket, aimed
        // deliberately at the edge of the tramline. Bowling there on purpose is
        // where a good share of T20 wides actually come from.
        val deathOvers = context.situation.totalOvers?.let { context.situation.over >= it * 0.8 } ?: false
        if (band == LineBand.WIDE_OUTSIDE_OFF && deathOvers && !context.format.isMultiDay) {
            utility += 3.6 * context.bowlerSkill(Attribute.DEATH_BOWLING)
        }
        // A bouncer is bowled at the body, not in the channel.
        if (type == DeliveryType.BOUNCER && (band == LineBand.MIDDLE || band == LineBand.OFF_STUMP)) utility += 1.2
        // A yorker at the stumps, or wide of them at the death.
        if (type == DeliveryType.YORKER && band == LineBand.MIDDLE) utility += 1.0
        if (band == plan.preferredLine) utility += 1.0 * plan.beliefStrength
        return utility
    }

    /**
     * Draw a fresh plan for a batter.
     *
     * [belief] is how well the bowler knows him — it starts near zero for a
     * debutant and grows with balls bowled at him, so a young player's weakness
     * against the short ball is not known on day one, it gets found out.
     */
    fun drawPlan(bowler: Player, batter: Player, belief: Double, rng: SimRandom, tuning: EngineTuning): BowlingPlan {
        require(tuning.intent.planRedrawChance > 0.0)
        // What the bowler *believes* about the batter, blended from the truth
        // towards a neutral prior by how little he knows.
        fun believed(attribute: Attribute): Double {
            val truth = batter.attributes.normalised(attribute)
            return 0.5 + (truth - 0.5) * belief
        }

        val shortBallWeakness = 1.0 - believed(Attribute.SHORT_BALL_PLAY)
        val frontFootWeakness = 1.0 - believed(Attribute.FRONTFOOT_PLAY)

        val length = if (rng.chance(0.30 + 0.45 * shortBallWeakness)) {
            LengthBand.BACK_OF_LENGTH
        } else if (rng.chance(0.35 + 0.35 * frontFootWeakness)) {
            LengthBand.FULLISH
        } else {
            LengthBand.GOOD
        }

        val line = if (bowler.bowlingStyle.isSpin) {
            rng.pickWeighted(
                listOf(LineBand.MIDDLE, LineBand.OFF_STUMP, LineBand.FOURTH_STUMP),
                doubleArrayOf(1.2, 1.4, 0.8),
            )
        } else {
            rng.pickWeighted(
                listOf(LineBand.MIDDLE, LineBand.OFF_STUMP, LineBand.FOURTH_STUMP, LineBand.CHANNEL),
                doubleArrayOf(0.8, 1.3, 1.2, 0.7),
            )
        }

        return BowlingPlan(
            preferredLength = length,
            preferredLine = line,
            shortBallRate = (0.06 + 0.35 * shortBallWeakness).coerceIn(0.0, 0.5),
            beliefStrength = belief,
        )
    }

    private fun lengthTargetFor(band: LengthBand, spin: Boolean): Double =
        if (spin) band.centreMetres - LengthBand.SPIN_SHIFT_M else band.centreMetres

    /** Softmax weights. Shifted by the maximum first so a large utility cannot overflow. */
    private fun softmax(utilities: DoubleArray, temperature: Double): DoubleArray {
        var max = Double.NEGATIVE_INFINITY
        for (u in utilities) if (u > max) max = u
        val weights = DoubleArray(utilities.size)
        for (i in utilities.indices) weights[i] = exp((utilities[i] - max) / temperature)
        return weights
    }

    private val PACE_LENGTHS = listOf(
        LengthBand.YORKER, LengthBand.HALF_VOLLEY, LengthBand.FULLISH,
        LengthBand.GOOD, LengthBand.BACK_OF_LENGTH, LengthBand.SHORT,
    )

    private val SPIN_LENGTHS = listOf(
        LengthBand.HALF_VOLLEY, LengthBand.FULLISH, LengthBand.GOOD, LengthBand.BACK_OF_LENGTH,
    )

    private val LINES = listOf(
        LineBand.LEG_STUMP, LineBand.MIDDLE, LineBand.OFF_STUMP,
        LineBand.FOURTH_STUMP, LineBand.CHANNEL,
    )
}
