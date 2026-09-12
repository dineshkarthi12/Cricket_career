package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.dls.DuckworthLewis
import com.cricketcareer.engine.match.dls.Interruption
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
        val battingFirst = if (state.toss!!.decision == TossDecision.BAT) tossWinner else state.setup.opponentOf(tossWinner)

        if (!format.isMultiDay) {
            simulateLimitedOvers(state, battingFirst)
            return state
        }

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

    /**
     * A one-day or Twenty20 match, including the rain.
     *
     * Kept apart from the multi-day loop because almost nothing is shared: no
     * follow-on, no declaration, no match clock in overs, and — the reason this
     * method exists — a result that may have to be read off a par score rather
     * than off the scoreboard.
     */
    private fun simulateLimitedOvers(state: MatchState, battingFirst: String) {
        val scheduled = checkNotNull(format.oversPerInnings)
        val plan = RainModel.plan(format, weather, conditionsRng, tuning.rain)
        if (plan.isWashout) {
            state.concludeByDls(MatchResult.NoResult("abandoned without a ball bowled"))
            return
        }
        val second = state.setup.opponentOf(battingFirst)

        // ---- first innings ------------------------------------------------
        val beforeAnyPlay = plan.inInnings(1).filter { it.afterOvers == 0 }.sumOf { it.oversLost }
        val firstOvers = (scheduled - beforeAnyPlay).coerceAtLeast(0)
        if (firstOvers == 0) {
            state.concludeByDls(MatchResult.NoResult("rain; no play"))
            return
        }

        val firstInnings = state.startInnings(battingFirst, oversAvailable = firstOvers)
        val firstInterruptions = play(
            match = state,
            innings = firstInnings,
            inningsNumber = 0,
            pitch = startingPitch,
            stoppages = plan.inInnings(1).filter { it.afterOvers > 0 },
        )
        val firstResources = DuckworthLewis.resourcesAvailable(
            oversAtStart = firstOvers.toDouble(),
            interruptions = firstInterruptions,
            tuning = tuning.dls,
        )

        // ---- second innings -----------------------------------------------
        // The side batting second gets the overs the side batting first was
        // left with, not the overs it started with. Then the weather has
        // another go at it.
        val afterFirst = checkNotNull(firstInnings.oversAvailable)
        val lostBeforeSecond = plan.inInnings(2).filter { it.afterOvers == 0 }.sumOf { it.oversLost }
        val secondOvers = (afterFirst - lostBeforeSecond).coerceAtLeast(0)

        if (!enoughCricket(secondOvers)) {
            state.concludeByDls(MatchResult.NoResult("rain; ${secondOvers} overs was not a match"))
            return
        }

        fun targetFor(interruptions: List<Interruption>): Int = DuckworthLewis.target(
            firstInningsRuns = firstInnings.runs,
            resourcesFirst = firstResources,
            resourcesSecond = DuckworthLewis.resourcesAvailable(
                oversAtStart = secondOvers.toDouble(),
                interruptions = interruptions,
                tuning = tuning.dls,
            ),
            tuning = tuning.dls,
        )

        val openingTarget = targetFor(emptyList())
        val secondInnings = state.startInnings(second, oversAvailable = secondOvers, target = openingTarget)

        // The target is revised every time the players go off. That is not a
        // convenience: it is what makes the abandoned case fall out of the
        // ordinary result logic. A stoppage that takes the chase to nought
        // overs remaining leaves the side with resources it never used, and
        // the revised target for those resources *is* par plus one - so a
        // scoreboard comparison gives the right answer with no special case.
        play(
            match = state,
            innings = secondInnings,
            inningsNumber = 1,
            pitch = pitchAfter,
            stoppages = plan.inInnings(2).filter { it.afterOvers > 0 },
            onInterruption = { soFar -> secondInnings.reviseTarget(targetFor(soFar)) },
        )

        // Rain that cuts a chase below the minimum leaves no match, however far
        // ahead of par a side happens to be. A side bowled out, or one that got
        // there, has finished the job however few overs it took.
        val curtailed = !secondInnings.allOut && !secondInnings.chaseComplete
        if (curtailed && !enoughCricket(secondInnings.completedOvers)) {
            state.concludeByDls(
                MatchResult.NoResult("rain; ${secondInnings.completedOvers} overs was not a match"),
            )
            return
        }

        state.concludeIfFinished()
        // "(DLS method)" belongs on a scoreboard only when the target was
        // actually revised. Rain that cost nobody an over, or that cost both
        // sides the same, leaves an ordinary result.
        if (checkNotNull(secondInnings.target) != firstInnings.runs + 1) state.markResultDls()
    }

    /**
     * Bowl an innings, stopping for rain where the plan says to.
     *
     * Returns the stoppages as the resource table sees them: what the side had
     * to bat when the players went off, and what it had when they came back.
     */
    private fun play(
        match: MatchState,
        innings: com.cricketcareer.engine.match.state.InningsState,
        inningsNumber: Int,
        pitch: Pitch,
        stoppages: List<Stoppage>,
        onInterruption: (List<Interruption>) -> Unit = {},
    ): List<Interruption> {
        val simulator = InningsSimulator(
            format = format,
            battingSide = sideFor(innings.battingTeam),
            bowlingSide = sideFor(innings.bowlingTeam),
            venue = venue,
            pitch = pitch,
            weather = weather,
            level = level,
            random = MatchRandom(com.cricketcareer.engine.rng.SimRandom.deriveSeed(seed, "innings-$inningsNumber")),
            tuning = tuning,
            sink = sink,
            inningsNumber = inningsNumber + 1,
        )

        val interruptions = mutableListOf<Interruption>()
        for (stoppage in stoppages) {
            if (innings.isComplete) break
            simulator.simulate(state = innings, overLimit = stoppage.afterOvers)
            if (innings.isComplete) break

            val available = checkNotNull(innings.oversAvailable)
            val before = (available - innings.completedOvers).toDouble()
            val reducedTo = (available - stoppage.oversLost).coerceAtLeast(innings.completedOvers)
            val after = (reducedTo - innings.completedOvers).toDouble()
            if (after < before) {
                interruptions += Interruption(before, after, innings.wickets)
                val wasAvailable = available
                val targetBefore = innings.target
                innings.reduceOversTo(reducedTo)
                onInterruption(interruptions.toList())
                match.recordInterruption(
                    com.cricketcareer.engine.match.state.MatchInterruption(
                        innings = inningsNumber + 1,
                        afterOvers = innings.completedOvers,
                        oversBefore = wasAvailable,
                        oversAfter = reducedTo,
                        wicketsLost = innings.wickets,
                        runs = innings.runs,
                        targetBefore = targetBefore,
                        revisedTarget = innings.target,
                    ),
                )
            }
        }
        if (!innings.isComplete) simulator.simulate(state = innings)
        pitchAfter = simulator.pitch
        return interruptions
    }

    /**
     * Whether there has been enough cricket for anybody to have won.
     *
     * A playing condition, not a judgement: below the format's minimum the
     * match is a no result however far ahead of par a side happens to be.
     */
    private fun enoughCricket(overs: Int): Boolean {
        val minimum = format.minimumOversForResult ?: 1
        return overs >= minimum
    }

    private var pitchAfter: Pitch = startingPitch

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
