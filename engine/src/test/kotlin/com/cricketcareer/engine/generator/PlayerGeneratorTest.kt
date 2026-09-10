package com.cricketcareer.engine.generator

import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.AttributeGroup
import com.cricketcareer.engine.model.player.BattingHand
import com.cricketcareer.engine.model.player.BowlingStyle
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.player.PlayerRole
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The generator's job is a believable *distribution*, so almost every test here
 * is over a sample rather than over one player. A generator that produces a
 * plausible individual and an implausible population is the failure mode that
 * matters — docs/CALIBRATION.md calls it out: if every batting average
 * converges on 30, the model is broken.
 */
class PlayerGeneratorTest {

    private val generator = PlayerGenerator()
    private val today = LocalDate.of(2025, 4, 1)

    private fun spec(level: LadderLevel, role: PlayerRole? = null) =
        PlayerSpec(country = "IND", region = "MH", level = level, role = role)

    private fun sample(
        level: LadderLevel,
        role: PlayerRole? = null,
        count: Int = 400,
        seed: Long = 99L,
    ): List<Player> {
        val rng = SimRandom.fromSeed(seed)
        return (1..count).map { generator.generate(rng, spec(level, role), today, PlayerId("p$it")) }
    }

    @Test
    fun `the same seed generates the same player`() {
        val a = generator.generate(SimRandom.fromSeed(7L), spec(LadderLevel.STATE_FIRST_CLASS), today, PlayerId("x"))
        val b = generator.generate(SimRandom.fromSeed(7L), spec(LadderLevel.STATE_FIRST_CLASS), today, PlayerId("x"))
        assertEquals(a, b)
    }

    @Test
    fun `higher levels produce better players`() {
        // The pyramid. Each rung must be measurably better than the one below.
        val means = listOf(
            LadderLevel.COLLEGE,
            LadderLevel.DISTRICT_CLUB,
            LadderLevel.STATE_FIRST_CLASS,
            LadderLevel.INTERNATIONAL,
        ).map { level ->
            level to sample(level).map { p -> AttributeGroup.ALL.maxOf { p.attributes.groupAverage(it) } }.average()
        }

        means.zipWithNext().forEach { (lower, higher) ->
            assertTrue(
                higher.second > lower.second + 3.0,
                "${higher.first} (${higher.second}) should clearly beat ${lower.first} (${lower.second})",
            )
        }
    }

    @Test
    fun `ability spreads within a level rather than converging`() {
        // If everyone at a level is the same, there is no team to be picked for.
        val peaks = sample(LadderLevel.STATE_FIRST_CLASS).map { p ->
            AttributeGroup.ALL.maxOf { p.attributes.groupAverage(it) }
        }
        val mean = peaks.average()
        val sd = sqrt(peaks.sumOf { (it - mean) * (it - mean) } / peaks.size)
        assertTrue(sd > 4.0, "spread of ability was only $sd points; the level has converged")
        assertTrue(sd < 20.0, "spread of ability was $sd points, which is implausibly wide")
    }

    @Test
    fun `attributes correlate more strongly inside a group than across groups`() {
        // Good technique should tend to come with good footwork; it should not
        // strongly imply a good throwing arm.
        val players = sample(LadderLevel.STATE_FIRST_CLASS, count = 600)
        val within = correlation(
            players.map { it.attributes[Attribute.TECHNIQUE].toDouble() },
            players.map { it.attributes[Attribute.FOOTWORK].toDouble() },
        )
        val across = correlation(
            players.map { it.attributes[Attribute.TECHNIQUE].toDouble() },
            players.map { it.attributes[Attribute.THROW_ARM].toDouble() },
        )
        assertTrue(within > across, "within-group correlation $within did not beat across-group $across")
        assertTrue(within > 0.3, "attributes in a group barely correlate ($within); players will feel random")
    }

    @Test
    fun `fast bowlers bowl much better than they bat, and batters the reverse`() {
        val quicks = sample(LadderLevel.STATE_FIRST_CLASS, PlayerRole.FAST_BOWLER, count = 200)
        val batters = sample(LadderLevel.STATE_FIRST_CLASS, PlayerRole.TOP_ORDER_BAT, count = 200)

        val quickGap = quicks.map {
            it.attributes.groupAverage(AttributeGroup.BOWLING) - it.attributes.groupAverage(AttributeGroup.BATTING)
        }.average()
        val batterGap = batters.map {
            it.attributes.groupAverage(AttributeGroup.BATTING) - it.attributes.groupAverage(AttributeGroup.BOWLING)
        }.average()

        assertTrue(quickGap > 20.0, "fast bowlers were only $quickGap points better at bowling than batting")
        assertTrue(batterGap > 20.0, "top-order batters were only $batterGap points better at batting than bowling")
    }

    @Test
    fun `all-rounders sit between the specialists`() {
        val allrounders = sample(LadderLevel.STATE_FIRST_CLASS, PlayerRole.SEAM_ALLROUNDER, count = 200)
        val gap = allrounders.map {
            abs(it.attributes.groupAverage(AttributeGroup.BOWLING) - it.attributes.groupAverage(AttributeGroup.BATTING))
        }.average()
        assertTrue(gap < 14.0, "all-rounders were $gap points apart in their two disciplines; that is a specialist")
    }

    @Test
    fun `a spinner has turn and no pace, and a seamer the reverse`() {
        val spinners = sample(LadderLevel.STATE_FIRST_CLASS, PlayerRole.SPINNER, count = 200)
        spinners.forEach { p ->
            assertTrue(p.bowlingStyle.isSpin, "${p.role} was given ${p.bowlingStyle}")
            assertTrue(
                p.attributes[Attribute.PACE] < 25,
                "${p.name} is a spinner with pace ${p.attributes[Attribute.PACE]}",
            )
        }
        assertTrue(spinners.map { it.attributes[Attribute.TURN] }.average() > 45.0)

        val quicks = sample(LadderLevel.STATE_FIRST_CLASS, PlayerRole.FAST_BOWLER, count = 200)
        quicks.forEach { p ->
            assertTrue(p.bowlingStyle.isPace, "${p.role} was given ${p.bowlingStyle}")
            assertTrue(p.attributes[Attribute.TURN] < 25, "a quick with turn ${p.attributes[Attribute.TURN]}")
        }
        assertTrue(quicks.map { it.attributes[Attribute.PACE] }.average() > 50.0)
    }

    @Test
    fun `a genuinely fast style rates faster than a medium-pacer`() {
        val fast = sample(LadderLevel.INTERNATIONAL, PlayerRole.FAST_BOWLER, count = 400)
            .filter { it.bowlingStyle == BowlingStyle.RIGHT_FAST }
        val medium = sample(LadderLevel.INTERNATIONAL, PlayerRole.FAST_BOWLER, count = 400)
            .filter { it.bowlingStyle == BowlingStyle.RIGHT_MEDIUM_FAST }
        assertTrue(fast.isNotEmpty() && medium.isNotEmpty(), "sample produced no bowlers of one style")
        assertTrue(
            fast.map { it.attributes[Attribute.PACE] }.average() >
                medium.map { it.attributes[Attribute.PACE] }.average() + 10,
            "right-arm fast did not out-rate right-arm medium-fast for pace",
        )
    }

    @Test
    fun `a non-bowler is not secretly a bowler`() {
        val nonBowlers = sample(LadderLevel.STATE_FIRST_CLASS, count = 600)
            .filter { it.bowlingStyle == BowlingStyle.NONE }
        assertTrue(nonBowlers.isNotEmpty(), "nobody in the sample was a non-bowler")
        nonBowlers.forEach {
            assertTrue(
                it.attributes.groupAverage(AttributeGroup.BOWLING) < 20.0,
                "${it.name} does not bowl but averages ${it.attributes.groupAverage(AttributeGroup.BOWLING)}",
            )
        }
    }

    @Test
    fun `young players are further from their potential than peak-age ones`() {
        val rng = SimRandom.fromSeed(3L)
        fun headroomAt(age: Int): Double = (1..300).map {
            val p = generator.generate(rng, spec(LadderLevel.STATE_FIRST_CLASS).copy(age = age), today, PlayerId("p$it"))
            p.hidden.potential - AttributeGroup.ALL.maxOf { g -> p.attributes.groupAverage(g) }
        }.average()

        val teenage = headroomAt(18)
        val peak = headroomAt(28)
        assertTrue(teenage > peak, "an 18-year-old had $teenage points of headroom, a 28-year-old $peak")
    }

    @Test
    fun `physical attributes decline with age but the mental ones hold up`() {
        val rng = SimRandom.fromSeed(5L)
        fun groupAt(age: Int, group: AttributeGroup): Double = (1..300).map {
            generator.generate(rng, spec(LadderLevel.STATE_FIRST_CLASS).copy(age = age), today, PlayerId("p$it"))
                .attributes.groupAverage(group)
        }.average()

        val physicalPeak = groupAt(27, AttributeGroup.PHYSICAL)
        val physicalOld = groupAt(37, AttributeGroup.PHYSICAL)
        val mentalPeak = groupAt(27, AttributeGroup.MENTAL)
        val mentalOld = groupAt(37, AttributeGroup.MENTAL)

        assertTrue(physicalOld < physicalPeak - 8.0, "physical decline from $physicalPeak to $physicalOld is too gentle")
        assertTrue(
            mentalOld > physicalOld,
            "at 37 the mental attributes ($mentalOld) should hold up better than the physical ($physicalOld)",
        )
    }

    @Test
    fun `ages sit in a believable band around the level's mean`() {
        val ages = sample(LadderLevel.STATE_FIRST_CLASS, count = 500).map { it.ageOn(today) }
        assertTrue(ages.average() in 23.0..30.0, "mean age was ${ages.average()}")
        assertTrue(ages.min() >= 16, "someone was ${ages.min()}")
        assertTrue(ages.max() <= 41, "someone was ${ages.max()}")

        val ageGroup = sample(LadderLevel.STATE_AGE_GROUP, count = 500).map { it.ageOn(today) }
        assertTrue(ageGroup.average() < ages.average(), "age-group cricket was not younger than first-class")
    }

    @Test
    fun `left handers are a minority but a substantial one`() {
        val players = sample(LadderLevel.STATE_FIRST_CLASS, count = 1000)
        val share = players.count { it.battingHand == BattingHand.LEFT }.toDouble() / players.size
        assertTrue(share in 0.18..0.33, "left-handers were $share of the sample")
    }

    @Test
    fun `a generated squad is balanced enough to field a side`() {
        val rng = SimRandom.fromSeed(11L)
        val squad = generator.generateSquad(rng, spec(LadderLevel.STATE_FIRST_CLASS), 16, today, "MH")
        assertEquals(16, squad.size)
        assertTrue(squad.count { it.keeps } >= 1, "no wicketkeeper in the squad")
        assertTrue(squad.count { it.bowlingStyle.isPace && it.bowls } >= 3, "not enough seamers")
        assertTrue(squad.count { it.bowlingStyle.isSpin && it.bowls } >= 1, "no spinner")
        assertTrue(squad.count { it.role.primary.name == "BATTING" } >= 5, "not enough specialist batters")
        assertEquals(16, squad.map { it.id }.distinct().size, "duplicate ids in the squad")
    }

    @Test
    fun `a squad smaller than an XI is refused`() {
        val rng = SimRandom.fromSeed(1L)
        val error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            generator.generateSquad(rng, spec(LadderLevel.COLLEGE), 8, today, "X")
        }
        assertTrue(error.message!!.contains("at least"))
    }

    @Test
    fun `every attribute stays inside the legal range`() {
        sample(LadderLevel.INTERNATIONAL, count = 300).forEach { p ->
            Attribute.ALL.forEach { a ->
                assertTrue(p.attributes[a] in 1..100, "${p.name} has ${a.name} = ${p.attributes[a]}")
            }
        }
    }

    @Test
    fun `names vary across a squad`() {
        val rng = SimRandom.fromSeed(13L)
        val squad = generator.generateSquad(rng, spec(LadderLevel.STATE_FIRST_CLASS), 16, today, "MH")
        assertTrue(squad.map { it.name.full }.distinct().size >= 14, "too many repeated names in one squad")
    }

    private fun correlation(xs: List<Double>, ys: List<Double>): Double {
        val meanX = xs.average()
        val meanY = ys.average()
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        return covariance / sqrt(varianceX * varianceY)
    }
}
