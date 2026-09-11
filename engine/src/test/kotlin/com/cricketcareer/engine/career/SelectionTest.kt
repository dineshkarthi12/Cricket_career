package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.SelectionTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.BowlingStyle
import com.cricketcareer.engine.model.player.Injury
import com.cricketcareer.engine.model.player.InjurySeverity
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.player.PlayerRole
import com.cricketcareer.engine.model.player.PlayerState
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SelectionTest {

    private val tuning = SelectionTuning()
    private val quiet = SelectionTuning(judgementSigma = 0.0)

    private fun rng(seed: Long = 5) = SimRandom.fromSeed(seed)

    private fun p(
        id: String,
        role: PlayerRole = PlayerRole.MIDDLE_ORDER_BAT,
        style: BowlingStyle = BowlingStyle.NONE,
        rating: Int = 50,
        form: Double = 0.0,
        sharpness: Double = 1.0,
    ): Player = Fixtures.averagePlayer(id, role = role, bowlingStyle = style).copy(
        attributes = Attributes.uniform(rating),
        state = PlayerState.FRESH.copy(form = form, sharpness = sharpness),
    )

    /** A legal squad: six batters incl. keeper, two all-rounders, four bowlers, plus spares. */
    private fun squad(): List<Player> = listOf(
        p("BAT1", PlayerRole.OPENING_BAT), p("BAT2", PlayerRole.OPENING_BAT),
        p("BAT3", PlayerRole.TOP_ORDER_BAT), p("BAT4", PlayerRole.MIDDLE_ORDER_BAT),
        p("BAT5", PlayerRole.MIDDLE_ORDER_BAT), p("BAT6", PlayerRole.FINISHER),
        p("KEEP1", PlayerRole.WICKETKEEPER_BAT), p("KEEP2", PlayerRole.WICKETKEEPER_BAT, rating = 35),
        p("AR1", PlayerRole.SEAM_ALLROUNDER, BowlingStyle.RIGHT_FAST_MEDIUM),
        p("AR2", PlayerRole.SPIN_ALLROUNDER, BowlingStyle.OFF_BREAK),
        p("SEAM1", PlayerRole.FAST_BOWLER, BowlingStyle.RIGHT_FAST),
        p("SEAM2", PlayerRole.FAST_BOWLER, BowlingStyle.RIGHT_FAST_MEDIUM),
        p("SEAM3", PlayerRole.FAST_BOWLER, BowlingStyle.LEFT_FAST_MEDIUM),
        p("SPIN1", PlayerRole.SPINNER, BowlingStyle.LEG_BREAK),
        p("SPIN2", PlayerRole.SPINNER, BowlingStyle.SLOW_LEFT_ARM_ORTHODOX),
    )

    private fun context(pitch: Pitch = Pitch.AVERAGE, incumbents: Set<String> = emptySet(), caps: Map<String, Int> = emptyMap()) =
        SelectionContext(
            format = Fixtures.T20,
            pitch = pitch,
            incumbents = incumbents.map { PlayerId(it) }.toSet(),
            caps = caps.mapKeys { PlayerId(it.key) },
        )

    private fun ids(picked: List<SelectionScore>) = picked.map { it.player.id.value }.toSet()

    @Test
    fun `a better player outscores a worse one`() {
        val good = Selection.standardFor(p("A", rating = 85), Fixtures.T20)
        val poor = Selection.standardFor(p("B", rating = 25), Fixtures.T20)
        assertTrue(good > poor) { "85 scored $good, 25 scored $poor" }
    }

    @Test
    fun `a Test side wants patience and a T20 side wants power`() {
        val grinder = p("G").copy(
            attributes = Attributes.uniform(40)
                .with(Attribute.PATIENCE, 92).with(Attribute.CONCENTRATION, 92),
        )
        val hitter = p("H").copy(
            attributes = Attributes.uniform(40)
                .with(Attribute.POWER, 92).with(Attribute.RANGE_HITTING, 92),
        )
        assertTrue(
            Selection.standardFor(grinder, Fixtures.TEST) > Selection.standardFor(hitter, Fixtures.TEST),
        ) { "the grinder should win the Test place" }
        assertTrue(
            Selection.standardFor(hitter, Fixtures.T20) > Selection.standardFor(grinder, Fixtures.T20),
        ) { "the hitter should win the T20 place" }
    }

    @Test
    fun `a turning pitch is worth a spinner and a green one a seamer`() {
        val turner = Pitch.AVERAGE.copy(turn = 0.95, gripSeam = 0.2, grassCover = 0.1)
        val green = Pitch.AVERAGE.copy(turn = 0.1, gripSeam = 0.9, grassCover = 0.85)
        val spinner = p("SP", PlayerRole.SPINNER, BowlingStyle.OFF_BREAK)
        val seamer = p("SE", PlayerRole.FAST_BOWLER, BowlingStyle.RIGHT_FAST)

        assertTrue(
            Selection.suitability(spinner, context(turner)) > Selection.suitability(seamer, context(turner)),
        ) { "spinner should suit the turner" }
        assertTrue(
            Selection.suitability(seamer, context(green)) > Selection.suitability(spinner, context(green)),
        ) { "seamer should suit the green top" }
    }

    @Test
    fun `one failure does not cost an incumbent his place`() {
        // The reputation term. Without it the side churns every week and a
        // debut is worth nothing because everyone gets one.
        val incumbent = p("IN", form = -0.25)
        val challenger = p("CH", form = 0.0)
        val ctx = context(incumbents = setOf("IN"), caps = mapOf("IN" to 30))
        val scores = Selection.score(listOf(incumbent, challenger), ctx, SelectorPersonality.AVERAGE, rng(), quiet)
        assertEquals("IN", scores.first().player.id.value) {
            "incumbent should survive one failure: ${scores.map { it.player.id.value to it.total }}"
        }
    }

    @Test
    fun `a young player must be clearly better, not marginally better`() {
        val incumbent = p("IN", rating = 60)
        val marginal = p("MARGIN", rating = 63)
        val clearlyBetter = p("CLEAR", rating = 88)
        val ctx = context(incumbents = setOf("IN"), caps = mapOf("IN" to 45))
        val scores = Selection.score(listOf(incumbent, marginal, clearlyBetter), ctx, SelectorPersonality.AVERAGE, rng(), quiet)
        val order = scores.map { it.player.id.value }
        assertEquals("CLEAR", order.first()) { "order was $order" }
        assertTrue(order.indexOf("IN") < order.indexOf("MARGIN")) { "order was $order" }
    }

    @Test
    fun `a hundred-cap player gets a longer run than a three-cap one`() {
        val ctx = context(caps = mapOf("VET" to 100, "NEW" to 3))
        val vet = Selection.reputation(p("VET"), ctx)
        val new = Selection.reputation(p("NEW"), ctx)
        assertTrue(vet > new) { "vet $vet vs new $new" }
    }

    @Test
    fun `rustiness and a niggle both count against a player`() {
        assertTrue(Selection.risk(p("RUSTY", sharpness = 0.2), tuning) > Selection.risk(p("SHARP"), tuning))
        val niggled = p("NIG").let {
            it.copy(state = it.state.copy(injury = Injury("Tight hamstring", InjurySeverity.NIGGLE, 2)))
        }
        assertTrue(Selection.risk(niggled, tuning) > Selection.risk(p("FIT"), tuning))
    }

    @Test
    fun `a bold selector discounts rustiness and a cautious one does not`() {
        val rusty = p("R", sharpness = 0.15)
        val safe = p("S", sharpness = 1.0, rating = 48)
        val bold = SelectorPersonality(loyalty = 0.5, boldness = 0.95, formWeighting = 0.5)
        val cautious = SelectorPersonality(loyalty = 0.5, boldness = 0.05, formWeighting = 0.5)
        fun gap(personality: SelectorPersonality): Double {
            val s = Selection.score(listOf(rusty, safe), context(), personality, rng(), quiet)
            return s.first { it.player.id.value == "R" }.total - s.first { it.player.id.value == "S" }.total
        }
        assertTrue(gap(bold) > gap(cautious)) { "bold ${gap(bold)} vs cautious ${gap(cautious)}" }
    }

    @Test
    fun `two panels reading the same numbers do not always pick the same side`() {
        // If they always agreed, a career would be a calculation rather than
        // something happening to the player.
        val squad = squad()
        val a = Selection.pickXI(squad, context(), SelectorPersonality(0.9, 0.1, 0.2), rng(1), tuning)
        val b = Selection.pickXI(squad, context(), SelectorPersonality(0.2, 0.9, 0.8), rng(2), tuning)
        assertTrue(ids(a) != ids(b) || a.map { it.player.id } != b.map { it.player.id }) {
            "two very different panels picked an identical side"
        }
    }

    @Test
    fun `the XI always has a keeper`() {
        // Strip the good keeper out of contention by making the rest superb,
        // and check the panel still finds one.
        val squad = squad().map {
            if (it.role.keeps) it.copy(attributes = Attributes.uniform(20)) else it.copy(attributes = Attributes.uniform(85))
        }
        val picked = Selection.pickXI(squad, context(), SelectorPersonality.AVERAGE, rng(), tuning)
        assertEquals(11, picked.size)
        assertTrue(picked.count { it.player.role.keeps } >= 1) {
            "no keeper in ${picked.map { it.player.id.value to it.player.role }}"
        }
    }

    @Test
    fun `the XI can always bowl its overs`() {
        val squad = squad().map {
            if (it.role.bowls) it.copy(attributes = Attributes.uniform(22)) else it.copy(attributes = Attributes.uniform(88))
        }
        val picked = Selection.pickXI(squad, context(), SelectorPersonality.AVERAGE, rng(), tuning)
        assertTrue(picked.count { it.player.role.bowls } >= tuning.minimumBowlers) {
            "only ${picked.count { it.player.role.bowls }} bowlers in ${picked.map { it.player.id.value }}"
        }
    }

    @Test
    fun `the XI always has a top six`() {
        val squad = squad().map {
            if (it.role.primary == com.cricketcareer.engine.model.player.RoleDiscipline.BATTING) {
                it.copy(attributes = Attributes.uniform(20))
            } else {
                it.copy(attributes = Attributes.uniform(90))
            }
        }
        val picked = Selection.pickXI(squad, context(), SelectorPersonality.AVERAGE, rng(), tuning)
        assertTrue(
            picked.count { it.player.role.primary == com.cricketcareer.engine.model.player.RoleDiscipline.BATTING } >= tuning.minimumBatters,
        ) { "only ${picked.count { it.player.role.primary == com.cricketcareer.engine.model.player.RoleDiscipline.BATTING }} batters" }
    }

    @Test
    fun `an injured player is never picked`() {
        val squad = squad().map { player ->
            // The best player in the squad, and unavailable.
            if (player.id.value == "BAT1") {
                player.copy(
                    attributes = Attributes.uniform(99),
                    state = player.state.copy(injury = Injury("Calf strain", InjurySeverity.MODERATE, 20)),
                )
            } else {
                player
            }
        }
        val picked = Selection.pickXI(squad, context(), SelectorPersonality.AVERAGE, rng(), tuning)
        assertTrue(picked.none { it.player.id.value == "BAT1" }) { "picked an injured player" }
    }

    @Test
    fun `a player carrying a niggle is still pickable`() {
        val squad = squad().map { player ->
            if (player.id.value == "BAT1") {
                player.copy(
                    attributes = Attributes.uniform(99),
                    state = player.state.copy(injury = Injury("Sore shoulder", InjurySeverity.NIGGLE, 2)),
                )
            } else {
                player
            }
        }
        val picked = Selection.pickXI(squad, context(), SelectorPersonality.AVERAGE, rng(), quiet)
        assertTrue(picked.any { it.player.id.value == "BAT1" }) {
            "a niggle should be a mark-down, not a bar: ${picked.map { it.player.id.value }}"
        }
    }

    @Test
    fun `a squad too small to field an XI is rejected rather than padded`() {
        assertThrows<IllegalArgumentException> {
            Selection.pickXI(squad().take(8), context(), SelectorPersonality.AVERAGE, rng(), tuning)
        }
    }

    @Test
    fun `selection is a pure function of its seed`() {
        val squad = squad()
        val a = Selection.pickXI(squad, context(), SelectorPersonality.AVERAGE, rng(808), tuning)
        val b = Selection.pickXI(squad, context(), SelectorPersonality.AVERAGE, rng(808), tuning)
        assertEquals(a.map { it.player.id }, b.map { it.player.id })
    }

    @Test
    fun `the order does not depend on the order the squad was handed over`() {
        val squad = squad()
        val a = Selection.score(squad, context(), SelectorPersonality.AVERAGE, rng(9), quiet)
        val b = Selection.score(squad.reversed(), context(), SelectorPersonality.AVERAGE, rng(9), quiet)
        // Judgement noise is off, so only the deterministic ranking is left and
        // it must not depend on input order.
        assertEquals(a.map { it.player.id }, b.map { it.player.id })
    }

    @Test
    fun `a drawn personality is always in range`() {
        val random = rng(31)
        repeat(500) {
            val personality = SelectorPersonality.draw(random)
            assertTrue(personality.loyalty in 0.0..1.0)
            assertTrue(personality.boldness in 0.0..1.0)
            assertTrue(personality.formWeighting in 0.0..1.0)
        }
    }
}
