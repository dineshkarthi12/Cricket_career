package com.cricketcareer.engine.career

import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CareerRandomTest {

    @Test
    fun `a stream is created once and keeps advancing`() {
        val career = CareerRandom(seed = 4242)
        assertSame(career.stream(CareerStreams.INJURY), career.stream(CareerStreams.INJURY))
    }

    @Test
    fun `streams are independent of the order they are first used`() {
        val a = CareerRandom(99).let { listOf(it.stream(CareerStreams.FORM).nextLong(), it.stream(CareerStreams.INJURY).nextLong()) }
        val b = CareerRandom(99).let { listOf(it.stream(CareerStreams.INJURY).nextLong(), it.stream(CareerStreams.FORM).nextLong()) }
        // Drawn in the opposite order, so compare like with like.
        assertEquals(a[0], b[1])
        assertEquals(a[1], b[0])
    }

    @Test
    fun `different streams from the same seed do not coincide`() {
        val career = CareerRandom(7)
        val form = (1..8).map { career.stream(CareerStreams.FORM).nextLong() }
        val injury = (1..8).map { career.stream(CareerStreams.INJURY).nextLong() }
        assertNotEquals(form, injury)
    }

    @Test
    fun `a match seed is derived, not drawn`() {
        // Derived means a match replays identically whether it is reached by
        // simulating the career forward or opened on its own from a bug report.
        val career = CareerRandom(1234)
        val first = career.matchSeed("2026-06-10-IND-AUS-1")
        repeat(50) { career.stream(CareerStreams.SELECTION).nextLong() }
        assertEquals(first, career.matchSeed("2026-06-10-IND-AUS-1"))
        assertEquals(SimRandom.deriveSeed(1234, "match:2026-06-10-IND-AUS-1"), first)
    }

    @Test
    fun `different matches in one career get different seeds`() {
        val career = CareerRandom(1234)
        assertNotEquals(career.matchSeed("m1"), career.matchSeed("m2"))
    }

    @Test
    fun `the same career seed replays identically`() {
        fun run() = CareerRandom(555).let { c ->
            CareerStreams.let { _ -> listOf(CareerStreams.AGEING, CareerStreams.FORM, CareerStreams.INJURY) }
                .flatMap { name -> (1..5).map { c.stream(name).nextDouble() } }
        }
        assertEquals(run(), run())
    }
}
