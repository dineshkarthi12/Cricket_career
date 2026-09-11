package com.cricketcareer.harness

import com.cricketcareer.engine.commentary.CommentaryGenerator
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.RecordingSink
import com.cricketcareer.engine.match.sim.InningsSimulator
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.MatchRandom

/**
 * Simulates one innings and prints it as commentary and a scorecard.
 *
 * Phase 2's presentation, and the quickest way to see whether the engine is
 * producing cricket rather than merely producing numbers in the right bands.
 */
object ScorecardReport {

    fun run(args: HarnessArgs) {
        val (batting, bowling) = Fixtures.averageTeams()
        val names = (batting + bowling).associate { it.id to it.name.scorecard }
        val commentary = CommentaryGenerator { id: PlayerId -> names[id] ?: id.value }
        val sink = RecordingSink()

        val state = InningsSimulator(
            format = MatchFormat.T20,
            battingSide = batting,
            bowlingSide = bowling,
            venue = Fixtures.AVERAGE_VENUE,
            pitch = Fixtures.AVERAGE_PITCH,
            weather = Weather.AVERAGE,
            level = LadderLevel.STATE_FIRST_CLASS,
            random = MatchRandom(args.seed),
            sink = sink,
        ).simulate()

        println("An innings, seed ${args.seed}")
        println("=".repeat(78))
        println()

        var over = -1
        sink.events().forEach { event ->
            if (event.id.over != over) {
                over = event.id.over
                println()
                println("-- over ${over + 1}, ${names[event.bowler]} --")
            }
            println("  " + commentary.describe(event))
        }

        val card = state.snapshot()
        println()
        println("=".repeat(78))
        println("Batting" + " ".repeat(28) + "R     B    4s    6s     SR")
        println("-".repeat(78))
        card.batting.forEach { batter ->
            val how = batter.dismissal?.let { dismissal ->
                dismissal.mode.displayName + " " + (dismissal.bowler?.let { names[it] } ?: "")
            } ?: "not out"
            println(
                "%-22s %-12s %5s %5d %5d %5d %6.1f".format(
                    names[batter.player], how.take(12), batter.display,
                    batter.balls, batter.fours, batter.sixes, batter.strikeRate,
                ),
            )
        }
        println("-".repeat(78))
        println(
            "Extras  (b ${card.byes}, lb ${card.legByes}, w ${card.wides}, nb ${card.noBalls})" +
                " ".repeat(20) + "${card.extras}",
        )
        println("TOTAL" + " ".repeat(42) + card.display)
        println()
        println("Bowling" + " ".repeat(24) + "O     M     R     W   Econ")
        println("-".repeat(78))
        card.bowling.forEach { bowler ->
            println(
                "%-30s %5s %5d %5d %5d %6.2f".format(
                    names[bowler.player], bowler.overs, bowler.maidens,
                    bowler.runsConceded, bowler.wickets, bowler.economy,
                ),
            )
        }
        println()
        println("Fall of wickets: " + card.fallOfWickets.joinToString(", ") { it.display })
        println()
        println("Books reconcile: ${card.reconciles()}")
    }
}
