package com.cricketcareer.harness

import com.cricketcareer.engine.commentary.CommentaryGenerator
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.RecordingSink
import com.cricketcareer.engine.match.sim.InningsSimulator
import com.cricketcareer.engine.match.state.InningsState
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.MatchRandom
import java.io.File

/**
 * Dumps one simulated innings as JSON, for the UI prototype to render.
 *
 * Everything a match screen needs comes out of the event stream and nowhere
 * else — the wagon wheel is the real shot azimuths, the pitch map is the real
 * pitch points, the beehive is the real line and height at the stumps. That is
 * the point of the event model (docs/ARCHITECTURE.md §3): a chart never has to
 * invent data, and if the engine says a ball did something the chart says the
 * same thing.
 */
object InningsExport {

    fun run(args: HarnessArgs, outputPath: String) {
        val (batting, bowling) = Fixtures.averageTeams()
        // The fixture players are called "Average PlayerHOME-1", which is right
        // for a calibration control and useless on a scorecard. Names are drawn
        // from the seed pools purely for display; the players themselves are
        // still the calibration fixtures.
        val nameRng = com.cricketcareer.engine.rng.SimRandom.fromSeed(args.seed)
        val names = (batting + bowling).associate {
            it.id to com.cricketcareer.engine.generator.NamePools.FALLBACK.draw(nameRng, "MH").scorecard
        }
        val commentary = CommentaryGenerator { id: PlayerId -> names[id] ?: id.value }
        val sink = RecordingSink()

        val format = when (args.format) {
            "LIST_A" -> MatchFormat.LIST_A
            "TEST" -> MatchFormat.TEST
            "FIRST_CLASS" -> MatchFormat.FOUR_DAY
            else -> MatchFormat.T20
        }

        val state = InningsSimulator(
            format = format,
            battingSide = batting,
            bowlingSide = bowling,
            venue = Fixtures.AVERAGE_VENUE,
            pitch = Fixtures.AVERAGE_PITCH,
            weather = Weather.AVERAGE,
            level = LadderLevel.FRANCHISE_T20,
            random = MatchRandom(args.seed),
            sink = sink,
        ).simulate()

        val json = buildString {
            append("{\n")
            append("\"format\":").append(quote(format.displayName)).append(",\n")
            append("\"seed\":").append(args.seed).append(",\n")
            append("\"balls\":[\n")
            sink.events().forEachIndexed { index, event ->
                if (index > 0) append(",\n")
                val outcome = event.outcome
                append("{")
                append("\"o\":").append(event.id.over).append(",")
                append("\"b\":").append(event.id.ballInOver).append(",")
                append("\"bowler\":").append(quote(names[event.bowler] ?: "")).append(",")
                append("\"striker\":").append(quote(names[event.striker] ?: "")).append(",")
                append("\"text\":").append(quote(commentary.describe(event))).append(",")
                append("\"runs\":").append(outcome.totalRuns).append(",")
                append("\"bat\":").append(outcome.runsOffBat).append(",")
                append("\"wicket\":").append(outcome.dismissal != null).append(",")
                append("\"mode\":").append(quote(outcome.dismissal?.mode?.displayName ?: "")).append(",")
                append("\"score\":").append(event.scoreAfter).append(",")
                append("\"wkts\":").append(event.wicketsAfter).append(",")
                append("\"legal\":").append(outcome.isLegalBall).append(",")
                // Pitch map: where it landed, and the line as it passed the stumps.
                append("\"pitchY\":").append(round(event.delivered.pitchPointMetres)).append(",")
                append("\"lineX\":").append(round(event.delivered.lineAtStumpsMetres)).append(",")
                append("\"heightZ\":").append(round(event.delivered.heightAtStumpsMetres)).append(",")
                append("\"kph\":").append(round(event.delivered.paceKph)).append(",")
                append("\"len\":").append(quote(event.delivered.lengthBand.displayName)).append(",")
                append("\"shot\":").append(quote(event.shot.shot.displayName)).append(",")
                append("\"contact\":").append(quote(event.contact.point.name)).append(",")
                append("\"press\":").append(round(event.pressure))
                // Wagon wheel: the real exit vector.
                event.trajectory?.let { t ->
                    append(",\"az\":").append(round(t.azimuthDegrees))
                    append(",\"dist\":").append(round(if (t.isAerial) t.carryMetres else 0.0))
                    append(",\"speed\":").append(round(t.exitSpeedMetresPerSecond))
                }
                append("}")
            }
            append("\n],\n")
            append(scorecardJson(state, names))
            append("}\n")
        }

        File(outputPath).writeText(json)
        println("Wrote ${sink.size} balls to $outputPath")
        println("Final score: ${state.snapshot().display}")
    }

    private fun scorecardJson(state: InningsState, names: Map<PlayerId, String>): String {
        val card = state.snapshot()
        return buildString {
            append("\"batting\":[")
            card.batting.forEachIndexed { i, b ->
                if (i > 0) append(",")
                append("{\"name\":").append(quote(names[b.player] ?: ""))
                append(",\"how\":").append(
                    quote(
                        b.dismissal?.let { d ->
                            d.mode.displayName + (d.fielder?.let { " " + (names[it] ?: "") } ?: "") +
                                (d.bowler?.let { " b " + (names[it] ?: "") } ?: "")
                        } ?: "not out",
                    ),
                )
                append(",\"r\":").append(b.runs)
                append(",\"b\":").append(b.balls)
                append(",\"f\":").append(b.fours)
                append(",\"s\":").append(b.sixes)
                append(",\"out\":").append(b.isOut)
                append("}")
            }
            append("],\"bowling\":[")
            card.bowling.forEachIndexed { i, w ->
                if (i > 0) append(",")
                append("{\"name\":").append(quote(names[w.player] ?: ""))
                append(",\"o\":").append(quote(w.overs))
                append(",\"m\":").append(w.maidens)
                append(",\"r\":").append(w.runsConceded)
                append(",\"w\":").append(w.wickets)
                append("}")
            }
            append("],\"extras\":").append(card.extras)
            append(",\"byes\":").append(card.byes)
            append(",\"legByes\":").append(card.legByes)
            append(",\"wides\":").append(card.wides)
            append(",\"noBalls\":").append(card.noBalls)
            append(",\"total\":").append(quote(card.display))
            append(",\"runs\":").append(card.runs)
            append(",\"wickets\":").append(card.wickets)
            append(",\"fow\":[")
            card.fallOfWickets.forEachIndexed { i, f ->
                if (i > 0) append(",")
                append(quote(f.display))
            }
            append("]\n")
        }
    }

    private fun round(value: Double): String = (kotlin.math.round(value * 100) / 100).toString()

    private fun quote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
