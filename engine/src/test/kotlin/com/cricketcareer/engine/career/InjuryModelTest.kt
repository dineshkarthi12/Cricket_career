package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.InjuryTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.BowlingStyle
import com.cricketcareer.engine.model.player.HiddenAttributes
import com.cricketcareer.engine.model.player.Injury
import com.cricketcareer.engine.model.player.InjurySeverity
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerState
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InjuryModelTest {

    private val tuning = InjuryTuning()

    private fun player(
        style: BowlingStyle = BowlingStyle.RIGHT_FAST,
        fatigue: Double = 0.0,
        proneness: Int = 50,
        fitness: Int = 50,
    ): Player = Fixtures.averagePlayer("P", bowlingStyle = style).let { p ->
        p.copy(
            attributes = Attributes.uniform(fitness),
            hidden = HiddenAttributes(60, proneness, 50, 50, 60),
            state = PlayerState.FRESH.withFatigueChange(fatigue),
        )
    }

    private fun rate(p: Player, age: Int, seeds: Int = 20_000): Double {
        val random = SimRandom.fromSeed(20260911)
        var broke = 0
        repeat(seeds) {
            if (InjuryModel.roll(p, age, InjuryContext.MATCH, 1.0, random, tuning) != null) broke++
        }
        return broke.toDouble() / seeds
    }

    @Test
    fun `a fast bowler breaks down more often than a spinner or a batter`() {
        val pace = InjuryModel.hazard(player(BowlingStyle.RIGHT_FAST), 24, InjuryContext.MATCH, 1.0, tuning)
        val spin = InjuryModel.hazard(player(BowlingStyle.OFF_BREAK), 24, InjuryContext.MATCH, 1.0, tuning)
        val bat = InjuryModel.hazard(player(BowlingStyle.NONE), 24, InjuryContext.MATCH, 1.0, tuning)
        assertTrue(pace > spin) { "pace $pace vs spin $spin" }
        assertTrue(spin > bat) { "spin $spin vs batter $bat" }
    }

    @Test
    fun `playing tired is how a niggle becomes a tear`() {
        // The load-bearing term. Without it, resting a player is a pure loss.
        val fresh = InjuryModel.hazard(player(fatigue = 0.0), 24, InjuryContext.MATCH, 1.0, tuning)
        val spent = InjuryModel.hazard(player(fatigue = 1.0), 24, InjuryContext.MATCH, 1.0, tuning)
        assertTrue(spent > fresh * 2.5) { "spent $spent should be far above fresh $fresh" }
    }

    @Test
    fun `the brittle cricketer breaks more often than his fitness explains`() {
        val glass = InjuryModel.hazard(player(proneness = 95), 24, InjuryContext.MATCH, 1.0, tuning)
        val durable = InjuryModel.hazard(player(proneness = 5), 24, InjuryContext.MATCH, 1.0, tuning)
        assertTrue(glass > durable) { "glass $glass vs durable $durable" }
    }

    @Test
    fun `risk climbs with age past the late twenties`() {
        val young = InjuryModel.hazard(player(), 24, InjuryContext.MATCH, 1.0, tuning)
        val old = InjuryModel.hazard(player(), 36, InjuryContext.MATCH, 1.0, tuning)
        assertTrue(old > young) { "36 $old vs 24 $young" }
    }

    @Test
    fun `training is safer than a match but not safe`() {
        val match = InjuryModel.hazard(player(), 24, InjuryContext.MATCH, 1.0, tuning)
        val nets = InjuryModel.hazard(player(), 24, InjuryContext.TRAINING, 1.0, tuning)
        assertTrue(nets < match) { "nets $nets vs match $match" }
        assertTrue(nets > 0.0) { "nets should not be risk-free, got $nets" }
    }

    @Test
    fun `a light week is safer than a hard one`() {
        val hard = InjuryModel.hazard(player(), 24, InjuryContext.TRAINING, 1.0, tuning)
        val light = InjuryModel.hazard(player(), 24, InjuryContext.TRAINING, 0.3, tuning)
        assertTrue(light < hard) { "light $light vs hard $hard" }
    }

    @Test
    fun `a seamer misses parts of one or two matches a season`() {
        // The calibration claim in the tuning comment, measured rather than
        // asserted: about 3% of matches for a fresh average 24-year-old.
        val observed = rate(player(), 24)
        assertTrue(observed in 0.015..0.055) {
            "a fresh 24-year-old seamer broke down in $observed of matches, expected 1.5-5.5%"
        }
    }

    @Test
    fun `most injuries are a missed game, not a missed year`() {
        val random = SimRandom.fromSeed(4242)
        val severities = (1..40_000).map {
            InjuryModel.drawSeverity(random.nextDouble(), strain = 0.0, tuning = tuning)
        }
        val light = severities.count { it == InjurySeverity.NIGGLE || it == InjurySeverity.MINOR }
        val severe = severities.count { it == InjurySeverity.SEVERE }
        assertTrue(light.toDouble() / severities.size > 0.6) { "only $light of ${severities.size} were light" }
        assertTrue(severe.toDouble() / severities.size < 0.05) { "$severe severe out of ${severities.size}" }
    }

    @Test
    fun `strain pushes the severity draw toward the serious end`() {
        val random = SimRandom.fromSeed(777)
        fun meanSeverity(strain: Double) = (1..20_000)
            .map { InjuryModel.drawSeverity(random.nextDouble(), strain, tuning).ordinal }
            .average()
        val fresh = meanSeverity(0.0)
        val spent = meanSeverity(1.0)
        assertTrue(spent > fresh) { "spent $spent should be worse than fresh $fresh" }
    }

    @Test
    fun `rehab is shorter for a fitter player`() {
        val fit = InjuryModel.rehabDays(InjurySeverity.MODERATE, Attributes.uniform(95), tuning)
        val unfit = InjuryModel.rehabDays(InjurySeverity.MODERATE, Attributes.uniform(10), tuning)
        assertTrue(fit < unfit) { "fit $fit days vs unfit $unfit days" }
        assertTrue(fit >= 1) { "rehab cannot be zero days, got $fit" }
    }

    @Test
    fun `rehab counts down and then clears`() {
        var state = PlayerState.FRESH.copy(
            injury = Injury("Hamstring strain", InjurySeverity.MINOR, daysRemaining = 3),
        )
        repeat(2) { state = InjuryModel.rehabDay(state) }
        assertEquals(1, state.injury?.daysRemaining)
        state = InjuryModel.rehabDay(state)
        assertNull(state.injury)
        // And a fit player is not re-injured by counting past zero.
        assertNull(InjuryModel.rehabDay(state).injury)
    }

    @Test
    fun `only a severe injury leaves permanent damage`() {
        assertTrue(InjuryModel.leavesPermanentDamage(Injury("ACL tear", InjurySeverity.SEVERE, 200)))
        assertTrue(!InjuryModel.leavesPermanentDamage(Injury("Side strain", InjurySeverity.MODERATE, 30)))
    }

    @Test
    fun `a hazard is always a probability`() {
        for (fatigue in listOf(0.0, 0.5, 1.0)) {
            for (age in 16..44) {
                val h = InjuryModel.hazard(player(fatigue = fatigue, proneness = 100), age, InjuryContext.MATCH, 1.0, tuning)
                assertTrue(h in 0.0..1.0) { "hazard $h at age $age fatigue $fatigue" }
            }
        }
    }

    @Test
    fun `rolling is a pure function of its seed`() {
        val p = player(fatigue = 0.9, proneness = 90)
        fun run() = (1..40).map {
            InjuryModel.roll(p, 33, InjuryContext.MATCH, 1.0, SimRandom.fromSeed(it.toLong()), tuning)?.severity
        }
        assertEquals(run(), run())
    }
}
