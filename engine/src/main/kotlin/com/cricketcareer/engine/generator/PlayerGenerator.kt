package com.cricketcareer.engine.generator

import com.cricketcareer.engine.config.GenerationTuning
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.AttributeGroup
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.BattingHand
import com.cricketcareer.engine.model.player.BowlingStyle
import com.cricketcareer.engine.model.player.HiddenAttributes
import com.cricketcareer.engine.model.player.PersonName
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.player.PlayerRole
import com.cricketcareer.engine.model.world.BowlingMix
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.rng.SimRandom
import java.time.LocalDate

/** What to generate. Anything left null is drawn from the level's distribution. */
data class PlayerSpec(
    val country: String,
    val region: String,
    val level: LadderLevel,
    val role: PlayerRole? = null,
    val age: Int? = null,
    val bowlingStyle: BowlingStyle? = null,
    val battingHand: BattingHand? = null,
    val bowlingMix: BowlingMix = BowlingMix(),
)

/**
 * Makes fictional cricketers with a believable distribution.
 *
 * The causal order matters and is the point of the design:
 *
 *  1. draw an **age**,
 *  2. draw a **potential** from the level's distribution,
 *  3. work out how much of that potential a player of that age has realised,
 *  4. spread the resulting quality across groups and then attributes.
 *
 * Doing it this way rather than drawing current ability directly is what makes
 * a 17-year-old at a state academy a genuinely different object from a 29-year-
 * old at the same level — same standard of cricket, completely different
 * futures — and it means the career layer's growth model and the generator
 * agree about what a young player is.
 *
 * Correlation is produced in two stages: a group centre near the player's
 * overall quality, then an attribute near its group centre. That gives
 * attributes within a group that move together (good technique tends to come
 * with good footwork) while still allowing the specialist weakness that makes a
 * player interesting to bowl at.
 */
class PlayerGenerator(
    private val tuning: GenerationTuning = GenerationTuning.DEFAULT,
    private val names: NamePools = NamePools.FALLBACK,
) {

    /**
     * Generate one player.
     *
     * [today] is passed in rather than read from a clock — the engine may not
     * touch the clock, and a career started in 2003 must generate players who
     * are the right age for 2003.
     */
    fun generate(rng: SimRandom, spec: PlayerSpec, today: LocalDate, id: PlayerId): Player {
        val age = spec.age ?: drawAge(rng, spec.level)
        val role = spec.role ?: drawRole(rng)
        val bowlingStyle = spec.bowlingStyle ?: drawBowlingStyle(rng, role, spec.bowlingMix)
        val battingHand = spec.battingHand
            ?: if (rng.chance(tuning.leftHandedShare)) BattingHand.LEFT else BattingHand.RIGHT

        val potential = drawPotential(rng, spec.level)
        val realisation = (tuning.realisationMean + rng.nextGaussian() * tuning.realisationSpread)
            .coerceIn(0.55, 1.05)
        val peakQuality = potential * realisation

        val attributes = buildAttributes(rng, role, bowlingStyle, age, peakQuality)
        val hidden = buildHidden(rng, potential, attributes)

        return Player(
            id = id,
            name = drawName(rng, spec.region),
            dateOfBirth = birthDateFor(rng, age, today),
            country = spec.country,
            region = spec.region,
            battingHand = battingHand,
            bowlingStyle = bowlingStyle,
            role = role,
            attributes = attributes,
            hidden = hidden,
        )
    }

    /**
     * Generate a balanced squad: openers, a middle order, a keeper, seamers and
     * spinners in the proportions a real team is built in.
     *
     * A squad of eleven randomly-rolled roles would produce sides with four
     * keepers and no spinner often enough to matter.
     */
    fun generateSquad(
        rng: SimRandom,
        spec: PlayerSpec,
        size: Int,
        today: LocalDate,
        idPrefix: String,
    ): List<Player> {
        require(size >= MINIMUM_SQUAD) { "a squad needs at least $MINIMUM_SQUAD players, asked for $size" }
        val roles = squadRoles(size)
        return roles.mapIndexed { index, role ->
            generate(rng, spec.copy(role = role), today, PlayerId("$idPrefix-${index + 1}"))
        }
    }

    /**
     * The shape of a squad. The first eleven form a balanced XI — five
     * specialist batters, a keeper, an all-rounder, three seamers and a spinner
     * — and anything beyond that is depth in the same proportions.
     */
    private fun squadRoles(size: Int): List<PlayerRole> {
        val core = listOf(
            PlayerRole.OPENING_BAT,
            PlayerRole.OPENING_BAT,
            PlayerRole.TOP_ORDER_BAT,
            PlayerRole.MIDDLE_ORDER_BAT,
            PlayerRole.MIDDLE_ORDER_BAT,
            PlayerRole.WICKETKEEPER_BAT,
            PlayerRole.FINISHER,
            PlayerRole.SEAM_ALLROUNDER,
            PlayerRole.FAST_BOWLER,
            PlayerRole.FAST_BOWLER,
            PlayerRole.SPINNER,
        )
        val depth = listOf(
            PlayerRole.TOP_ORDER_BAT,
            PlayerRole.FAST_BOWLER,
            PlayerRole.MIDDLE_ORDER_BAT,
            PlayerRole.SPIN_ALLROUNDER,
            PlayerRole.FAST_BOWLER,
            PlayerRole.WICKETKEEPER_BAT,
            PlayerRole.SPINNER,
            PlayerRole.FINISHER,
            PlayerRole.SEAM_ALLROUNDER,
            PlayerRole.OPENING_BAT,
        )
        return buildList {
            addAll(core.take(size))
            var i = 0
            while (this.size < size) {
                add(depth[i % depth.size])
                i++
            }
        }
    }

    // --- The maths ----------------------------------------------------------

    private fun drawPotential(rng: SimRandom, level: LadderLevel): Double {
        val mean = tuning.potentialMeanByLevel.getValue(level)
        return (mean + rng.nextGaussian() * tuning.potentialSpread)
            .coerceIn(Attributes.MIN.toDouble(), Attributes.MAX.toDouble())
    }

    private fun drawAge(rng: SimRandom, level: LadderLevel): Int {
        val mean = tuning.ageMeanByLevel.getValue(level)
        return (mean + rng.nextGaussian() * tuning.ageSpread)
            .toInt()
            .coerceIn(tuning.minAge, tuning.maxAge)
    }

    private fun drawRole(rng: SimRandom): PlayerRole = rng.pickWeighted(
        SQUAD_ROLE_WEIGHTS.keys.toList(),
        SQUAD_ROLE_WEIGHTS.values.toDoubleArray(),
    )

    private fun drawBowlingStyle(rng: SimRandom, role: PlayerRole, mix: BowlingMix): BowlingStyle = when (role) {
        PlayerRole.FAST_BOWLER, PlayerRole.SEAM_ALLROUNDER -> rng.pickWeighted(
            listOf(
                BowlingStyle.RIGHT_FAST, BowlingStyle.RIGHT_FAST_MEDIUM, BowlingStyle.RIGHT_MEDIUM_FAST,
                BowlingStyle.LEFT_FAST, BowlingStyle.LEFT_FAST_MEDIUM,
            ),
            doubleArrayOf(mix.fast, mix.fastMedium, mix.medium, mix.fast * 0.3, mix.fastMedium * 0.3),
        )
        PlayerRole.SPINNER, PlayerRole.SPIN_ALLROUNDER -> rng.pickWeighted(
            listOf(
                BowlingStyle.OFF_BREAK, BowlingStyle.LEG_BREAK,
                BowlingStyle.SLOW_LEFT_ARM_ORTHODOX, BowlingStyle.SLOW_LEFT_ARM_CHINAMAN,
            ),
            doubleArrayOf(mix.offSpin, mix.legSpin, mix.leftArmSpin, mix.legSpin * 0.15),
        )
        // A batter who bowls a bit, or does not bowl at all. Most do not.
        else -> if (rng.chance(0.30)) {
            rng.pickWeighted(
                listOf(BowlingStyle.RIGHT_MEDIUM, BowlingStyle.OFF_BREAK, BowlingStyle.LEG_BREAK),
                doubleArrayOf(mix.medium, mix.offSpin, mix.legSpin),
            )
        } else {
            BowlingStyle.NONE
        }
    }

    /**
     * How much of his potential a player of this age has realised, per group.
     *
     * Growth is fastest at 17-23, slows to a crawl by 27-30 and reverses after
     * 32-34 — and it reverses *first* in the physical attributes, which is why
     * a fast bowler's decline is a cliff and a batter's is a slope.
     */
    private fun maturity(age: Int, group: AttributeGroup): Double {
        val ageD = age.toDouble()
        // Rise from ~0.52 at 16 to 1.0 at 27, steepest in the late teens.
        val growth = when {
            ageD <= YOUTH_START -> YOUTH_FLOOR
            ageD >= PEAK_AGE -> 1.0
            else -> YOUTH_FLOOR + (1.0 - YOUTH_FLOOR) * ((ageD - YOUTH_START) / (PEAK_AGE - YOUTH_START)).let {
                // Ease-out: most of the gain lands early, then it flattens.
                1.0 - (1.0 - it) * (1.0 - it)
            }
        }
        if (ageD <= PEAK_AGE) return growth

        return if (group.decaysEarly) {
            // Physical and fielding: away at ~2.2% a year from 29, accelerating.
            val years = ageD - PHYSICAL_DECLINE_START
            if (years <= 0) 1.0 else (1.0 - 0.022 * years - 0.0016 * years * years).coerceAtLeast(0.45)
        } else {
            // Batting, bowling craft and the mental attributes keep improving
            // into the early thirties before a slow decline.
            val years = ageD - PEAK_AGE
            (1.0 + 0.008 * years.coerceAtMost(6.0) - 0.014 * (ageD - MENTAL_DECLINE_START).coerceAtLeast(0.0))
                .coerceIn(0.55, 1.06)
        }
    }

    private fun buildAttributes(
        rng: SimRandom,
        role: PlayerRole,
        style: BowlingStyle,
        age: Int,
        peakQuality: Double,
    ): Attributes {
        val archetype = RoleArchetype.forRole(role)

        // Stage 1: a centre for each group, near the player's overall quality
        // but free to diverge — this is where a specialist weakness is born.
        val groupCentres = AttributeGroup.ALL.associateWith { group ->
            val pull = tuning.groupCoherence
            val base = peakQuality * pull + peakQuality * (1 - pull) * (1.0 + rng.nextGaussian() * 0.20)
            base + rng.nextGaussian() * tuning.groupSpread
        }

        // Stage 2: each attribute near its group's centre, shifted by the role's
        // shape and by how much of his potential a player of this age has.
        val values = mutableMapOf<Attribute, Int>()
        Attribute.ALL.forEach { attribute ->
            val centre = groupCentres.getValue(attribute.group)
            val raw = centre + archetype.offsetFor(attribute) + rng.nextGaussian() * tuning.attributeSpread
            val matured = raw * maturity(age, attribute.group)
            values[attribute] = matured.toInt().coerceIn(Attributes.MIN, Attributes.MAX)
        }

        return Attributes.of(applyStyleConstraints(values, style))
    }

    /**
     * Force the attributes that a bowling style *defines* rather than merely
     * influences.
     *
     * A leg-spinner with 70 pace is not an interesting variation, it is a bug —
     * `pace` and `turn` are the two attributes the style determines outright,
     * and a non-bowler should not be sitting on bowling attributes at all.
     */
    private fun applyStyleConstraints(
        values: MutableMap<Attribute, Int>,
        style: BowlingStyle,
    ): Map<Attribute, Int> {
        when {
            style.isPace -> {
                // Pace tracks the style's typical speed: 145 km/h is a genuine
                // quick, 118 is gentle medium.
                val paceRating = ((style.typicalPaceKph - PACE_FLOOR_KPH) / (PACE_CEILING_KPH - PACE_FLOOR_KPH) * 100)
                values[Attribute.PACE] = (paceRating.toInt() + (values[Attribute.PACE]!! - 50) / 4)
                    .coerceIn(Attributes.MIN, Attributes.MAX)
                values[Attribute.TURN] = (values[Attribute.TURN]!! / 5).coerceAtLeast(Attributes.MIN)
                values[Attribute.DRIFT] = (values[Attribute.DRIFT]!! / 5).coerceAtLeast(Attributes.MIN)
            }
            style.isSpin -> {
                values[Attribute.PACE] = (values[Attribute.PACE]!! / 6).coerceAtLeast(Attributes.MIN)
                values[Attribute.SWING] = (values[Attribute.SWING]!! / 5).coerceAtLeast(Attributes.MIN)
                values[Attribute.REVERSE_SWING] = (values[Attribute.REVERSE_SWING]!! / 5).coerceAtLeast(Attributes.MIN)
                values[Attribute.SEAM_MOVEMENT] = (values[Attribute.SEAM_MOVEMENT]!! / 4).coerceAtLeast(Attributes.MIN)
            }
            else -> {
                // Does not bowl: every bowling attribute drops to token level.
                Attribute.inGroup(AttributeGroup.BOWLING).forEach { attribute ->
                    values[attribute] = (values[attribute]!! / 6).coerceAtLeast(Attributes.MIN)
                }
            }
        }
        return values
    }

    /**
     * Hidden attributes.
     *
     * [HiddenAttributes.potential] is floored just above the player's current
     * best group, so a generated veteran is never handed a ceiling he has
     * already crashed through — that would make the growth model produce
     * negative headroom and a confusing coach report.
     */
    private fun buildHidden(rng: SimRandom, potential: Double, attributes: Attributes): HiddenAttributes {
        val bestGroup = AttributeGroup.ALL.maxOf { attributes.groupAverage(it) }
        // Clamped into range before use: a player who is already near 100 would
        // otherwise produce a floor above the scale and an empty range.
        val floor = (bestGroup * tuning.potentialFloorAboveCurrent).toInt()
            .coerceIn(Attributes.MIN, Attributes.MAX)
        return HiddenAttributes(
            potential = potential.toInt().coerceIn(floor, Attributes.MAX),
            injuryProneness = drawHidden(rng),
            temperament = drawHidden(rng),
            bigMatchFactor = drawHidden(rng),
            learningRate = drawHidden(rng),
        )
    }

    /**
     * Hidden attributes other than potential are drawn independently of ability.
     *
     * A great player is not automatically a big-match player, and that is the
     * whole point of them being hidden: you find out.
     */
    private fun drawHidden(rng: SimRandom): Int =
        (50 + rng.nextGaussian() * 16).toInt().coerceIn(Attributes.MIN, Attributes.MAX)

    private fun drawName(rng: SimRandom, region: String): PersonName = names.draw(rng, region)

    /**
     * A birth date for someone [age] years old on [today], with the day of the
     * year spread out so a squad's birthdays are not all in January.
     */
    private fun birthDateFor(rng: SimRandom, age: Int, today: LocalDate): LocalDate {
        val dayOffset = rng.nextInt(365)
        return today.minusYears(age.toLong()).minusDays(dayOffset.toLong())
    }

    companion object {
        const val MINIMUM_SQUAD: Int = 11

        /** Age at which growth starts from its floor. */
        private const val YOUTH_START: Double = 16.0

        /** Fraction of potential a sixteen-year-old has realised. */
        private const val YOUTH_FLOOR: Double = 0.52

        /** Age by which growth has essentially finished. */
        private const val PEAK_AGE: Double = 27.0

        /** Age at which physical attributes begin to go. */
        private const val PHYSICAL_DECLINE_START: Double = 29.0

        /** Age at which even the mental attributes start to slip. */
        private const val MENTAL_DECLINE_START: Double = 34.0

        /** Speeds mapped onto the 1-100 pace attribute. */
        private const val PACE_FLOOR_KPH: Double = 105.0
        private const val PACE_CEILING_KPH: Double = 155.0

        /**
         * Role frequencies when generating an unstructured pool of players
         * (a draft, a college intake). Squads use [PlayerGenerator.squadRoles]
         * instead, which guarantees balance.
         */
        private val SQUAD_ROLE_WEIGHTS: LinkedHashMap<PlayerRole, Double> = linkedMapOf(
            PlayerRole.OPENING_BAT to 1.6,
            PlayerRole.TOP_ORDER_BAT to 1.6,
            PlayerRole.MIDDLE_ORDER_BAT to 2.0,
            PlayerRole.FINISHER to 1.0,
            PlayerRole.WICKETKEEPER_BAT to 1.0,
            PlayerRole.SEAM_ALLROUNDER to 1.2,
            PlayerRole.SPIN_ALLROUNDER to 0.8,
            PlayerRole.FAST_BOWLER to 2.4,
            PlayerRole.SPINNER to 1.4,
        )
    }
}
