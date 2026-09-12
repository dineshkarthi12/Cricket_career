package com.cricketcareer.presentation.demo

import com.cricketcareer.engine.commentary.CommentaryGenerator
import com.cricketcareer.engine.generator.PlayerGenerator
import com.cricketcareer.engine.generator.PlayerSpec
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.sim.MatchSimulator
import com.cricketcareer.engine.match.state.InningsScorecard
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.BoundaryShape
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.model.world.PitchArchetype
import com.cricketcareer.engine.model.world.SoilType
import com.cricketcareer.engine.model.world.Venue
import com.cricketcareer.engine.rng.SimRandom
import com.cricketcareer.presentation.MatchSession
import com.cricketcareer.presentation.Names
import com.cricketcareer.presentation.resultText
import java.time.LocalDate

/** A whole simulated match, ready for a screen to show. */
data class DemoMatchData(
    val session: MatchSession,
    val names: Names,
    val homeTeam: String,
    val awayTeam: String,
    val firstInnings: InningsScorecard?,
    val secondInnings: InningsScorecard?,
    val result: String?,
)

/**
 * One simulated T20, for the app to show before there is a career to load.
 *
 * **This is scaffolding with an expiry date.** Choosing two squads, a venue and
 * a pitch is the seed database's job, and the seed database is `:data` in
 * Phase 7. Until then something has to hand the UI a match, and the only
 * alternative is doing it in `:app` — which is the one module that must never
 * decide anything about cricket. Of the two wrong homes this is the less wrong
 * one, and it is the single place in `:presentation` that touches the
 * simulator. See docs/UI.md §2.
 *
 * It is deterministic in [seed], like everything else: the same seed shows the
 * same match, so a screenshot of a bug is a bug report.
 */
object DemoMatch {

    private const val SQUAD_SIZE = 13

    fun simulate(seed: Long, format: MatchFormat = MatchFormat.T20): DemoMatchData {
        val generator = PlayerGenerator()
        val random = SimRandom.fromSeed(seed)
        val today = LocalDate.of(2026, 4, 1)

        fun squad(prefix: String): List<Player> = generator.generateSquad(
            rng = random,
            spec = PlayerSpec(country = "IND", region = "MH", level = LadderLevel.STATE_WHITE_BALL),
            size = SQUAD_SIZE,
            today = today,
            idPrefix = prefix,
        )

        val home = squad("MH")
        val away = squad("TN")
        val events = mutableListOf<BallEvent>()

        val match = MatchSimulator(
            format = format,
            homeSide = home,
            awaySide = away,
            venue = VENUE,
            startingPitch = Pitch.AVERAGE,
            weather = Weather.AVERAGE,
            level = LadderLevel.STATE_WHITE_BALL,
            seed = seed,
            sink = BallEventSink { events += it },
        ).simulate()

        val names = Names(home + away)
        val commentary = CommentaryGenerator { names.short(it) }

        val innings = match.completedInnings
        val first = innings.getOrNull(0)?.snapshot()
        val second = innings.getOrNull(1)?.snapshot()

        // The chase is the half worth watching, so that is the innings the
        // playback runs over.
        val chaseBalls = events.filter { it.id.innings == 1 }
        val playbackBalls = if (chaseBalls.isNotEmpty()) chaseBalls else events

        return DemoMatchData(
            session = MatchSession(
                balls = playbackBalls,
                format = format,
                commentary = commentary::describe,
                target = first?.let { it.runs + 1 }?.takeIf { chaseBalls.isNotEmpty() },
                result = resultText(match.result),
                feedLength = 8,
            ),
            names = names,
            homeTeam = match.setup.homeTeam,
            awayTeam = match.setup.awayTeam,
            firstInnings = first,
            secondInnings = second,
            result = resultText(match.result),
        )
    }

    private val VENUE = Venue(
        id = "demo-ground",
        name = "City Ground",
        city = "Chennai",
        country = "IND",
        region = "TN",
        boundary = BoundaryShape.AVERAGE,
        soilType = SoilType.CLAY,
        archetypeWeights = mapOf(PitchArchetype.BALANCED to 1.0),
    )
}
