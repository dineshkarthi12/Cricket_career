package com.cricketcareer.presentation

import com.cricketcareer.engine.career.Appearance
import com.cricketcareer.engine.career.SeasonRecord
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.AttributeGroup
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.HiddenAttributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerState
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.Pitch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class ProfileAndCareerTest {

    private val today = LocalDate.of(2026, 6, 1)

    private fun player(
        rating: Int = 60,
        potential: Int = 90,
        form: Double = 0.0,
        fatigue: Double = 0.0,
    ): Player = Fixtures.averagePlayer("P").copy(
        attributes = Attributes.uniform(rating),
        hidden = HiddenAttributes(potential, 73, 41, 88, 62),
        state = PlayerState.FRESH.copy(form = form).withFatigueChange(fatigue),
    )

    // ---- The rule this layer must not break -----------------------------

    @Test
    fun `no hidden attribute reaches the profile, as a number or as text`() {
        // Potential, injury proneness, temperament, big-match factor and
        // learning rate are the things a cricketer never sees a number for.
        val hidden = HiddenAttributes(potential = 87, injuryProneness = 73, temperament = 41, bigMatchFactor = 88, learningRate = 62)
        val state = playerProfileState(player().copy(hidden = hidden), today)
        val rendered = listOf(
            state.name, state.role, state.potentialDescription, state.form, state.fitness,
            state.battingStyle, state.bowlingStyle, state.dateOfBirth,
        ).joinToString(" ") + state.groups.joinToString(" ") { group ->
            "${group.name} ${group.overall} " + group.rows.joinToString(" ") { "${it.name} ${it.value}" }
        }
        listOf(87, 73, 41, 88, 62).forEach { value ->
            assertTrue(!Regex("\\b$value\\b").containsMatchIn(rendered)) {
                "the hidden value $value appears in the profile: $rendered"
            }
        }
    }

    @Test
    fun `potential is a band a coach would say, never a figure`() {
        val raw = playerProfileState(player(rating = 40, potential = 90), today).potentialDescription
        val finished = playerProfileState(player(rating = 88, potential = 90), today).potentialDescription
        assertTrue(raw != finished) { "a raw talent and a finished player got the same description" }
        assertTrue(raw.none { it.isDigit() }) { "a number leaked into '$raw'" }
        assertTrue(finished.none { it.isDigit() }) { "a number leaked into '$finished'" }
    }

    // ---- Profile --------------------------------------------------------

    @Test
    fun `age is counted in completed years`() {
        val p = player().copy(dateOfBirth = LocalDate.of(2004, 6, 1))
        assertEquals(22, playerProfileState(p, today).age)
        assertEquals(21, playerProfileState(p, today.minusDays(1)).age)
    }

    @Test
    fun `every attribute appears exactly once, in a stable order`() {
        // A profile whose rows rearrange themselves as a player improves is one
        // nobody can learn to read.
        val groups = attributeGroups(Attributes.uniform(50))
        val all = groups.flatMap { it.rows }.map { it.name }
        assertEquals(Attribute.ALL.size, all.size)
        assertEquals(all.distinct(), all)
        assertEquals(AttributeGroup.ALL.map { it.displayName }, groups.map { it.name })
    }

    @Test
    fun `a bar's fraction matches its value`() {
        val rows = attributeGroups(Attributes.uniform(1)).flatMap { it.rows }
        rows.forEach { assertEquals(0.0, it.fraction, 1e-9) }
        attributeGroups(Attributes.uniform(100)).flatMap { it.rows }
            .forEach { assertEquals(1.0, it.fraction, 1e-9) }
    }

    @Test
    fun `form and fitness read as English, and the worst cases are distinguishable`() {
        assertTrue(playerProfileState(player(form = 0.9), today).form != playerProfileState(player(form = -0.9), today).form)
        assertEquals("Fresh", playerProfileState(player(fatigue = 0.0), today).fitness)
        assertTrue(playerProfileState(player(fatigue = 0.9), today).fitness != "Fresh")
    }

    // ---- Career table ---------------------------------------------------

    private fun season(runs: List<Pair<Int, Boolean>>): SeasonRecord = SeasonRecord(
        player = player(),
        appearances = runs.mapIndexed { i, (r, out) ->
            Appearance(
                fixture = "F$i",
                date = today.plusDays(i.toLong()),
                format = Fixtures.T20,
                level = LadderLevel.STATE_WHITE_BALL,
                runs = r,
                ballsFaced = r + 5,
                out = out,
            )
        },
        omissions = 0,
    )

    @Test
    fun `a career average divides total runs by total dismissals, once`() {
        // Averaging the seasonal averages weights a two-innings season like a
        // twenty-innings one and produces a figure that appears nowhere.
        val a = season(listOf(100 to true, 0 to true))   // averages 50
        val b = season(listOf(10 to true))               // averages 10
        val table = careerTableState(listOf(a, b), listOf("2026", "2027"))
        assertEquals("36.67", table.totals.average) { "not the mean of 50 and 10" }
        assertEquals(110, table.totals.runs)
        assertEquals(3, table.totals.matches)
    }

    @Test
    fun `an unbeaten best score carries a star`() {
        val table = careerTableState(listOf(season(listOf(80 to false, 40 to true))), listOf("2026"))
        assertEquals("80*", table.rows.single().highestScore)
        assertEquals("80*", table.totals.highestScore)
    }

    @Test
    fun `a player who was never dismissed has no average to show`() {
        val table = careerTableState(listOf(season(listOf(12 to false))), listOf("2026"))
        assertEquals("-", table.rows.single().average)
        assertEquals("-", table.totals.average)
    }

    @Test
    fun `a season with no cricket in it still gets a row`() {
        val table = careerTableState(listOf(season(emptyList())), listOf("2026"))
        assertEquals(0, table.rows.single().matches)
        assertEquals("-", table.rows.single().highestScore)
    }

    @Test
    fun `labels and seasons must line up`() {
        assertThrows<IllegalArgumentException> {
            careerTableState(listOf(season(listOf(1 to true))), listOf("2026", "2027"))
        }
    }

    // ---- Conditions -----------------------------------------------------

    @Test
    fun `the pitch report shows the engine's own fields and invents nothing`() {
        val pitch = Pitch.AVERAGE.copy(turn = 0.9, gripSeam = 0.2)
        val report = pitchReportState(pitch)
        assertEquals(0.9, report.gauges.first { it.name == "Turn" }.value, 1e-9)
        assertEquals(0.2, report.gauges.first { it.name == "Seam grip" }.value, 1e-9)
        // No combined "batting friendliness" number: the engine deliberately
        // has none, and inventing one here is the same mistake one module up.
        assertTrue(report.gauges.none { it.name.contains("friend", ignoreCase = true) })
    }

    @Test
    fun `a turning pitch and a green top do not read the same`() {
        val turner = pitchReportState(Pitch.AVERAGE.copy(turn = 0.9, gripSeam = 0.1, grassCover = 0.1))
        val green = pitchReportState(Pitch.AVERAGE.copy(turn = 0.1, gripSeam = 0.9, grassCover = 0.8))
        assertTrue(turner.summary != green.summary) { "both read '${turner.summary}'" }
        assertTrue(turner.summary.contains("turn")) { turner.summary }
        assertTrue(green.summary.contains("seam")) { green.summary }
    }

    @Test
    fun `bad light and a dewy evening read differently`() {
        val gloom = conditionsState(Weather.AVERAGE.copy(lightQuality = 0.2))
        val dewy = conditionsState(Weather.AVERAGE.copy(dew = 0.8))
        assertTrue(gloom.summary != dewy.summary)
        assertTrue(gloom.summary.contains("light")) { gloom.summary }
    }

    @Test
    fun `a gauge outside its range is rejected rather than drawn off the end of the bar`() {
        assertThrows<IllegalArgumentException> { Gauge("Turn", 1.4) }
        assertThrows<IllegalArgumentException> { Gauge("Turn", -0.1) }
    }
}
