package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.WorldTuning
import com.cricketcareer.engine.fixtures.CalibrationRun
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerState
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.math.sqrt

class WorldSimTest {

    private val tuning = WorldTuning()

    private fun rng(seed: Long = 17) = SimRandom.fromSeed(seed)

    private fun batter(rating: Int = 50, form: Double = 0.0): Player =
        Fixtures.averagePlayer("B").copy(
            attributes = Attributes.uniform(rating),
            state = PlayerState.FRESH.copy(form = form),
        )

    private fun season(p: Player, format: MatchFormat, n: Int, opposition: Double = 0.5, seed: Long = 17) =
        WorldSim.season(p, format, n, opposition, rng(seed), tuning)

    // ---- The claim this module exists to keep --------------------------

    /**
     * If a batter averages 38 in the user's competition and 47 in a reduced
     * one, every leading-run-scorer table in the game is a lie and the
     * selection model built on those tables picks the wrong people.
     *
     * Tolerance is stated as a multiple of the sampling standard error, per
     * CLAUDE.md §6, so raising the sample size does not silently change how
     * strict this is.
     */
    @Test
    fun `a reduced innings averages what the real engine averages`() {
        data class Case(val name: String, val format: MatchFormat, val engineInnings: Int, val reducedInnings: Int)
        val cases = listOf(
            Case("T20", Fixtures.T20, 600, 40_000),
            Case("List A", Fixtures.LIST_A, 400, 40_000),
            Case("four-day", MatchFormat.FOUR_DAY, 200, 40_000),
        )
        for (case in cases) {
            val stats = CalibrationRun.run(case.format, case.engineInnings)
            val runsOffBat = stats.runs - stats.wides - stats.noBalls - stats.byes - stats.legByes
            val tier1 = runsOffBat.toDouble() / stats.wickets

            val reduced = season(batter(), case.format, case.reducedInnings)
            val tier2 = WorldSim.average(reduced)

            // An exponential's mean has standard error mean/sqrt(n); the ratio
            // of runs to dismissals inflates that a little, so use the coarser
            // of the two sample sizes.
            val standardError = tier1 / sqrt(minOf(stats.wickets, reduced.count { it.out }).toDouble())
            assertTrue(abs(tier2 - tier1) <= 4.0 * standardError) {
                "${case.name}: engine averaged %.2f, reduced averaged %.2f, 4 s.e. = %.2f"
                    .format(tier1, tier2, 4.0 * standardError)
            }
        }
    }

    @Test
    fun `a reduced innings is struck at about the rate the real engine strikes at`() {
        val stats = CalibrationRun.run(Fixtures.T20, 600)
        val runsOffBat = stats.runs - stats.wides - stats.noBalls - stats.byes - stats.legByes
        val tier1 = runsOffBat.toDouble() / stats.legalBalls * 100.0

        val reduced = season(batter(), Fixtures.T20, 40_000)
        val tier2 = reduced.sumOf { it.runs }.toDouble() / reduced.sumOf { it.ballsFaced } * 100.0

        // Strike rate is a ratio estimator, so its standard error comes from
        // the innings-level spread of (runs - R x balls), not from the ball
        // count - balls are heavily correlated inside one innings. The engine
        // sample's spread is not exposed innings by innings, so it is taken to
        // have the same shape as the reduced one's; it is the smaller sample by
        // two orders of magnitude and dominates the error either way.
        val reducedError = strikeRateStandardError(reduced.map { it.runs }, reduced.map { it.ballsFaced })
        val engineError = reducedError * sqrt(reduced.size.toDouble() / stats.wickets)
        val tolerance = 4.0 * sqrt(reducedError * reducedError + engineError * engineError)

        assertTrue(abs(tier2 - tier1) <= tolerance) {
            "engine SR %.1f, reduced SR %.1f, 4 s.e. = %.1f".format(tier1, tier2, tolerance)
        }
    }

    /** Standard error of an aggregate strike rate, by the delta method. */
    private fun strikeRateStandardError(runs: List<Int>, balls: List<Int>): Double {
        val n = runs.size
        val ratio = runs.sum().toDouble() / balls.sum()
        val meanBalls = balls.sum().toDouble() / n
        val variance = runs.indices.sumOf {
            val residual = runs[it] - ratio * balls[it]
            residual * residual
        } / (n - 1)
        return 100.0 * sqrt(variance / n) / meanBalls
    }

    // ---- Tempo -----------------------------------------------------------

    @Test
    fun `two batters do not strike at the same rate`() {
        // Until a tempo term existed, every reduced innings in the world came
        // back at exactly the format's mean strike rate. A model that cannot
        // tell a thirty off ninety from a thirty off twenty cannot produce a
        // chase, and every career in it looks the same.
        val rates = season(batter(), Fixtures.LIST_A, 4_000)
            .filter { it.runs >= 10 }
            .map { it.runs * 100.0 / it.ballsFaced }

        val mean = rates.average()
        val spread = sqrt(rates.sumOf { (it - mean) * (it - mean) } / (rates.size - 1))
        assertTrue(spread / mean > 0.15) { "strike rates vary by only %.1f%%".format(100.0 * spread / mean) }
    }

    @Test
    fun `a range hitter scores faster than a blocker`() {
        fun shaped(scoring: Int, occupying: Int): Player = batter().let {
            it.copy(
                attributes = it.attributes.withAll(
                    mapOf(
                        Attribute.RANGE_HITTING to scoring,
                        Attribute.POWER to scoring,
                        Attribute.STRIKE_ROTATION to scoring,
                        Attribute.PATIENCE to occupying,
                        Attribute.CONCENTRATION to occupying,
                    ),
                ),
            )
        }
        val hitter = shaped(scoring = 90, occupying = 20)
        val blocker = shaped(scoring = 20, occupying = 90)

        fun strikeRate(p: Player) = season(p, Fixtures.LIST_A, 8_000)
            .let { it.sumOf { i -> i.runs }.toDouble() / it.sumOf { i -> i.ballsFaced } * 100.0 }

        assertTrue(strikeRate(hitter) > strikeRate(blocker) * 1.25) {
            "hitter %.1f, blocker %.1f".format(strikeRate(hitter), strikeRate(blocker))
        }
    }

    // ---- Shape ----------------------------------------------------------

    @Test
    fun `the score distribution has ducks and hundreds in it`() {
        // A normal draw would give a world where nobody ever failed and nobody
        // ever made a hundred, and every career would look identical.
        val reduced = season(batter(), Fixtures.LIST_A, 20_000)
        val scores = reduced.map { it.runs }
        val ducks = scores.count { it == 0 }.toDouble() / scores.size
        val hundreds = scores.count { it >= 100 }.toDouble() / scores.size
        assertTrue(ducks in 0.01..0.12) { "duck rate $ducks" }
        assertTrue(hundreds in 0.005..0.08) { "hundred rate $hundreds" }
        // Mean well above median is the signature of the right distribution.
        assertTrue(scores.average() > scores.sorted()[scores.size / 2]) {
            "mean ${scores.average()} should exceed median ${scores.sorted()[scores.size / 2]}"
        }
    }

    @Test
    fun `a better batter averages more`() {
        val poor = WorldSim.average(season(batter(rating = 25), Fixtures.T20, 12_000))
        val good = WorldSim.average(season(batter(rating = 85), Fixtures.T20, 12_000))
        assertTrue(good > poor * 1.8) { "good $good vs poor $poor" }
    }

    @Test
    fun `a better attack costs runs`() {
        val easy = WorldSim.average(season(batter(), Fixtures.T20, 12_000, opposition = 0.15))
        val hard = WorldSim.average(season(batter(), Fixtures.T20, 12_000, opposition = 0.9))
        assertTrue(easy > hard) { "easy $easy vs hard $hard" }
    }

    @Test
    fun `form moves a reduced innings the way it moves a real one`() {
        val flat = WorldSim.average(season(batter(form = 0.0), Fixtures.T20, 15_000))
        val purple = WorldSim.average(season(batter(form = 0.95), Fixtures.T20, 15_000))
        assertTrue(purple > flat) { "in form $purple vs flat $flat" }
    }

    @Test
    fun `a multi-day innings is longer and slower than a T20 one`() {
        val t20 = season(batter(), Fixtures.T20, 8000)
        val multi = season(batter(), MatchFormat.FOUR_DAY, 8000)
        fun strikeRate(l: List<ReducedInnings>) =
            l.sumOf { it.runs }.toDouble() / l.sumOf { it.ballsFaced }
        assertTrue(strikeRate(t20) > strikeRate(multi)) {
            "T20 ${strikeRate(t20)} should outpace four-day ${strikeRate(multi)}"
        }
    }

    // ---- Bowling --------------------------------------------------------

    @Test
    fun `a better bowler strikes sooner and costs less`() {
        val poor = Fixtures.averagePlayer("P", bowlingStyle = com.cricketcareer.engine.model.player.BowlingStyle.RIGHT_FAST)
            .copy(attributes = Attributes.uniform(25))
        val good = poor.copy(attributes = Attributes.uniform(88))
        fun spell(p: Player) = WorldSim.spell(p, Fixtures.T20, ballsBowled = 24_000, battingStandard = 0.5, random = rng(), tuning = tuning)
        val a = spell(poor)
        val b = spell(good)
        assertTrue(b.wickets > a.wickets) { "good took ${b.wickets}, poor took ${a.wickets}" }
        assertTrue(b.runsConceded < a.runsConceded) { "good gave ${b.runsConceded}, poor gave ${a.runsConceded}" }
    }

    @Test
    fun `an empty spell is an empty spell`() {
        val spell = WorldSim.spell(batter(), Fixtures.T20, 0, 0.5, rng(), tuning)
        assertEquals(ReducedSpell(0, 0, 0), spell)
    }

    // ---- Contract -------------------------------------------------------

    @Test
    fun `one innings costs exactly one draw, however extreme the score`() {
        // Inverse-transform, not rejection. A rejection loop would consume a
        // variable number of draws and make the whole world's subsequent
        // randomness depend on one batter's score.
        val a = SimRandom.fromSeed(99)
        val b = SimRandom.fromSeed(99)
        repeat(500) { WorldSim.drawGeometricish(mean = 30.0, random = a) }
        repeat(500) { WorldSim.drawGeometricish(mean = 3000.0, random = b) }
        assertEquals(a.nextLong(), b.nextLong()) {
            "the stream diverged, so the draw count depends on the value drawn"
        }
    }

    @Test
    fun `a reduced season is a pure function of its seed`() {
        val p = batter()
        assertEquals(
            season(p, Fixtures.LIST_A, 200, seed = 4242),
            season(p, Fixtures.LIST_A, 200, seed = 4242),
        )
    }

    @Test
    fun `nonsense inputs are rejected rather than absorbed`() {
        assertThrows<IllegalArgumentException> { WorldSim.innings(batter(), Fixtures.T20, 1.6, rng(), tuning) }
        assertThrows<IllegalArgumentException> { WorldSim.season(batter(), Fixtures.T20, -1, 0.5, rng(), tuning) }
        assertThrows<IllegalArgumentException> { WorldSim.spell(batter(), Fixtures.T20, -1, 0.5, rng(), tuning) }
        assertThrows<IllegalArgumentException> { WorldSim.drawGeometricish(0.0, rng()) }
    }

    @Test
    fun `an unbeaten season still returns a finite average`() {
        val neverOut = listOf(ReducedInnings(40, 30, out = false), ReducedInnings(12, 9, out = false))
        assertEquals(52.0, WorldSim.average(neverOut), 1e-9)
    }
}
