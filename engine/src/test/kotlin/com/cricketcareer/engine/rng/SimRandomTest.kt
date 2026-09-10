package com.cricketcareer.engine.rng

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SimRandomTest {

    @Test
    fun `same seed produces the same sequence`() {
        val a = SimRandom.fromSeed(20250910L)
        val b = SimRandom.fromSeed(20250910L)
        repeat(10_000) { assertEquals(a.nextLong(), b.nextLong()) }
    }

    @Test
    fun `adjacent seeds produce unrelated sequences`() {
        // A test loop over seeds 1..N is the normal case, so weak seeding here
        // would silently correlate thousands of "independent" simulated matches.
        val first = List(64) { SimRandom.fromSeed(1L).nextLong() }
        val second = List(64) { SimRandom.fromSeed(2L).nextLong() }
        assertNotEquals(first, second)

        val matches = (0 until 64).count { SimRandom.fromSeed(1L).nextLong() == SimRandom.fromSeed(2L).nextLong() }
        assertEquals(0, matches)
    }

    @Test
    fun `nextDouble stays in range and is roughly uniform`() {
        val rng = SimRandom.fromSeed(7L)
        val buckets = IntArray(10)
        val n = 200_000
        repeat(n) {
            val v = rng.nextDouble()
            assertTrue(v >= 0.0 && v < 1.0, "nextDouble out of range: $v")
            buckets[(v * 10).toInt()]++
        }
        val expected = n / 10.0
        // 3.5 sigma on a binomial(n, 0.1) count.
        val tolerance = 3.5 * sqrt(n * 0.1 * 0.9)
        buckets.forEachIndexed { i, count ->
            assertTrue(
                abs(count - expected) < tolerance,
                "decile $i had $count, expected ~$expected (tolerance $tolerance)",
            )
        }
    }

    @Test
    fun `nextInt is flat across the whole range`() {
        val rng = SimRandom.fromSeed(11L)
        val counts = IntArray(7)
        val n = 140_000
        repeat(n) { counts[rng.nextInt(7)]++ }
        val expected = n / 7.0
        val tolerance = 4.0 * sqrt(n * (1.0 / 7.0) * (6.0 / 7.0))
        counts.forEachIndexed { i, count ->
            assertTrue(abs(count - expected) < tolerance, "value $i had $count, expected ~$expected")
        }
    }

    @Test
    fun `nextGaussian has unit mean and variance`() {
        val rng = SimRandom.fromSeed(13L)
        val n = 500_000
        var sum = 0.0
        var sumSquares = 0.0
        repeat(n) {
            val z = rng.nextGaussian()
            sum += z
            sumSquares += z * z
        }
        val mean = sum / n
        val variance = sumSquares / n - mean * mean
        assertTrue(abs(mean) < 0.01, "mean was $mean")
        assertTrue(abs(variance - 1.0) < 0.02, "variance was $variance")
    }

    @Test
    fun `nextGaussian consumes exactly two underlying draws`() {
        // Documented in SimRandom: the pipeline relies on predictable stream
        // consumption so that regression diffs stay legible.
        val gaussian = SimRandom.fromSeed(99L).also { it.nextGaussian() }
        val uniform = SimRandom.fromSeed(99L).also { it.nextDouble(); it.nextDouble() }
        assertEquals(uniform.nextLong(), gaussian.nextLong())
    }

    @Test
    fun `chance clamps and matches its probability`() {
        val rng = SimRandom.fromSeed(17L)
        assertTrue((1..1000).none { rng.chance(0.0) })
        assertTrue((1..1000).all { rng.chance(1.0) })
        assertTrue((1..1000).none { rng.chance(-0.5) })
        assertTrue((1..1000).all { rng.chance(1.5) })

        val n = 200_000
        val hits = (1..n).count { rng.chance(0.23) }
        val rate = hits.toDouble() / n
        assertTrue(abs(rate - 0.23) < 0.005, "observed rate $rate")
    }

    @Test
    fun `pickWeighted respects the weights`() {
        val rng = SimRandom.fromSeed(23L)
        val options = listOf("bouncer", "good length", "yorker")
        val weights = doubleArrayOf(1.0, 7.0, 2.0)
        val counts = IntArray(options.size)
        val n = 200_000
        repeat(n) { counts[options.indexOf(rng.pickWeighted(options, weights))]++ }

        val expectedShares = doubleArrayOf(0.1, 0.7, 0.2)
        options.indices.forEach { i ->
            val share = counts[i].toDouble() / n
            assertTrue(
                abs(share - expectedShares[i]) < 0.005,
                "${options[i]} came out at $share, expected ~${expectedShares[i]}",
            )
        }
    }

    @Test
    fun `pickWeighted never selects a zero weighted option`() {
        val rng = SimRandom.fromSeed(29L)
        val options = listOf("legal", "impossible")
        val weights = doubleArrayOf(1.0, 0.0)
        repeat(50_000) { assertEquals("legal", rng.pickWeighted(options, weights)) }
    }
}
