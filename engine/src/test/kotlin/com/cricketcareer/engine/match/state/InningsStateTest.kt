package com.cricketcareer.engine.match.state

import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.MatchFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The Laws of scoring. Every one of these is a rule that is easy to get subtly
 * wrong and impossible to catch later: a mis-scored leg bye corrupts every
 * career bowling average in the save and no distribution test would notice.
 */
class InningsStateTest {

    private val batters = (1..11).map { PlayerId("bat$it") }
    private val bowlerA = PlayerId("bowlA")
    private val bowlerB = PlayerId("bowlB")

    /** Six bowlers, so a full 20-over innings can be bowled inside the four-over cap. */
    private val attack = (1..6).map { PlayerId("bowl$it") }

    private fun innings(format: MatchFormat = MatchFormat.T20, target: Int? = null) =
        InningsState(format, "HOME", "AWAY", batters, target = target).also { it.setBowler(bowlerA) }

    /**
     * Bowl a whole over of the same outcome, then rotate to the next bowler in
     * [attack] who is legally allowed to bowl the next one.
     */
    private fun InningsState.bowlOver(outcome: DeliveryOutcome) {
        repeat(MatchFormat.BALLS_PER_OVER) { if (!isComplete) record(outcome) }
        if (!isComplete) setBowler(attack.first { mayBowlNextOver(it) })
    }

    @Nested
    inner class Basics {
        @Test
        fun `a dot ball advances the over and nothing else`() {
            val i = innings()
            i.record(DeliveryOutcome.DOT)
            assertEquals(0, i.runs)
            assertEquals(1, i.legalBalls)
            assertEquals("0.1", i.oversBowled)
            assertEquals(batters[0], i.striker)
        }

        @Test
        fun `runs off the bat go to the striker and to the bowler`() {
            val i = innings()
            i.record(DeliveryOutcome.offBat(4))
            assertEquals(4, i.runs)
            assertEquals(4, i.batterCard(batters[0])!!.runs)
            assertEquals(1, i.batterCard(batters[0])!!.fours)
            assertEquals(4, i.bowlerCard(bowlerA)!!.runsConceded)
        }

        @Test
        fun `sixes are counted separately from fours`() {
            val i = innings()
            i.record(DeliveryOutcome.offBat(6))
            val card = i.batterCard(batters[0])!!
            assertEquals(1, card.sixes)
            assertEquals(0, card.fours)
        }

        @Test
        fun `an odd number of runs changes the strike`() {
            val i = innings()
            i.record(DeliveryOutcome.offBat(1))
            assertEquals(batters[1], i.striker)
            assertEquals(batters[0], i.nonStriker)
        }

        @Test
        fun `an even number of runs does not change the strike`() {
            val i = innings()
            i.record(DeliveryOutcome.offBat(2))
            assertEquals(batters[0], i.striker)
        }

        @Test
        fun `the batters change ends at the end of an over`() {
            val i = innings()
            repeat(6) { i.record(DeliveryOutcome.DOT) }
            assertEquals("1.0", i.oversBowled)
            assertEquals(batters[1], i.striker)
        }

        @Test
        fun `a single off the last ball of an over cancels out with the change of ends`() {
            // He keeps the strike: crosses for the single, then changes ends.
            val i = innings()
            repeat(5) { i.record(DeliveryOutcome.DOT) }
            i.record(DeliveryOutcome.offBat(1))
            assertEquals(batters[0], i.striker)
        }
    }

    @Nested
    inner class Extras {
        @Test
        fun `a wide is not a legal ball and not a ball faced`() {
            val i = innings()
            i.record(DeliveryOutcome.wide())
            assertEquals(1, i.runs)
            assertEquals(0, i.legalBalls)
            assertEquals(0, i.batterCard(batters[0])!!.balls)
            assertEquals(1, i.bowlerCard(bowlerA)!!.wides)
            assertEquals(1, i.bowlerCard(bowlerA)!!.runsConceded)
        }

        @Test
        fun `a no ball is a ball faced but not a legal ball`() {
            // The batter had to play it, so it counts against him; the over does not advance.
            val i = innings()
            i.record(DeliveryOutcome.noBall(runs = 4))
            assertEquals(5, i.runs)
            assertEquals(0, i.legalBalls)
            assertEquals(1, i.batterCard(batters[0])!!.balls)
            assertEquals(4, i.batterCard(batters[0])!!.runs)
            assertEquals(5, i.bowlerCard(bowlerA)!!.runsConceded)
        }

        @Test
        fun `byes are charged to the team but not to the bowler`() {
            val i = innings()
            i.record(DeliveryOutcome(byes = 4))
            assertEquals(4, i.runs)
            assertEquals(4, i.byes)
            assertEquals(0, i.bowlerCard(bowlerA)!!.runsConceded)
            assertEquals(1, i.legalBalls)
            assertEquals(1, i.batterCard(batters[0])!!.balls)
            assertEquals(0, i.batterCard(batters[0])!!.runs)
        }

        @Test
        fun `leg byes are charged to the team but not to the bowler`() {
            val i = innings()
            i.record(DeliveryOutcome(legByes = 2))
            assertEquals(2, i.runs)
            assertEquals(2, i.legByes)
            assertEquals(0, i.bowlerCard(bowlerA)!!.runsConceded)
        }

        @Test
        fun `runs run on a wide are further wides`() {
            val i = innings()
            i.record(DeliveryOutcome.wide(additionalRuns = 4))
            assertEquals(5, i.runs)
            assertEquals(5, i.wides)
            assertEquals(0, i.batterCard(batters[0])!!.runs)
        }

        @Test
        fun `four byes off a wide still change the strike an odd number of times`() {
            // 5 wides: one penalty plus four run, so an odd number were run.
            val i = innings()
            i.record(DeliveryOutcome.wide(additionalRuns = 3))
            assertEquals(batters[1], i.striker)
        }

        @Test
        fun `impossible combinations are rejected`() {
            assertThrows(IllegalArgumentException::class.java) { DeliveryOutcome(wides = 1, runsOffBat = 2) }
            assertThrows(IllegalArgumentException::class.java) { DeliveryOutcome(wides = 1, noBall = true) }
            assertThrows(IllegalArgumentException::class.java) { DeliveryOutcome(byes = 1, legByes = 1) }
            assertThrows(IllegalArgumentException::class.java) { DeliveryOutcome(runsOffBat = 1, legByes = 1) }
        }
    }

    @Nested
    inner class Maidens {
        @Test
        fun `six dot balls is a maiden`() {
            val i = innings()
            repeat(6) { i.record(DeliveryOutcome.DOT) }
            assertEquals(1, i.bowlerCard(bowlerA)!!.maidens)
        }

        @Test
        fun `leg byes do not spoil a maiden`() {
            // The bowler was not charged, so the over is still a maiden.
            val i = innings()
            repeat(5) { i.record(DeliveryOutcome.DOT) }
            i.record(DeliveryOutcome(legByes = 2))
            assertEquals(1, i.bowlerCard(bowlerA)!!.maidens)
        }

        @Test
        fun `a wide spoils a maiden`() {
            val i = innings()
            i.record(DeliveryOutcome.wide())
            repeat(6) { i.record(DeliveryOutcome.DOT) }
            assertEquals(0, i.bowlerCard(bowlerA)!!.maidens)
        }
    }

    @Nested
    inner class Wickets {
        private fun bowled(batter: PlayerId) = DeliveryOutcome(
            dismissal = Dismissal(DismissalMode.BOWLED, batter, bowlerA),
        )

        @Test
        fun `a wicket brings in the next batter on strike`() {
            val i = innings()
            i.record(bowled(batters[0]))
            assertEquals(1, i.wickets)
            assertEquals(batters[2], i.striker)
            assertEquals(batters[1], i.nonStriker)
            assertEquals(3, i.batterCard(batters[2])!!.position)
        }

        @Test
        fun `a catch taken after the batters crossed leaves the survivor on strike`() {
            val i = innings()
            i.record(
                DeliveryOutcome(
                    dismissal = Dismissal(DismissalMode.CAUGHT, batters[0], bowlerA, fielder = batters[5]),
                    battersCrossed = true,
                ),
            )
            assertEquals(batters[1], i.striker)
            assertEquals(batters[2], i.nonStriker)
        }

        @Test
        fun `a run out is not credited to the bowler`() {
            val i = innings()
            i.record(
                DeliveryOutcome(
                    runsOffBat = 1,
                    dismissal = Dismissal(DismissalMode.RUN_OUT, batters[1], bowler = null, fielder = batters[6]),
                ),
            )
            assertEquals(1, i.wickets)
            assertEquals(0, i.bowlerCard(bowlerA)!!.wickets)
            assertEquals(1, i.bowlerCard(bowlerA)!!.runsConceded)
        }

        @Test
        fun `a run out can take the non-striker`() {
            val i = innings()
            i.record(
                DeliveryOutcome(
                    dismissal = Dismissal(DismissalMode.RUN_OUT, batters[1], bowler = null, fielder = batters[6]),
                ),
            )
            assertNotNull(i.batterCard(batters[1])!!.dismissal)
            assertNull(i.batterCard(batters[0])!!.dismissal)
            assertEquals(batters[0], i.striker)
            assertEquals(batters[2], i.nonStriker)
        }

        @Test
        fun `a bowler cannot be credited with a run out`() {
            assertThrows(IllegalArgumentException::class.java) {
                Dismissal(DismissalMode.CAUGHT, batters[0], bowlerA, fielder = null)
            }
        }

        @Test
        fun `ten wickets ends the innings`() {
            val i = innings()
            var out = 0
            while (!i.isComplete) {
                i.record(bowled(i.striker))
                out++
                if (i.atEndOfOver && !i.isComplete) i.setBowler(attack.first { i.mayBowlNextOver(it) })
            }
            assertEquals(10, out)
            assertTrue(i.allOut)
            assertEquals(10, i.wickets)
        }

        @Test
        fun `an innings ends when only one batter is left even below ten wickets`() {
            val two = InningsState(MatchFormat.T20, "HOME", "AWAY", batters.take(2))
            two.setBowler(bowlerA)
            two.record(DeliveryOutcome(dismissal = Dismissal(DismissalMode.BOWLED, two.striker, bowlerA)))
            assertTrue(two.allOut)
            assertEquals(1, two.wickets)
        }
    }

    @Nested
    inner class InningsEnd {
        @Test
        fun `an innings ends when the overs run out`() {
            val i = InningsState(MatchFormat.T20, "HOME", "AWAY", batters)
            i.setBowler(attack[0])
            repeat(20) { i.bowlOver(DeliveryOutcome.DOT) }
            assertTrue(i.oversExhausted)
            assertTrue(i.isComplete)
            assertEquals(120, i.legalBalls)
        }

        @Test
        fun `a chase ends the moment the target is passed`() {
            val i = innings(target = 5)
            i.record(DeliveryOutcome.offBat(4))
            assertFalse(i.isComplete)
            i.record(DeliveryOutcome.offBat(1))
            assertTrue(i.chaseComplete)
            assertTrue(i.isComplete)
        }

        @Test
        fun `runsRequired counts down and never goes negative`() {
            val i = innings(target = 10)
            assertEquals(10, i.runsRequired)
            i.record(DeliveryOutcome.offBat(4))
            assertEquals(6, i.runsRequired)
            i.record(DeliveryOutcome.offBat(6))
            assertEquals(0, i.runsRequired)
        }

        @Test
        fun `a declaration closes the innings with the stand unbroken`() {
            val i = InningsState(MatchFormat.FOUR_DAY, "HOME", "AWAY", batters)
            i.setBowler(bowlerA)
            i.record(DeliveryOutcome.offBat(4))
            i.declare()
            assertTrue(i.declared)
            assertTrue(i.isComplete)
            assertTrue(i.snapshot().partnerships.single().unbroken)
        }

        @Test
        fun `recording after the innings is complete is refused`() {
            val i = innings(target = 1)
            i.record(DeliveryOutcome.offBat(1))
            assertThrows(IllegalStateException::class.java) { i.record(DeliveryOutcome.DOT) }
        }
    }

    @Nested
    inner class BowlingRules {
        @Test
        fun `nobody bowls consecutive overs`() {
            val i = innings()
            repeat(6) { i.record(DeliveryOutcome.DOT) }
            assertThrows(IllegalArgumentException::class.java) { i.setBowler(bowlerA) }
        }

        @Test
        fun `the per-bowler over limit is enforced`() {
            // T20 caps a bowler at four overs; bowl two of them out to the cap.
            val i = InningsState(MatchFormat.T20, "HOME", "AWAY", batters)
            i.setBowler(bowlerA)
            repeat(8) { over ->
                if (over > 0) i.setBowler(if (i.bowler == bowlerA) bowlerB else bowlerA)
                repeat(MatchFormat.BALLS_PER_OVER) { i.record(DeliveryOutcome.DOT) }
            }
            assertEquals(4, i.oversBowledBy(bowlerA))
            assertFalse(i.mayBowlNextOver(bowlerA))
        }

        @Test
        fun `multi-day cricket has no over limit`() {
            val i = InningsState(MatchFormat.TEST, "HOME", "AWAY", batters)
            i.setBowler(bowlerA)
            repeat(6) { i.record(DeliveryOutcome.DOT) }
            i.setBowler(bowlerB)
            repeat(6) { i.record(DeliveryOutcome.DOT) }
            assertTrue(i.mayBowlNextOver(bowlerA))
        }

        @Test
        fun `a ball cannot be bowled without a bowler`() {
            val i = InningsState(MatchFormat.T20, "HOME", "AWAY", batters)
            assertThrows(IllegalStateException::class.java) { i.record(DeliveryOutcome.DOT) }
        }
    }

    @Nested
    inner class Reconciliation {
        @Test
        fun `a mixed innings balances the books`() {
            val i = innings()
            i.record(DeliveryOutcome.offBat(4))
            i.record(DeliveryOutcome.wide(2))
            i.record(DeliveryOutcome(legByes = 1))
            i.record(DeliveryOutcome.noBall(2))
            i.record(DeliveryOutcome(byes = 4))
            i.record(DeliveryOutcome.offBat(1))
            i.record(DeliveryOutcome.offBat(3))
            i.record(DeliveryOutcome(dismissal = Dismissal(DismissalMode.BOWLED, i.striker, bowlerA)))

            val card = i.snapshot()
            assertTrue(card.reconciles(), "books did not balance: $card")
            // 4 off the bat, 3 wides (1 penalty + 2 run), 1 leg bye,
            // 3 off the no-ball (1 penalty + 2 off the bat), 4 byes, 1, 3 = 19.
            assertEquals(19, i.runs)
            assertEquals(3, i.wides)
            assertEquals(1, i.noBalls)
            assertEquals(4, i.byes)
            assertEquals(1, i.legByes)
        }

        @Test
        fun `partnerships account for every run including extras`() {
            val i = innings()
            i.record(DeliveryOutcome.offBat(4))
            i.record(DeliveryOutcome.wide())
            i.record(DeliveryOutcome(dismissal = Dismissal(DismissalMode.BOWLED, i.striker, bowlerA)))
            i.record(DeliveryOutcome.offBat(2))

            val partnerships = i.snapshot().partnerships
            assertEquals(2, partnerships.size)
            assertEquals(5, partnerships[0].runs)
            assertEquals(2, partnerships[1].runs)
            assertEquals(i.runs, partnerships.sumOf { it.runs })
        }

        @Test
        fun `fall of wickets records the score at each wicket`() {
            val i = innings()
            i.record(DeliveryOutcome.offBat(4))
            i.record(DeliveryOutcome(dismissal = Dismissal(DismissalMode.BOWLED, i.striker, bowlerA)))
            val fow = i.snapshot().fallOfWickets.single()
            assertEquals(1, fow.wicketNumber)
            assertEquals(4, fow.runs)
            assertEquals(batters[0], fow.batterOut)
        }
    }
}
