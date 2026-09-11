package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.SelectionTuning
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.BowlingKind
import com.cricketcareer.engine.model.player.InjurySeverity
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.player.RoleDiscipline
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.rng.SimRandom

/**
 * A selector's temperament.
 *
 * Drawn per team rather than fixed, because the thing that makes a career feel
 * like it is happening *to* someone is that the panel which dropped him is not
 * the same panel that picks him. Two selectors reading identical numbers
 * genuinely disagree, and the player lives with the difference.
 *
 * All three in [0, 1], 0.5 being the average panel described by
 * [SelectionTuning].
 */
data class SelectorPersonality(
    /** How far an incumbent's reputation counts. High loyalty means slow turnover. */
    val loyalty: Double = 0.5,

    /** Willingness to pick an unproven young player over a safe veteran. */
    val boldness: Double = 0.5,

    /** How far recent form outweighs long-run ability. */
    val formWeighting: Double = 0.5,
) {
    init {
        listOf("loyalty" to loyalty, "boldness" to boldness, "formWeighting" to formWeighting)
            .forEach { (name, value) ->
                require(value.isFinite() && value in 0.0..1.0) { "$name = $value must be in 0..1" }
            }
    }

    companion object {
        /** The panel [SelectionTuning] describes. */
        val AVERAGE: SelectorPersonality = SelectorPersonality()

        /** Drawn for a team, from the selection stream. */
        fun draw(random: SimRandom): SelectorPersonality = SelectorPersonality(
            loyalty = random.nextDouble(0.15, 0.9),
            boldness = random.nextDouble(0.1, 0.9),
            formWeighting = random.nextDouble(0.2, 0.85),
        )
    }
}

/** What the selectors know about the match they are picking for. */
data class SelectionContext(
    val format: MatchFormat,
    val pitch: Pitch,
    /** Players who were in the previous XI. Reputation attaches to these. */
    val incumbents: Set<PlayerId> = emptySet(),
    /** Career caps, used as the slower-moving half of reputation. */
    val caps: Map<PlayerId, Int> = emptyMap(),
)

/** One player's case, broken out so the UI can show why he was or was not picked. */
data class SelectionScore(
    val player: Player,
    val standard: Double,
    val form: Double,
    val suitability: Double,
    val reputation: Double,
    val risk: Double,
    val judgement: Double,
    val total: Double,
)

/**
 * The selection panel.
 *
 * The scorer proposes and the balance check disposes: a side that cannot bowl
 * its overs is not a side, however good the eleven names are. Running it in
 * that order rather than scoring "balance" as another weighted term means the
 * XI is always legal, and the cost of balance is visible as "the best player
 * left out", which is what a selection story is made of.
 *
 * See docs/CAREER_MODEL.md §8.
 */
object Selection {

    /**
     * Score every candidate. Sorted best first, with ties broken by player id
     * so the order never depends on the input collection's iteration.
     */
    fun score(
        candidates: List<Player>,
        context: SelectionContext,
        personality: SelectorPersonality,
        random: SimRandom,
        tuning: SelectionTuning,
    ): List<SelectionScore> = candidates
        .map { scoreOne(it, context, personality, random, tuning) }
        .sortedWith(compareByDescending<SelectionScore> { it.total }.thenBy { it.player.id.value })

    private fun scoreOne(
        player: Player,
        context: SelectionContext,
        personality: SelectorPersonality,
        random: SimRandom,
        tuning: SelectionTuning,
    ): SelectionScore {
        val standard = standardFor(player, context.format)
        // Form is in [-1, 1]; everything else is in [0, 1]. Leaving form signed
        // is deliberate: bad form must be able to cost a place, not merely fail
        // to win one.
        val form = player.state.form
        val suitability = suitability(player, context)
        val reputation = reputation(player, context)
        val risk = risk(player, tuning)
        val judgement = random.nextGaussian() * tuning.judgementSigma

        // Personality shifts the weights around the panel average rather than
        // replacing them, so a bold selector is recognisably picking the same
        // sport as a cautious one.
        val formWeight = tuning.weightForm * (0.4 + 1.2 * personality.formWeighting)
        val reputationWeight = tuning.weightReputation * (0.3 + 1.4 * personality.loyalty)
        // Boldness discounts the risk of an unsharp or unproven player.
        val riskWeight = tuning.weightRisk * (1.4 - 0.8 * personality.boldness)

        val total = standard * tuning.weightStandard +
            form * formWeight +
            suitability * tuning.weightSuitability +
            reputation * reputationWeight -
            risk * riskWeight +
            judgement
        return SelectionScore(player, standard, form, suitability, reputation, risk, judgement, total)
    }

    /**
     * Ability for this format, in [0, 1].
     *
     * A batter is judged on his batting, a bowler on his bowling, and an
     * all-rounder on both — with the format deciding how much the secondary
     * discipline is worth. A T20 side will carry a bowler who can hit; a Test
     * side wants him to bat properly or bowl properly.
     */
    fun standardFor(player: Player, format: MatchFormat): Double {
        val batting = battingStandard(player.attributes, format)
        val bowling = bowlingStandard(player.attributes, format)
        val keeping = player.attributes.groupAverage(
            com.cricketcareer.engine.model.player.AttributeGroup.KEEPING,
        ) / Attributes.MAX
        return when (player.role.primary) {
            RoleDiscipline.BATTING -> if (player.role.keeps) 0.68 * batting + 0.32 * keeping else batting
            RoleDiscipline.BOWLING -> bowling
            // The secondary discipline counts for more in the short forms,
            // where an all-rounder's four overs and quick thirty are both
            // match-winning and neither has to be world class.
            RoleDiscipline.ALLROUNDER -> {
                val secondaryValue = if (format.isMultiDay) 0.38 else 0.46
                val primary = maxOf(batting, bowling)
                val secondary = minOf(batting, bowling)
                primary * (1.0 - secondaryValue) + secondary * secondaryValue
            }
        }
    }

    private fun battingStandard(attributes: Attributes, format: MatchFormat): Double {
        // The same attributes matter in every format; how much they matter
        // does not. Patience and concentration win Tests, range hitting wins
        // T20s, and neither is worthless in the other.
        val core = listOf(Attribute.TECHNIQUE, Attribute.TIMING, Attribute.FOOTWORK)
            .sumOf { attributes.normalised(it) } / 3.0
        val endurance = listOf(Attribute.PATIENCE, Attribute.CONCENTRATION)
            .sumOf { attributes.normalised(it) } / 2.0
        val scoring = listOf(Attribute.POWER, Attribute.RANGE_HITTING, Attribute.STRIKE_ROTATION)
            .sumOf { attributes.normalised(it) } / 3.0
        return if (format.isMultiDay) 0.5 * core + 0.35 * endurance + 0.15 * scoring
        else 0.42 * core + 0.13 * endurance + 0.45 * scoring
    }

    private fun bowlingStandard(attributes: Attributes, format: MatchFormat): Double {
        val control = attributes.normalised(Attribute.ACCURACY)
        val threat = listOf(
            Attribute.PACE, Attribute.TURN, Attribute.SEAM_MOVEMENT,
            Attribute.SWING, Attribute.BOUNCE, Attribute.DRIFT,
        ).sumOf { attributes.normalised(it) } / 6.0
        val death = listOf(Attribute.DEATH_BOWLING, Attribute.VARIATIONS)
            .sumOf { attributes.normalised(it) } / 2.0
        val stamina = attributes.normalised(Attribute.STAMINA)
        // Control is worth more in a T20, where an over costs fourteen; threat
        // is worth more over five days, where you have to take twenty wickets.
        return if (format.isMultiDay) 0.32 * control + 0.45 * threat + 0.08 * death + 0.15 * stamina
        else 0.40 * control + 0.28 * threat + 0.27 * death + 0.05 * stamina
    }

    /**
     * Fit for these conditions, in [0, 1], 0.5 being neutral.
     *
     * Deliberately narrow: a turning pitch is worth a spinner and a green one
     * is worth a seamer, and that is most of what a panel actually reasons
     * about the day before a Test.
     */
    fun suitability(player: Player, context: SelectionContext): Double {
        val pitch = context.pitch
        return when (player.bowlingStyle.kind) {
            BowlingKind.SPIN -> 0.5 + 0.5 * (pitch.turn - 0.5) + 0.25 * (pitch.abrasion - 0.5)
            BowlingKind.PACE -> 0.5 + 0.5 * (pitch.gripSeam - 0.5) + 0.25 * (pitch.grassCover - 0.5)
            // A batter's suitability is how well he plays what the pitch will
            // do to him, which is the same question asked from the other end.
            BowlingKind.NONE -> {
                val vsSpin = player.attributes.normalised(Attribute.SPIN_PLAY)
                val vsPace = player.attributes.normalised(Attribute.PACE_PLAY)
                val spinWeight = pitch.turn
                (vsSpin * spinWeight + vsPace * (1.0 - spinWeight))
            }
        }.coerceIn(0.0, 1.0)
    }

    /**
     * Standing, in [0, 1].
     *
     * Two parts: being in the side last week, and having been in it for years.
     * The first is why one failure does not cost a place; the second is why a
     * hundred-cap player gets a longer run than a three-cap one.
     */
    fun reputation(player: Player, context: SelectionContext): Double {
        val incumbency = if (player.id in context.incumbents) 0.6 else 0.0
        val caps = context.caps[player.id] ?: 0
        // Saturating, so cap 5 to 25 matters far more than 80 to 100.
        val experience = 0.4 * (caps.toDouble() / (caps + 25.0))
        return (incumbency + experience).coerceIn(0.0, 1.0)
    }

    /** Fitness doubt and rustiness, in [0, 1]. */
    fun risk(player: Player, tuning: SelectionTuning): Double {
        val rust = (1.0 - player.state.sharpness) * tuning.sharpnessPenalty
        val niggle = if (player.state.injury?.severity == InjurySeverity.NIGGLE) tuning.nigglePenalty else 0.0
        return (rust + niggle).coerceIn(0.0, 1.0)
    }

    /**
     * Pick an XI.
     *
     * Unavailable players are removed first — a panel does not pick an injured
     * man and then discover it. The rest is: take the best available, then
     * repair the balance by swapping out the lowest-scoring picked player whose
     * absence the side can afford for the best available player who fills the
     * gap.
     */
    fun pickXI(
        candidates: List<Player>,
        context: SelectionContext,
        personality: SelectorPersonality,
        random: SimRandom,
        tuning: SelectionTuning,
        size: Int = 11,
    ): List<SelectionScore> {
        val available = candidates.filter { it.state.isAvailable }
        require(available.size >= size) {
            "cannot pick $size from ${available.size} available players"
        }
        val ranked = score(available, context, personality, random, tuning)
        val picked = ranked.take(size).toMutableList()
        val bench = ranked.drop(size).toMutableList()

        // A keeper is not negotiable: nobody else can keep. Repaired first so
        // that the later repairs cannot drop the only one.
        repairShortfall(
            picked, bench, size,
            isShort = { squad -> squad.none { it.player.role.keeps } },
            fills = { it.player.role.keeps },
        )
        repairShortfall(
            picked, bench, size,
            isShort = { squad -> squad.count { it.player.role.bowls } < tuning.minimumBowlers },
            fills = { it.player.role.bowls },
        )
        repairShortfall(
            picked, bench, size,
            isShort = { squad ->
                squad.count { it.player.role.primary == RoleDiscipline.BATTING } < tuning.minimumBatters
            },
            fills = { it.player.role.primary == RoleDiscipline.BATTING },
        )

        return picked.sortedWith(compareByDescending<SelectionScore> { it.total }.thenBy { it.player.id.value })
    }

    /**
     * While [isShort] holds, swap the worst expendable pick for the best
     * bench player who satisfies [fills].
     *
     * "Expendable" means the lowest-scoring picked player who does not himself
     * satisfy [fills] — dropping one of those would not help — and who is not
     * the side's only keeper. Bounded by the squad size so a squad that cannot
     * be balanced terminates instead of looping.
     */
    private inline fun repairShortfall(
        picked: MutableList<SelectionScore>,
        bench: MutableList<SelectionScore>,
        size: Int,
        isShort: (List<SelectionScore>) -> Boolean,
        fills: (SelectionScore) -> Boolean,
    ) {
        repeat(size) {
            if (!isShort(picked)) return
            val replacement = bench.firstOrNull(fills) ?: return
            val dropped = picked
                .filterNot(fills)
                .filterNot { it.player.role.keeps && picked.count { p -> p.player.role.keeps } <= 1 }
                .minByOrNull { it.total } ?: return
            picked.remove(dropped)
            bench.remove(replacement)
            picked.add(replacement)
            bench.add(dropped)
            bench.sortWith(compareByDescending<SelectionScore> { it.total }.thenBy { it.player.id.value })
        }
    }
}
