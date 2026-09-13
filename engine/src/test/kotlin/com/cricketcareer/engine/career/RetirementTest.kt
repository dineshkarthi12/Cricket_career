package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The end of a career.
 *
 * Not a hazard roll behind the scenes — that is `WorldAgeing`, for the two
 * thousand nine hundred cricketers nobody asks about. This is the last screen
 * of somebody's career and it owes him an answer, so every test here is as much
 * about the *sentence* as about the number.
 */
class RetirementTest {

    private val tuning = CareerTuning.DEFAULT
    private val config = tuning.retirement

    private fun player(rating: Int = 60): Player =
        Fixtures.averagePlayer("YOU").copy(attributes = Attributes.uniform(rating))

    private fun standardOf(rating: Int) =
        Selection.standardFor(player(rating), MatchFormat.LIST_A)

    private fun consider(
        rating: Int = 60,
        age: Int = 33,
        level: LadderLevel = LadderLevel.STATE_FIRST_CLASS,
        peak: Double = standardOf(60),
        idle: Int = 0,
        matches: Int = 60,
        damage: Int = 0,
        nowhere: Boolean = false,
    ) = Retirement.consider(
        player = player(rating),
        age = age,
        level = level,
        peakStandard = peak,
        seasonsWithoutCricket = idle,
        careerMatches = matches,
        accumulatedDamage = damage,
        nowhereLeftToPlay = nowhere,
        tuning = tuning,
    )

    // ---- nobody stops early ------------------------------------------------

    @Test
    fun `a young cricketer is not thinking about it`() {
        val young = consider(age = config.earliestAge - 1)
        assertEquals(0.0, young.likelihood, 1e-12)
        assertTrue(young.pressures.isEmpty())
        assertNull(young.headline) { "nobody should be asking him" }
        assertTrue(!Retirement.decide(young, SimRandom.fromSeed(1)))
    }

    @Test
    fun `everybody stops eventually`() {
        val ancient = consider(age = config.latestAge)
        assertEquals(1.0, ancient.likelihood, 1e-12)
        assertTrue(ancient.forced)
        assertTrue(Retirement.decide(ancient, SimRandom.fromSeed(1)))
    }

    // ---- the reasons -------------------------------------------------------

    @Test
    fun `a player nobody will have has had the decision taken out of his hands`() {
        // The one case where "he chose to retire" would be a lie.
        val done = consider(nowhere = true)
        assertTrue(done.forced)
        assertEquals("No side at any level will have you", done.headline)
        assertTrue(Retirement.decide(done, SimRandom.fromSeed(9)))
    }

    @Test
    fun `decline is measured against his own peak, not against the level`() {
        // How a cricketer experiences it: the problem is not that the bowling
        // got better, it is that the hands do not move like that any more.
        val faded = consider(rating = 40, peak = standardOf(80))
        val same = consider(rating = 80, peak = standardOf(80))

        assertTrue(faded.likelihood > same.likelihood) {
            "faded %.2f, undiminished %.2f".format(faded.likelihood, same.likelihood)
        }
        assertTrue(faded.pressures.any { it.reason.contains("not the player") || it.reason.contains("no longer up") })
    }

    @Test
    fun `a player below his level is told so, and one above it is not`() {
        val struggling = consider(rating = 30, level = LadderLevel.INTERNATIONAL, peak = standardOf(80))
        val comfortable = consider(rating = 80, level = LadderLevel.DISTRICT_CLUB, peak = standardOf(90))

        assertTrue(struggling.pressures.any { it.reason == "You are no longer up to this level" })
        assertTrue(comfortable.pressures.none { it.reason == "You are no longer up to this level" })
    }

    @Test
    fun `a player still doing the job is not told he has been found out`() {
        // A rung's standard is the standard of the *cricket*, not a bar every
        // player in it clears: an international side is 0.92 and most of it is
        // under that. Read that way, the model told a forty-two-year-old
        // averaging fifty-three that he was no longer up to it.
        val stillGood = consider(rating = 88, age = 40, level = LadderLevel.INTERNATIONAL, peak = standardOf(90))
        assertTrue(stillGood.pressures.none { it.reason == "You are no longer up to this level" }) {
            "told he was finished: ${stillGood.pressures.map { it.reason }}"
        }
    }

    @Test
    fun `seasons without cricket are the sharpest pressure of all`() {
        // This is what retires the player who is still good enough and cannot
        // get in, which is a real and particularly bitter way to finish.
        val playing = consider(idle = 0)
        val oneOut = consider(idle = 1)
        val twoOut = consider(idle = 2)

        assertTrue(oneOut.likelihood > playing.likelihood)
        assertTrue(twoOut.likelihood > oneOut.likelihood)
        assertEquals("You have not played in 2 seasons", twoOut.pressures.first().reason)
    }

    @Test
    fun `old injuries that did not mend push him towards it`() {
        assertTrue(consider(damage = 15).likelihood > consider(damage = 0).likelihood)
        assertTrue(consider(damage = 15).pressures.any { it.reason.contains("not going to mend") })
    }

    @Test
    fun `a fulfilled career makes it easier to walk away`() {
        // And the other half, which is the commoner one: a player still chasing
        // a first cap hangs on past the point of sense.
        val fulfilled = consider(matches = config.fulfilledAtMatches * 2)
        val unfulfilled = consider(matches = 2)

        assertTrue(fulfilled.likelihood > unfulfilled.likelihood)
        assertTrue(fulfilled.pressures.any { it.reason.contains("career you set out to have") })
    }

    @Test
    fun `no single pressure ends a career on its own at a plausible age`() {
        // Cricketers stop for two or three reasons at once, and a model where
        // one of them was sufficient would retire people for their birthday.
        val onlyAge = consider(age = 34, rating = 60, peak = standardOf(60), idle = 0, matches = 10, damage = 0)
        assertTrue(onlyAge.likelihood < 0.35) {
            "age alone at 34 gave %.2f".format(onlyAge.likelihood)
        }
    }

    @Test
    fun `several pressures together do`() {
        val finished = consider(age = 38, rating = 38, peak = standardOf(75), idle = 2, damage = 12)
        assertTrue(finished.likelihood > 0.8) { "%.2f".format(finished.likelihood) }
    }

    // ---- what a screen gets ------------------------------------------------

    @Test
    fun `the pressures are shares of the whole, strongest first`() {
        // So a screen can say which of them is doing the work rather than
        // printing five raw numbers nobody can compare.
        val prospect = consider(age = 37, rating = 42, peak = standardOf(72), idle = 1, damage = 8)

        assertTrue(prospect.pressures.isNotEmpty())
        assertEquals(1.0, prospect.pressures.sumOf { it.weight }, 1e-9)
        prospect.pressures.zipWithNext { a, b -> assertTrue(a.weight >= b.weight) }
        assertEquals(prospect.pressures.first().reason, prospect.headline)
    }

    @Test
    fun `a blank reason never reaches a screen`() {
        // The idle pressure has nothing to say when he played every week.
        assertTrue(consider(idle = 0).pressures.none { it.reason.isBlank() })
    }

    @Test
    fun `the same seed gives the same decision`() {
        val prospect = consider(age = 36, rating = 50, peak = standardOf(70))
        assertEquals(
            Retirement.decide(prospect, SimRandom.fromSeed(21)),
            Retirement.decide(prospect, SimRandom.fromSeed(21)),
        )
    }
}
