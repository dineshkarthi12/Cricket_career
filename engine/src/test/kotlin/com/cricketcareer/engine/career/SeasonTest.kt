package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.BowlingStyle
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.player.PlayerRole
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class SeasonTest {

    private val season = Season(CareerTuning.DEFAULT)
    private val start = LocalDate.of(2026, 4, 1)
    private val end = LocalDate.of(2026, 9, 30)

    private fun p(id: String, role: PlayerRole, style: BowlingStyle, rating: Int = 55): Player =
        Fixtures.averagePlayer(id, role = role, bowlingStyle = style)
            .copy(id = PlayerId(id), attributes = Attributes.uniform(rating))

    /** Fourteen players: a legal XI with three spare. */
    private fun squad(): List<Player> = listOf(
        p("BAT1", PlayerRole.OPENING_BAT, BowlingStyle.NONE),
        p("BAT2", PlayerRole.OPENING_BAT, BowlingStyle.NONE),
        p("BAT3", PlayerRole.TOP_ORDER_BAT, BowlingStyle.NONE),
        p("BAT4", PlayerRole.MIDDLE_ORDER_BAT, BowlingStyle.NONE),
        p("BAT5", PlayerRole.MIDDLE_ORDER_BAT, BowlingStyle.NONE),
        p("BAT6", PlayerRole.FINISHER, BowlingStyle.NONE),
        p("BAT7", PlayerRole.MIDDLE_ORDER_BAT, BowlingStyle.NONE),
        p("KEEP", PlayerRole.WICKETKEEPER_BAT, BowlingStyle.NONE),
        p("AR1", PlayerRole.SEAM_ALLROUNDER, BowlingStyle.RIGHT_FAST_MEDIUM),
        p("AR2", PlayerRole.SPIN_ALLROUNDER, BowlingStyle.OFF_BREAK),
        p("SEAM1", PlayerRole.FAST_BOWLER, BowlingStyle.RIGHT_FAST),
        p("SEAM2", PlayerRole.FAST_BOWLER, BowlingStyle.RIGHT_FAST_MEDIUM),
        p("SPIN1", PlayerRole.SPINNER, BowlingStyle.LEG_BREAK),
        p("SPIN2", PlayerRole.SPINNER, BowlingStyle.SLOW_LEFT_ARM_ORTHODOX),
    )

    private fun fixtures(n: Int, format: MatchFormat = Fixtures.T20, everyDays: Long = 5): List<Fixture> =
        List(n) { i ->
            Fixture(
                id = "F%02d".format(i),
                date = start.plusDays(7 + i * everyDays),
                format = format,
                level = LadderLevel.STATE_WHITE_BALL,
            )
        }

    private fun play(
        squad: List<Player> = squad(),
        fixtures: List<Fixture> = fixtures(14),
        seed: Long = 2026,
        personality: SelectorPersonality = SelectorPersonality.AVERAGE,
        seasonEnd: LocalDate = end,
    ) = season.play(squad, fixtures, start, seasonEnd, personality, coaching = 0.6, random = CareerRandom(seed))

    @Test
    fun `every fixture fields exactly eleven players`() {
        val records = play()
        val fixtures = fixtures(14).map { it.id }
        fixtures.forEach { id ->
            val playedIt = records.count { record -> record.appearances.any { it.fixture == id } }
            assertEquals(11, playedIt) { "fixture $id fielded $playedIt" }
        }
    }

    @Test
    fun `a player either plays a fixture or is recorded as left out`() {
        // A season where a player quietly vanishes is a season with a bug in it.
        val records = play()
        records.forEach { record ->
            assertTrue(record.matches + record.omissions <= 14) {
                "${record.player.id} has ${record.matches} caps and ${record.omissions} omissions"
            }
        }
        // Fourteen players, eleven places, fourteen matches: somebody misses out.
        assertTrue(records.sumOf { it.omissions } > 0) { "nobody was ever left out of a 14-man squad" }
    }

    @Test
    fun `a better player plays more often than a worse one`() {
        val squad = squad().map {
            when (it.id.value) {
                "BAT1" -> it.copy(attributes = Attributes.uniform(92))
                "BAT7" -> it.copy(attributes = Attributes.uniform(18))
                else -> it
            }
        }
        val records = play(squad)
        val star = records.first { it.player.id.value == "BAT1" }
        val filler = records.first { it.player.id.value == "BAT7" }
        assertTrue(star.matches > filler.matches) {
            "star played ${star.matches}, filler played ${filler.matches}"
        }
    }

    @Test
    fun `a season produces cricket-shaped figures`() {
        val records = play(fixtures = fixtures(18))
        val regulars = records.filter { it.matches >= 8 }
        assertTrue(regulars.isNotEmpty()) { "nobody played a season" }
        regulars.forEach { record ->
            val average = record.battingAverage
            if (average != null) {
                assertTrue(average in 3.0..120.0) { "${record.player.id} averaged $average" }
            }
            assertTrue(record.highestScore >= 0)
            assertTrue(record.runs >= 0)
        }
    }

    @Test
    fun `playing a season costs fatigue and playing keeps a player sharp`() {
        val schedule = fixtures(16, everyDays = 3)
        val records = play(fixtures = schedule, seasonEnd = schedule.last().date)
        val busiest = records.maxBy { it.matches }
        assertTrue(busiest.player.state.sharpness > 0.5) {
            "a regular should stay sharp, got ${busiest.player.state.sharpness}"
        }
        val unused = records.minBy { it.matches }
        assertTrue(unused.player.state.sharpness < busiest.player.state.sharpness) {
            "the man who did not play should be the rusty one"
        }
    }

    @Test
    fun `a crowded season breaks more players than a spread-out one`() {
        // The point of charging fatigue per day rather than per match.
        fun brokenIn(everyDays: Long): Int = (1L..40L).sumOf { seed ->
            play(fixtures = fixtures(18, everyDays = everyDays), seed = seed)
                .sumOf { it.injuries }
        }
        val crowded = brokenIn(2)
        val spread = brokenIn(9)
        assertTrue(crowded > spread) { "crowded broke $crowded, spread broke $spread" }
    }

    @Test
    fun `the fixture order handed over does not change the season`() {
        val shuffled = fixtures(14).reversed()
        val a = play(fixtures = fixtures(14))
        val b = play(fixtures = shuffled)
        assertEquals(
            a.map { it.player.id to it.matches },
            b.map { it.player.id to it.matches },
        )
    }

    @Test
    fun `a season is a pure function of its seed`() {
        assertEquals(play(seed = 99), play(seed = 99))
    }

    @Test
    fun `different seeds give different seasons`() {
        assertTrue(play(seed = 1) != play(seed = 2)) { "two seeds produced an identical season" }
    }

    @Test
    fun `a season with no fixtures still ages and rests the squad`() {
        val records = play(fixtures = emptyList())
        records.forEach {
            assertEquals(0, it.matches)
            // Six months without a match is six months of rust.
            assertTrue(it.player.state.sharpness < 0.4) { "sharpness ${it.player.state.sharpness}" }
        }
    }

    @Test
    fun `a squad too small to field a side plays nothing rather than padding it`() {
        val records = play(squad = squad().take(9))
        assertTrue(records.all { it.matches == 0 }) { "a nine-man squad fielded a side" }
    }

    @Test
    fun `a fixture outside the season is rejected`() {
        val stray = fixtures(3) + Fixture("STRAY", end.plusDays(40), Fixtures.T20, LadderLevel.STATE_WHITE_BALL)
        assertThrows<IllegalArgumentException> { play(fixtures = stray) }
    }

    @Test
    fun `a season that ends before it starts is rejected`() {
        assertThrows<IllegalArgumentException> {
            season.play(squad(), emptyList(), end, start, SelectorPersonality.AVERAGE, 0.6, CareerRandom(1))
        }
    }

    @Test
    fun `a multi-day season is a heavier workload than a T20 one`() {
        val t20 = play(fixtures = fixtures(10, Fixtures.T20, everyDays = 6))
        val fourDay = play(fixtures = fixtures(10, MatchFormat.FOUR_DAY, everyDays = 6))
        val t20Fatigue = t20.map { it.player.state.fatigue }.average()
        val fourDayFatigue = fourDay.map { it.player.state.fatigue }.average()
        assertTrue(fourDayFatigue > t20Fatigue) {
            "four-day $fourDayFatigue should cost more than T20 $t20Fatigue"
        }
    }

    @Test
    fun `a young regular develops over a season and a reserve does not`() {
        // Exposure has to reach the clock, or nobody in the world ever gets
        // better by playing and every career is flat.
        val young = squad().map {
            it.copy(
                dateOfBirth = LocalDate.of(2008, 5, 20),
                hidden = it.hidden.copy(potential = 95, learningRate = 85),
                attributes = Attributes.uniform(if (it.id.value == "BAT7") 18 else 45),
            )
        }
        val records = play(squad = young, fixtures = fixtures(16))
        val regular = records.maxBy { it.matches }
        val reserve = records.minBy { it.matches }
        assertTrue(regular.matches > reserve.matches) { "the squad was not contested" }
        assertTrue(regular.exposure.minutes > reserve.exposure.minutes) {
            "regular ${regular.exposure.minutes} vs reserve ${reserve.exposure.minutes}"
        }
        assertTrue(regular.player.attributes[Attribute.TECHNIQUE] > 45) {
            "a young regular should improve, got ${regular.player.attributes[Attribute.TECHNIQUE]} from 45"
        }
    }

    @Test
    fun `a decade of seasons leaves every value in range`() {
        var squad = squad()
        repeat(10) { year ->
            val from = LocalDate.of(2026 + year, 4, 1)
            val to = LocalDate.of(2026 + year, 9, 30)
            val fixtures = List(16) { i ->
                Fixture("Y$year-F$i", from.plusDays(7 + i * 5L), Fixtures.T20, LadderLevel.STATE_WHITE_BALL)
            }
            squad = season.play(
                squad, fixtures, from, to, SelectorPersonality.AVERAGE, 0.6, CareerRandom(400L + year),
            ).map { it.player }
        }
        squad.forEach { player ->
            assertTrue(player.state.fatigue in 0.0..1.0) { "${player.id} fatigue ${player.state.fatigue}" }
            assertTrue(player.state.sharpness in 0.0..1.0) { "${player.id} sharpness ${player.state.sharpness}" }
            assertTrue(kotlin.math.abs(player.state.form) <= 1.0) { "${player.id} form ${player.state.form}" }
        }
    }
}
