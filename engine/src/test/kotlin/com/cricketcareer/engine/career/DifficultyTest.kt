package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.CareerTuning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DifficultyTest {

    @Test
    fun `professional is the calibration reference and changes nothing`() {
        // Every band in docs/CALIBRATION.md is measured here. If this ever
        // stops being the identity, every one of them is measuring a different
        // game from the one it claims to.
        assertEquals(CareerTuning.DEFAULT, Difficulty.PROFESSIONAL.applyTo(CareerTuning.DEFAULT))
        assertSame(Difficulty.PROFESSIONAL, Difficulty.DEFAULT)
    }

    @Test
    fun `difficulty never touches how good anyone is at cricket`() {
        // The whole promise of Q6. Difficulty's signature only offers it a
        // CareerTuning, so it cannot reach EngineTuning at all - but it could
        // still lie by shading the reduced-form model that decides what a rival
        // scores, or by ageing the user faster. Those are the same lie wearing
        // a career-layer hat, so they are pinned here too.
        val base = CareerTuning.DEFAULT
        Difficulty.entries.forEach { difficulty ->
            val tuned = difficulty.applyTo(base)
            assertEquals(base.world, tuned.world) { "$difficulty moved the reduced-form world" }
            assertEquals(base.worldAgeing, tuned.worldAgeing) { "$difficulty aged the world differently" }
            assertEquals(base.ageing, tuned.ageing) { "$difficulty aged the user differently" }
            assertEquals(base.retirement, tuned.retirement) { "$difficulty changed when careers end" }
            assertEquals(base.contracts, tuned.contracts) { "$difficulty changed what a contract is worth" }
        }
    }

    @Test
    fun `the settings are ordered, and every lever points the same way`() {
        // A harder game must be harder in every term at once. A setting that
        // made selectors stricter but injuries rarer would not be a difficulty,
        // it would be a different game.
        val order = listOf(Difficulty.AMATEUR, Difficulty.PROFESSIONAL, Difficulty.ELITE)
        order.zipWithNext().forEach { (easier, harder) ->
            assertTrue(harder.selectorPatience > easier.selectorPatience) { "$harder selectors" }
            assertTrue(harder.oppositionStandardShift > easier.oppositionStandardShift) { "$harder opposition" }
            assertTrue(harder.injuryRisk > easier.injuryRisk) { "$harder injuries" }
            assertTrue(harder.formVolatility > easier.formVolatility) { "$harder form" }
            assertTrue(harder.development < easier.development) { "$harder development" }
        }
    }

    @Test
    fun `a harder game makes the incumbent harder to shift`() {
        val amateur = Difficulty.AMATEUR.applyTo(CareerTuning.DEFAULT)
        val elite = Difficulty.ELITE.applyTo(CareerTuning.DEFAULT)
        assertTrue(elite.selection.weightReputation > amateur.selection.weightReputation)
        // Ability still outweighs reputation even at the hardest setting: a
        // clearly better player gets picked, which is the difference between a
        // hard game and a closed shop.
        assertTrue(elite.selection.weightStandard > elite.selection.weightReputation)
    }

    @Test
    fun `a harder game breaks more bodies and develops players slower`() {
        val elite = Difficulty.ELITE.applyTo(CareerTuning.DEFAULT)
        val base = CareerTuning.DEFAULT
        assertTrue(elite.injury.baseRiskPerMatchPace > base.injury.baseRiskPerMatchPace)
        assertTrue(elite.injury.baseRiskPerMatchSpin > base.injury.baseRiskPerMatchSpin)
        assertTrue(elite.injury.baseRiskPerMatchOutfield > base.injury.baseRiskPerMatchOutfield)
        assertTrue(elite.training.pointsPerFullWeek < base.training.pointsPerFullWeek)
        assertTrue(elite.form.formGainPerSigma > base.form.formGainPerSigma)
    }

    @Test
    fun `applying a difficulty is a pure function of the tuning it is given`() {
        // So a save can store the enum rather than a tuning blob, and two
        // careers on the same setting are the same career.
        val once = Difficulty.ELITE.applyTo(CareerTuning.DEFAULT)
        val twice = Difficulty.ELITE.applyTo(CareerTuning.DEFAULT)
        assertEquals(once, twice)
    }

    @Test
    fun `an unknown stored difficulty loads as the default rather than failing`() {
        // A save written by a later version naming a setting this one does not
        // have must open, not crash.
        assertSame(Difficulty.DEFAULT, Difficulty.ofOrDefault("LEGENDARY"))
        assertSame(Difficulty.DEFAULT, Difficulty.ofOrDefault(null))
        assertSame(Difficulty.ELITE, Difficulty.ofOrDefault("ELITE"))
    }
}
