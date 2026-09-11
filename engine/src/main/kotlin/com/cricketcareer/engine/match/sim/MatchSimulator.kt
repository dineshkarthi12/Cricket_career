package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.state.InningsState
import com.cricketcareer.engine.match.state.MatchResult
import com.cricketcareer.engine.match.state.MatchState
import com.cricketcareer.engine.match.state.TossDecision
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.model.world.Venue
import com.cricketcareer.engine.rng.MatchRandom
import com.cricketcareer.engine.rng.RngStreams

/**
 * A whole match: toss, innings in order, follow-on, declarations, result.
 *
 * The pitch is carried from innings to innings rather than reset, which is the
 * point of multi-day cricket — the surface the side batting fourth gets is the
 * one the first three innings left behind.
 */
class MatchSimulator(
    private val format: MatchFormat,
    private val homeSide: List<Player>,
    private val awaySide: List<Player>,
    private val venue: Venue,
    private val startingPitch: Pitch,
    private val weather: Weather,
    private val level: LadderLevel,
    private val seed: Long,
    private val tuning: EngineTuning = EngineTuning.DEFAULT,
    private val sink: BallEventSink = BallEventSink.Discard,
) {
    private val random = MatchRandom(seed)
    private val conditionsRng = random.stream(RngStreams.CONDITIONS)
    private val captaincyRng = random.stream(RngStreams.CAPTAINCY)

    /** Playing time, in overs. A Test is ninety overs a day. */
    private val matchOvers: Int = format.days?.let { it * OVERS_PER_DAY } ?: Int.MAX_VALUE

    fun simulate(): MatchState {
        val state = MatchState(
            setup = com.cricketcareer.engine.match.state.MatchSetup(
                format = format,
                venue = venue,
                homeTeam = HOME,
                awayTeam = AWAY,
                homeXI = homeSide.map { it.id },
                awayXI = awaySide.map { it.id },
                seed = seed,
            ),
            pitch = startingPitch,
        )

        val tossWinner = if (conditionsRng.chance(0.5)) HOME else AWAY
        state.setToss(tossWinner, tossDecision())
        var battingFirst = if (state.toss!!.decision == TossDecision.BAT) tossWinner else state.setup.opponentOf(tossWinner)

        // Overs lost to weather. Without this every multi-day match finishes,
        // and roughly a quarter of real Tests are drawn because the rain came.
        val oversLostToWeather = if (format.isMultiDay) oversLostToRain() else 0
        var oversLeft = (matchOvers - oversLostToWeather).coerceAtLeast(0)
        var pitch = startingPitch
        var inningsNumber = 0

        while (state.result == null && inningsNumber < format.inningsPerSide * 2) {
            if (oversLeft <= 0) break

            // The follow-on decision has to come FIRST, because it is what
            // decides who bats next. Working out the batting side and then
            // asking whether this was a follow-on was circular, and the
            // follow-on could never be enforced at all.
            val enforcingFollowOn = inningsNumber == 2 &&
                state.followOnAvailable() && shouldEnforceFollowOn(oversLeft)
            val batting = battingSideFor(inningsNumber, battingFirst, state, enforcingFollowOn)

            val innings = state.startInnings(batting, enforcingFollowOn = enforcingFollowOn)
            val simulator = InningsSimulator(
                format = format,
                battingSide = sideFor(batting),
                bowlingSide = sideFor(state.setup.opponentOf(batting)),
                venue = venue,
                pitch = pitch,
                weather = weather,
                level = level,
                random = MatchRandom(com.cricketcareer.engine.rng.SimRandom.deriveSeed(seed, "innings-$inningsNumber")),
                tuning = tuning,
                sink = sink,
                inningsNumber = inningsNumber + 1,
            )
            val played = simulator.simulate(
                state = innings,
                overLimit = if (format.isMultiDay) oversLeft else null,
                declarationPolicy = if (format.isMultiDay) {
                    { current -> shouldDeclare(state, current, inningsNumber, oversLeft) }
                } else {
                    null
                },
            )

            oversLeft -= played.completedOvers
            pitch = simulator.pitch
            state.updatePitch(pitch)
            inningsNumber++
            state.concludeIfFinished()
        }

        if (state.result == null) state.concludeIfFinished(timeExpired = true)
        return state
    }

    private fun sideFor(team: String) = if (team == HOME) homeSide else awaySide

    /**
     * Playing time lost to weather across a multi-day match.
     *
     * Most matches lose nothing; a few lose a session; a handful lose most of a
     * day or more. Modelled as a skewed draw rather than a flat one because
     * that is how rain actually falls on a cricket match — the modal outcome is
     * none at all.
     */
    private fun oversLostToRain(): Int {
        val wetness = (weather.cloudCover * 0.7 + weather.humidity * 0.3).coerceIn(0.0, 1.0)
        if (!conditionsRng.chance(RAIN_CHANCE_BASE + RAIN_CHANCE_WEATHER * wetness)) return 0
        // Once it does rain, how much is lost is heavily skewed towards a
        // session or two rather than a whole day.
        val severity = conditionsRng.nextDouble() * conditionsRng.nextDouble()
        return (severity * matchOvers * MAX_SHARE_LOST_TO_RAIN).toInt()
    }

    /**
     * Who bats in this innings.
     *
     * Without a follow-on the order is A B A B. With one it is A B B A: the
     * side that followed on bats twice in succession, and the side that enforced
     * it bats last.
     */
    private fun battingSideFor(
        inningsNumber: Int,
        battingFirst: String,
        state: MatchState,
        enforcingFollowOn: Boolean,
    ): String {
        val other = state.setup.opponentOf(battingFirst)
        return when (inningsNumber) {
            0 -> battingFirst
            1 -> other
            2 -> if (enforcingFollowOn) other else battingFirst
            else -> if (state.followOnEnforced) battingFirst else other
        }
    }

    /**
     * Bat or bowl.
     *
     * The two things that actually decide it: a green, damp surface is at its
     * most dangerous in the first hour, and in a day-night game the side batting
     * second gets a wet ball and no spin. Everything else is noise, so it is
     * modelled as noise.
     */
    private fun tossDecision(): TossDecision {
        val firstMorningDanger = startingPitch.grassCover * 0.6 + startingPitch.moisture * 0.5
        val wearLater = if (format.isMultiDay) 0.45 else 0.0
        val batScore = 0.55 + wearLater - firstMorningDanger + conditionsRng.nextGaussian() * 0.18
        return if (batScore > 0.0) TossDecision.BAT else TossDecision.BOWL
    }

    /**
     * Whether to enforce the follow-on.
     *
     * The arithmetic is only half of it: a captain also has to believe there is
     * time to bowl the other side out twice, and has to be willing to put his
     * own quicks back on. Modern captains often bat again, so this is far from
     * automatic.
     */
    private fun shouldEnforceFollowOn(oversLeft: Int): Boolean {
        val timeEnough = oversLeft > matchOvers * 0.42
        if (!timeEnough) return false
        val quicks = homeSide.count { it.bowlingStyle.isPace && it.bowls }
        val willing = 0.35 + 0.05 * quicks
        return captaincyRng.chance(willing)
    }

    /**
     * Whether the captain is closing the innings.
     *
     * A declaration is a trade: more runs against fewer overs to bowl them out
     * in. The lead a captain wants scales with the time he has left, and he will
     * not declare at all until he is safe from losing.
     */
    private fun shouldDeclare(
        state: MatchState,
        innings: InningsState,
        inningsNumber: Int,
        oversLeftBefore: Int,
    ): Boolean {
        if (!format.isMultiDay) return false
        val oversLeft = oversLeftBefore - innings.completedOvers
        if (oversLeft <= 0) return false

        val lead = state.runsFor(innings.battingTeam) + innings.runs - state.runsFor(innings.bowlingTeam)
        val isLastInnings = inningsNumber == format.inningsPerSide * 2 - 1
        if (isLastInnings) return false

        // Roughly two and a half runs an over of safety, plus enough overs left
        // to take ten wickets - about sixty in a day and a half of cricket.
        val wantedLead = (oversLeft * 2.6).toInt().coerceAtLeast(160)
        val enoughTime = oversLeft in MINIMUM_OVERS_TO_BOWL_A_SIDE_OUT..(matchOvers)
        if (!enoughTime) return false

        // A first-innings declaration is rare and needs a huge score.
        if (inningsNumber == 0) return innings.runs > 620 && innings.wickets >= 6

        return lead >= wantedLead && captaincyRng.chance(0.55)
    }

    private companion object {
        const val HOME = "HOME"
        const val AWAY = "AWAY"

        /** Scheduled overs in a day of multi-day cricket. */
        const val OVERS_PER_DAY = 90

        /** Below this many overs left, nobody is bowling a side out. */
        const val MINIMUM_OVERS_TO_BOWL_A_SIDE_OUT = 55

        /** Chance a multi-day match loses any time at all to weather. */
        const val RAIN_CHANCE_BASE = 0.18
        const val RAIN_CHANCE_WEATHER = 0.45

        /** The most of a match that weather can take. */
        const val MAX_SHARE_LOST_TO_RAIN = 0.85
    }
}
