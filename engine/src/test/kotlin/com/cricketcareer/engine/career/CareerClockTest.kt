package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
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
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class CareerClockTest {

    private val clock = CareerClock(CareerTuning.DEFAULT)
    private val start = LocalDate.of(2026, 4, 1)

    private fun rng(seed: Long = 3) = SimRandom.fromSeed(seed)

    private fun player(
        dob: LocalDate = LocalDate.of(2004, 6, 15),
        rating: Int = 50,
        potential: Int = 85,
        state: PlayerState = PlayerState.FRESH,
    ): Player = Fixtures.averagePlayer("P").copy(
        dateOfBirth = dob,
        attributes = Attributes.uniform(rating),
        hidden = HiddenAttributes(potential, 50, 50, 50, 70),
        state = state,
    )

    @Test
    fun `a day of rest recovers fatigue and costs sharpness`() {
        val tired = player(state = PlayerState.FRESH.withFatigueChange(0.6))
        val after = clock.advanceDay(tired, start, SeasonExposure.NONE, rng())
        assertTrue(after.state.fatigue < 0.6) { "fatigue ${after.state.fatigue}" }
        assertTrue(after.state.sharpness < 1.0) { "sharpness ${after.state.sharpness}" }
    }

    @Test
    fun `rehab counts down day by day and clears on the last one`() {
        val injured = player(
            state = PlayerState.FRESH.copy(injury = Injury("Calf strain", InjurySeverity.MINOR, 3)),
        )
        val after2 = clock.advance(listOf(injured), start, start.plusDays(2), random = rng()).single()
        assertEquals(1, after2.state.injury?.daysRemaining)
        val after3 = clock.advance(listOf(injured), start, start.plusDays(3), random = rng()).single()
        assertNull(after3.state.injury)
        assertTrue(after3.state.isAvailable)
    }

    @Test
    fun `a player is available on the last day of rehab, not the day after`() {
        // Rehab runs first in the daily order precisely so this is true.
        val injured = player(
            state = PlayerState.FRESH.copy(injury = Injury("Side strain", InjurySeverity.MINOR, 1)),
        )
        val after = clock.advanceDay(injured, start, SeasonExposure.NONE, rng())
        assertNull(after.state.injury)
    }

    @Test
    fun `a severe injury takes something permanent when it resolves`() {
        val injured = player(
            rating = 70,
            state = PlayerState.FRESH.copy(
                injury = Injury("Achilles rupture", InjurySeverity.SEVERE, daysRemaining = 1),
            ),
        )
        val after = clock.advanceDay(injured, start, SeasonExposure.NONE, rng())
        assertNull(after.state.injury)
        assertTrue(after.attributes[Attribute.PACE] < 70) { "pace ${after.attributes[Attribute.PACE]}" }
        assertEquals(70, after.attributes[Attribute.COMPOSURE]) { "the mind is untouched" }
    }

    @Test
    fun `a lesser injury takes nothing permanent`() {
        val injured = player(
            rating = 70,
            state = PlayerState.FRESH.copy(
                injury = Injury("Hamstring strain", InjurySeverity.MODERATE, daysRemaining = 1),
            ),
        )
        val after = clock.advanceDay(injured, start, SeasonExposure.NONE, rng())
        assertEquals(70, after.attributes[Attribute.PACE])
    }

    @Test
    fun `ageing happens on the birthday and only then`() {
        val p = player(dob = LocalDate.of(2004, 6, 15), rating = 45)
        val exposure = mapOf(p.id to SeasonExposure(minutes = 1.0, coaching = 0.8))

        val dayBefore = clock.advance(listOf(p), LocalDate.of(2026, 6, 13), LocalDate.of(2026, 6, 14), exposure, rng()).single()
        assertEquals(45, dayBefore.attributes[Attribute.TECHNIQUE]) { "nothing should happen on the 14th" }

        val birthday = clock.advance(listOf(p), LocalDate.of(2026, 6, 14), LocalDate.of(2026, 6, 15), exposure, rng()).single()
        assertTrue(birthday.attributes[Attribute.TECHNIQUE] > 45) {
            "should have improved on the 15th, got ${birthday.attributes[Attribute.TECHNIQUE]}"
        }
    }

    @Test
    fun `a leap-day player ages every year, not once in four`() {
        // Invisible for decades and then one immortal cricketer.
        val leapling = player(dob = LocalDate.of(2004, 2, 29))
        assertTrue(clock.isBirthday(leapling, LocalDate.of(2028, 2, 29))) { "leap year: the day itself" }
        assertTrue(clock.isBirthday(leapling, LocalDate.of(2027, 3, 1))) { "non-leap year: 1 March" }
        assertTrue(!clock.isBirthday(leapling, LocalDate.of(2028, 3, 1))) { "leap year: not twice" }
        assertTrue(!clock.isBirthday(leapling, LocalDate.of(2027, 2, 28))) { "not 28 February" }
    }

    @Test
    fun `a season of second-XI cricket develops nobody`() {
        val p = player(dob = LocalDate.of(2006, 6, 15), rating = 45)
        val from = LocalDate.of(2026, 6, 14)
        val to = LocalDate.of(2026, 6, 15)
        val played = clock.advance(listOf(p), from, to, mapOf(p.id to SeasonExposure(1.0, 0.8)), rng()).single()
        val benched = clock.advance(listOf(p), from, to, mapOf(p.id to SeasonExposure(0.0, 0.8)), rng()).single()
        assertTrue(played.attributes[Attribute.TECHNIQUE] > benched.attributes[Attribute.TECHNIQUE]) {
            "played ${played.attributes[Attribute.TECHNIQUE]} vs benched ${benched.attributes[Attribute.TECHNIQUE]}"
        }
    }

    @Test
    fun `two months out leaves a player short of cricket and fully recovered`() {
        val p = player(state = PlayerState.FRESH.withFatigueChange(0.8))
        val after = clock.advance(listOf(p), start, start.plusDays(60), random = rng()).single()
        assertTrue(after.state.fatigue < 0.02) { "fatigue ${after.state.fatigue}" }
        assertTrue(after.state.sharpness < 0.6) { "sharpness ${after.state.sharpness}" }
    }

    @Test
    fun `age is counted in completed years`() {
        val p = player(dob = LocalDate.of(2004, 6, 15))
        assertEquals(21, clock.ageOn(p, LocalDate.of(2026, 6, 14)))
        assertEquals(22, clock.ageOn(p, LocalDate.of(2026, 6, 15)))
    }

    @Test
    fun `advancing zero days changes nothing`() {
        val p = player(state = PlayerState.FRESH.withFatigueChange(0.4))
        assertEquals(listOf(p), clock.advance(listOf(p), start, start, random = rng()))
    }

    @Test
    fun `the clock refuses to run backwards`() {
        assertThrows<IllegalArgumentException> {
            clock.advance(listOf(player()), start, start.minusDays(1), random = rng())
        }
    }

    @Test
    fun `a decade of clock is a pure function of its seed`() {
        val squad = (1..6).map { player(dob = LocalDate.of(2000 + it, it, 10)).copy(id = com.cricketcareer.engine.model.player.PlayerId("P$it")) }
        fun run() = clock.advance(
            squad,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2036, 1, 1),
            squad.associate { it.id to SeasonExposure(0.7, 0.6) },
            SimRandom.fromSeed(20260911),
        )
        assertEquals(run(), run())
    }

    @Test
    fun `a whole career runs without leaving any value out of range`() {
        var squad = (1..4).map {
            player(dob = LocalDate.of(1996 + it, 3, 3), rating = 40 + it * 10, potential = 90)
                .copy(id = com.cricketcareer.engine.model.player.PlayerId("P$it"))
        }
        squad = clock.advance(
            squad,
            LocalDate.of(2020, 1, 1),
            LocalDate.of(2042, 1, 1),
            squad.associate { it.id to SeasonExposure(0.8, 0.7) },
            SimRandom.fromSeed(11),
        )
        squad.forEach { p ->
            Attribute.ALL.forEach {
                assertTrue(p.attributes[it] in Attributes.MIN..Attributes.MAX) { "${p.id} $it = ${p.attributes[it]}" }
            }
            assertTrue(p.state.fatigue in 0.0..1.0)
            assertTrue(p.state.sharpness in 0.0..1.0)
        }
    }
}
