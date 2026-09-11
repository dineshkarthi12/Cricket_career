package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.TrainingTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.HiddenAttributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerState
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TrainingModelTest {

    private val tuning = TrainingTuning()

    private fun player(rating: Int = 50, potential: Int = 85, fatigue: Double = 0.0): Player =
        Fixtures.averagePlayer("P").copy(
            attributes = Attributes.uniform(rating),
            hidden = HiddenAttributes(potential, 50, 50, 50, 70),
            state = PlayerState.FRESH.withFatigueChange(fatigue),
        )

    private fun rng(seed: Long = 11) = SimRandom.fromSeed(seed)

    /** Mean attribute after [weeks] of the same plan, averaged over seeds. */
    private fun after(
        p: Player,
        week: TrainingWeek,
        weeks: Int,
        attribute: Attribute,
        coaching: Double = 0.7,
        seeds: Int = 24,
    ): Double = (1L..seeds).map { seed ->
        var current = p
        var carry: Map<Attribute, Double> = emptyMap()
        val random = rng(seed)
        repeat(weeks) {
            val result = TrainingModel.train(current, week, coaching, random, tuning, carry)
            // Fatigue is deliberately not carried here, so the test measures
            // the gain rather than its interaction with the fatigue term,
            // which has a test of its own.
            current = current.copy(attributes = result.attributes)
            carry = result.carry
        }
        current.attributes[attribute].toDouble()
    }.average()

    @Test
    fun `a week must add up to a hundred points`() {
        assertThrows<IllegalArgumentException> { TrainingWeek(mapOf(TrainingFocus.FITNESS to 60)) }
        assertThrows<IllegalArgumentException> {
            TrainingWeek(mapOf(TrainingFocus.FITNESS to 60, TrainingFocus.REST to 60))
        }
        assertThrows<IllegalArgumentException> { TrainingWeek(mapOf(TrainingFocus.FITNESS to -10)) }
    }

    @Test
    fun `an off-season on one thing is a season's visible improvement`() {
        // The calibration claim in the tuning comment: about four points from
        // eight focused weeks for a young player with headroom.
        val p = player(rating = 50, potential = 85)
        val mean = after(p, TrainingWeek.allOn(TrainingFocus.BATTING_TECHNIQUE), 8, Attribute.TECHNIQUE)
        assertTrue(mean - 50.0 in 1.5..4.0) {
            "eight weeks on technique moved it to $mean from 50; expected roughly 51.5-54"
        }
    }

    @Test
    fun `training only moves the attributes the focus covers`() {
        val p = player()
        val result = TrainingModel.train(p, TrainingWeek.allOn(TrainingFocus.SPIN_BOWLING), 0.7, rng(), tuning)
        assertEquals(50, result.attributes[Attribute.POWER])
        assertEquals(50, result.attributes[Attribute.GLOVEWORK])
    }

    @Test
    fun `a tired player gets almost nothing from a hard week`() {
        // The whole reason rest is a real option rather than dead weight.
        val week = TrainingWeek.allOn(TrainingFocus.FITNESS)
        val fresh = after(player(fatigue = 0.0), week, 10, Attribute.FITNESS)
        val spent = after(player(fatigue = 0.95), week, 10, Attribute.FITNESS)
        assertTrue(fresh > spent) { "fresh reached $fresh, spent reached $spent" }
    }

    @Test
    fun `a hard week costs fatigue and a rest week clears it`() {
        val tired = player(fatigue = 0.6)
        val hard = TrainingModel.train(tired, TrainingWeek.allOn(TrainingFocus.FITNESS), 0.7, rng(), tuning)
        val rest = TrainingModel.train(tired, TrainingWeek.allOn(TrainingFocus.REST), 0.7, rng(), tuning)
        assertTrue(hard.state.fatigue > tired.state.fatigue) { "hard week gave ${hard.state.fatigue}" }
        assertTrue(rest.state.fatigue < tired.state.fatigue) { "rest week gave ${rest.state.fatigue}" }
    }

    @Test
    fun `a rest week costs nothing in fatigue`() {
        val p = player(fatigue = 0.0)
        val rest = TrainingModel.train(p, TrainingWeek.allOn(TrainingFocus.REST), 0.7, rng(), tuning)
        assertEquals(0.0, rest.state.fatigue, 1e-9)
    }

    @Test
    fun `a player at his potential cannot train past it by much`() {
        val p = player(rating = 85, potential = 85)
        val mean = after(p, TrainingWeek.allOn(TrainingFocus.BATTING_TECHNIQUE), 40, Attribute.TECHNIQUE)
        assertTrue(mean < 89.0) { "forty weeks at potential reached $mean, should stay near 85" }
    }

    @Test
    fun `better coaching is worth more than worse coaching`() {
        val p = player()
        val week = TrainingWeek.allOn(TrainingFocus.BATTING_TECHNIQUE)
        val best = after(p, week, 12, Attribute.TECHNIQUE, coaching = 1.0)
        val worst = after(p, week, 12, Attribute.TECHNIQUE, coaching = 0.0)
        assertTrue(best > worst) { "best $best vs worst $worst" }
    }

    @Test
    fun `a split week moves both areas, more slowly than either alone`() {
        val p = player()
        val split = TrainingWeek(mapOf(TrainingFocus.BATTING_TECHNIQUE to 50, TrainingFocus.FITNESS to 50))
        val whole = TrainingWeek.allOn(TrainingFocus.BATTING_TECHNIQUE)
        val splitTechnique = after(p, split, 20, Attribute.TECHNIQUE)
        val wholeTechnique = after(p, whole, 20, Attribute.TECHNIQUE)
        val splitFitness = after(p, split, 20, Attribute.FITNESS)
        assertTrue(splitTechnique > 50.0) { "split should still move technique, got $splitTechnique" }
        assertTrue(splitFitness > 50.0) { "split should still move fitness, got $splitFitness" }
        assertTrue(splitTechnique < wholeTechnique) { "split $splitTechnique should trail whole $wholeTechnique" }
    }

    @Test
    fun `intensity counts everything that is not rest`() {
        assertEquals(1.0, TrainingWeek.allOn(TrainingFocus.FITNESS).intensity, 1e-9)
        assertEquals(0.0, TrainingWeek.allOn(TrainingFocus.REST).intensity, 1e-9)
        assertEquals(
            0.6,
            TrainingWeek(mapOf(TrainingFocus.FITNESS to 60, TrainingFocus.REST to 40)).intensity,
            1e-9,
        )
    }

    @Test
    fun `the projection agrees with what training actually does`() {
        // If the number on the screen and the number in the save file disagree,
        // one of them is lying to the player.
        val p = player(rating = 50, potential = 85)
        val week = TrainingWeek.allOn(TrainingFocus.BATTING_TECHNIQUE)
        val projected = TrainingModel.project(p, week, 0.7, weeks = 12, Attribute.TECHNIQUE, tuning)
        val actual = after(p, week, 12, Attribute.TECHNIQUE)
        assertTrue(kotlin.math.abs(projected - actual) <= 1.5) {
            "projection said $projected, training gave $actual"
        }
    }

    @Test
    fun `the carry is what makes a run of small weeks add up`() {
        // Each week is worth about a fifth of a point. Thrown away, eight of
        // them are worth nothing at all; carried, they are worth two.
        val p = player(rating = 50, potential = 85)
        val week = TrainingWeek.allOn(TrainingFocus.BATTING_TECHNIQUE)

        var discarded = p
        var carried = p
        var carry: Map<Attribute, Double> = emptyMap()
        repeat(8) {
            discarded = discarded.copy(
                attributes = TrainingModel.train(discarded, week, 0.7, rng(1), tuning).attributes,
            )
            val r = TrainingModel.train(carried, week, 0.7, rng(1), tuning, carry)
            carried = carried.copy(attributes = r.attributes)
            carry = r.carry
        }
        assertEquals(50, discarded.attributes[Attribute.TECHNIQUE])
        assertTrue(carried.attributes[Attribute.TECHNIQUE] > 50) {
            "carried reached ${carried.attributes[Attribute.TECHNIQUE]}"
        }
    }

    @Test
    fun `a bad week teaches nothing but never makes a player worse`() {
        val p = player(rating = 70, potential = 72)
        repeat(200) { seed ->
            val r = TrainingModel.train(p, TrainingWeek.allOn(TrainingFocus.FITNESS), 0.7, rng(seed.toLong()), tuning)
            assertTrue(r.attributes[Attribute.FITNESS] >= 70) {
                "seed $seed took fitness to ${r.attributes[Attribute.FITNESS]}"
            }
        }
    }

    @Test
    fun `training is a pure function of its seed`() {
        val p = player()
        val week = TrainingWeek(mapOf(TrainingFocus.SEAM_BOWLING to 70, TrainingFocus.REST to 30))
        val a = TrainingModel.train(p, week, 0.6, rng(2024), tuning)
        val b = TrainingModel.train(p, week, 0.6, rng(2024), tuning)
        assertEquals(a, b)
    }

    @Test
    fun `attributes stay in range through a lifetime of training`() {
        var p = player(rating = 99, potential = 100)
        var carry: Map<Attribute, Double> = emptyMap()
        val random = rng(5)
        repeat(500) {
            val r = TrainingModel.train(p, TrainingWeek.allOn(TrainingFocus.FITNESS), 1.0, random, tuning, carry)
            p = p.copy(attributes = r.attributes)
            carry = r.carry
        }
        Attribute.ALL.forEach {
            assertTrue(p.attributes[it] in Attributes.MIN..Attributes.MAX) { "$it = ${p.attributes[it]}" }
        }
    }
}
