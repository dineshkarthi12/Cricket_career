package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.AgeingTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.HiddenAttributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgeingTest {

    private val tuning = AgeingTuning()

    private fun player(rating: Int, potential: Int, learningRate: Int = 60): Player =
        Fixtures.averagePlayer("P").copy(
            attributes = Attributes.uniform(rating),
            hidden = HiddenAttributes(
                potential = potential,
                injuryProneness = 50,
                temperament = 50,
                bigMatchFactor = 50,
                learningRate = learningRate,
            ),
        )

    private fun rng(seed: Long = 1) = SimRandom.fromSeed(seed)

    @Test
    fun `every attribute is assigned a development class`() {
        // Exhaustive `when`, so this is really a guard against someone adding
        // an attribute and a default branch at the same time.
        Attribute.ALL.forEach { Ageing.developmentClass(it) }
    }

    @Test
    fun `pace peaks before technique, which peaks before composure`() {
        val physical = Ageing.peakAge(Ageing.developmentClass(Attribute.PACE), tuning)
        val technical = Ageing.peakAge(Ageing.developmentClass(Attribute.TECHNIQUE), tuning)
        val mental = Ageing.peakAge(Ageing.developmentClass(Attribute.COMPOSURE), tuning)
        assertTrue(physical < technical) { "physical peak $physical should precede technical $technical" }
        assertTrue(technical < mental) { "technical peak $technical should precede mental $mental" }
    }

    @Test
    fun `a young player with headroom improves`() {
        val p = player(rating = 45, potential = 85)
        val after = Ageing.ageOneYear(p, age = 19, exposure = 1.0, coaching = 0.7, random = rng(), tuning = tuning)
        assertTrue(after[Attribute.TECHNIQUE] > p.attributes[Attribute.TECHNIQUE]) {
            "19-year-old at 45 with potential 85 should improve, got ${after[Attribute.TECHNIQUE]}"
        }
    }

    @Test
    fun `a young player at his potential barely moves`() {
        val p = player(rating = 85, potential = 85)
        val after = Ageing.ageOneYear(p, age = 19, exposure = 1.0, coaching = 0.7, random = rng(), tuning = tuning)
        // Only the overshoot allowance and the yearly noise are left.
        assertTrue(Ageing.closeTo(after, p.attributes, tolerance = 3)) {
            "no headroom should mean no meaningful growth, got ${after[Attribute.TECHNIQUE]} from 85"
        }
    }

    @Test
    fun `a season without cricket develops nobody`() {
        val p = player(rating = 45, potential = 85)
        val played = Ageing.ageOneYear(p, 19, exposure = 1.0, coaching = 0.7, random = rng(), tuning = tuning)
        val benched = Ageing.ageOneYear(p, 19, exposure = 0.0, coaching = 0.7, random = rng(), tuning = tuning)
        assertTrue(played[Attribute.TECHNIQUE] > benched[Attribute.TECHNIQUE]) {
            "playing (${played[Attribute.TECHNIQUE]}) should beat not playing (${benched[Attribute.TECHNIQUE]})"
        }
    }

    @Test
    fun `a fast bowler loses pace while his composure is still rising`() {
        // The whole reason for a per-class curve. At 31 a seamer is past the
        // physical peak of 26 and still short of the mental peak of 34.
        // Averaged over seeds: at 31 the physical decline is well under a
        // point a year, so a single yearly noise draw can swamp it. The claim
        // is about the model, not about one seed.
        val p = player(rating = 70, potential = 85)
        val years = (1L..120L).map { seed ->
            Ageing.ageOneYear(p, age = 31, exposure = 1.0, coaching = 0.7, random = rng(seed), tuning = tuning)
        }
        val meanPace = years.map { it[Attribute.PACE] }.average()
        val meanComposure = years.map { it[Attribute.COMPOSURE] }.average()
        assertTrue(meanPace < 70.0) { "pace should decline at 31, mean was $meanPace" }
        assertTrue(meanComposure > 70.0) { "composure should still rise at 31, mean was $meanComposure" }
    }

    @Test
    fun `decline accelerates rather than running flat`() {
        // Averaged over seeds so the yearly noise does not decide the test.
        fun meanPaceDropAt(age: Int): Double = (1L..80L).map { seed ->
            val p = player(rating = 80, potential = 80)
            val after = Ageing.ageOneYear(p, age, exposure = 1.0, coaching = 0.7, random = rng(seed), tuning = tuning)
            (p.attributes[Attribute.PACE] - after[Attribute.PACE]).toDouble()
        }.average()

        val early = meanPaceDropAt(28)
        val late = meanPaceDropAt(36)
        assertTrue(late > early * 2.0) {
            "decline should accelerate: 28 loses $early, 36 loses $late"
        }
    }

    @Test
    fun `ageing is a pure function of its seed`() {
        val p = player(rating = 55, potential = 80)
        val a = Ageing.ageOneYear(p, 22, 0.8, 0.6, rng(31337), tuning)
        val b = Ageing.ageOneYear(p, 22, 0.8, 0.6, rng(31337), tuning)
        assertEquals(a, b)
    }

    @Test
    fun `attributes never leave the legal range`() {
        for (age in 14..44) {
            for (rating in intArrayOf(1, 50, 100)) {
                val p = player(rating = rating, potential = 100)
                val after = Ageing.ageOneYear(p, age, 1.0, 1.0, rng(age.toLong() * 100 + rating), tuning)
                Attribute.ALL.forEach {
                    assertTrue(after[it] in Attributes.MIN..Attributes.MAX) {
                        "age $age rating $rating produced ${after[it]} for $it"
                    }
                }
            }
        }
    }

    @Test
    fun `a severe injury costs physical attributes and leaves the mind alone`() {
        val before = Attributes.uniform(70)
        val after = Ageing.applyPermanentDamage(before, points = 4)
        assertEquals(66, after[Attribute.PACE])
        assertEquals(66, after[Attribute.SPEED])
        assertEquals(70, after[Attribute.COMPOSURE])
        assertEquals(70, after[Attribute.TECHNIQUE])
    }

    @Test
    fun `a career of growth then decline peaks somewhere sane`() {
        // The shape test: run a whole career and check it looks like a career.
        var p = player(rating = 42, potential = 84, learningRate = 70)
        val random = rng(9001)
        val byAge = LinkedHashMap<Int, Int>()
        for (age in 17..38) {
            val attrs = Ageing.ageOneYear(p, age, exposure = 0.9, coaching = 0.7, random = random, tuning = tuning)
            p = p.copy(attributes = attrs)
            byAge[age] = attrs[Attribute.TECHNIQUE]
        }
        val peakAge = byAge.maxBy { it.value }.key
        assertTrue(peakAge in 26..33) { "technique peaked at $peakAge: $byAge" }
        assertTrue(byAge.getValue(38) < byAge.getValue(peakAge)) { "should be past it by 38: $byAge" }
        assertTrue(byAge.getValue(peakAge) >= 70) { "a potential-84 player should get near it: $byAge" }
    }
}
