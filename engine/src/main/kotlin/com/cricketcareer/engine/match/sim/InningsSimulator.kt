package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.match.delivery.BallCondition
import com.cricketcareer.engine.match.delivery.BowlingPlan
import com.cricketcareer.engine.match.delivery.DeliveryContext
import com.cricketcareer.engine.match.delivery.MatchSituation
import com.cricketcareer.engine.match.delivery.Pressure
import com.cricketcareer.engine.match.delivery.Stage1Intent
import com.cricketcareer.engine.match.delivery.Stage2Execution
import com.cricketcareer.engine.match.delivery.Stage3Movement
import com.cricketcareer.engine.match.delivery.Stage4Read
import com.cricketcareer.engine.match.delivery.Stage5Contact
import com.cricketcareer.engine.match.delivery.Stage6Outcome
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.event.BallId
import com.cricketcareer.engine.match.field.FieldCaptain
import com.cricketcareer.engine.match.field.FieldSetting
import com.cricketcareer.engine.match.field.MatchPhase
import com.cricketcareer.engine.match.pitch.PitchEvolution
import com.cricketcareer.engine.match.pitch.Session
import com.cricketcareer.engine.match.state.DeliveryOutcome
import com.cricketcareer.engine.match.state.InningsState
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.model.world.Venue
import com.cricketcareer.engine.rng.MatchRandom
import com.cricketcareer.engine.rng.RngStreams
import kotlin.math.pow

/**
 * Simulates one innings, ball by ball.
 *
 * Owns the bookkeeping the six stages do not: who is bowling, how tired he is,
 * what the field is, what the bowler currently believes about the batter, and
 * the pressure index. Every ball it assembles a [DeliveryContext], runs the
 * pipeline, and hands the scoring result to [InningsState].
 *
 * Never takes an over-by-over shortcut (CLAUDE.md non-negotiable 5).
 */
class InningsSimulator(
    private val format: MatchFormat,
    private val battingSide: List<Player>,
    private val bowlingSide: List<Player>,
    private val venue: Venue,
    pitch: Pitch,
    private val weather: Weather,
    private val level: LadderLevel,
    private val random: MatchRandom,
    private val tuning: EngineTuning = EngineTuning.DEFAULT,
    private val sink: BallEventSink = BallEventSink.Discard,
    /** The batting side's posture, -1 block to +1 attack. */
    private val battingIntent: Double = 0.0,
    private val inningsNumber: Int = 1,
) {
    private val byId: Map<PlayerId, Player> =
        (battingSide + bowlingSide).associateBy { it.id }

    private val bowlers: List<Player> = bowlingSide.filter { it.bowls }
        .ifEmpty { bowlingSide.sortedByDescending { it.attributes.normalised(Attribute.ACCURACY) }.take(5) }

    private val keeper: PlayerId = (bowlingSide.firstOrNull { it.keeps } ?: bowlingSide.last()).id

    // One stream per stage, held for the innings. Adding a draw inside one
    // stage leaves every other stage bit-identical (CLAUDE.md §4).
    private val intentRng = random.stream(RngStreams.BOWLER_INTENT)
    private val executionRng = random.stream(RngStreams.EXECUTION)
    private val movementRng = random.stream(RngStreams.BALL_MOVEMENT)
    private val readRng = random.stream(RngStreams.BATTER_READ)
    private val contactRng = random.stream(RngStreams.CONTACT)
    private val trajectoryRng = random.stream(RngStreams.TRAJECTORY)
    private val fieldingRng = random.stream(RngStreams.FIELDING)
    private val runningRng = random.stream(RngStreams.RUNNING)
    private val umpiringRng = random.stream(RngStreams.UMPIRING)
    private val captaincyRng = random.stream(RngStreams.CAPTAINCY)
    private val conditionsRng = random.stream(RngStreams.CONDITIONS)

    /**
     * The pitch, which changes under the players' feet. Exposed so a multi-day
     * match can carry the worn surface into the next innings rather than
     * starting every innings on a fresh one.
     */
    var pitch: Pitch = pitch
        private set

    /**
     * Overs bowled with the current ball.
     *
     * Not the same as overs bowled in the innings: in multi-day cricket the
     * fielding side takes a new one at 80, and everything the movement model
     * does - swing, reverse, hardness, carry to the cordon - reads this rather
     * than the innings age.
     */
    private var oversSinceNewBall: Double = 0.0
    private var newBallsTaken: Int = 1

    /** Overs bowled in the current session, for pitch evolution. */
    private var oversThisSession: Double = 0.0
    private var sessionIndex: Int = 0

    /** Overs this innings actually consumed, which the match clock needs. */
    val oversConsumed: Double get() = totalOversBowled

    private var totalOversBowled: Double = 0.0

    private val ballsFaced = mutableMapOf<PlayerId, Int>()
    private val ballsBowled = mutableMapOf<PlayerId, Int>()

    /** Overs in each bowler's current unbroken spell, and when he last bowled. */
    private val spellOvers = mutableMapOf<PlayerId, Int>()
    private val lastOverBowled = mutableMapOf<PlayerId, Int>()
    private val plans = mutableMapOf<Pair<PlayerId, PlayerId>, BowlingPlan>()
    private val ballsBowledAt = mutableMapOf<Pair<PlayerId, PlayerId>, Int>()
    private val recentOutcomes = ArrayDeque<DeliveryOutcome>()

    private var ballsSinceWicket = 0
    private var currentField: FieldSetting? = null

    /**
     * Bowl the innings out.
     *
     * @param target runs needed to win, when batting last.
     * @param oversAvailable a rain-reduced allocation, when there is one.
     */
    /**
     * Bowl the innings out.
     *
     * @param target runs needed to win, when batting last.
     * @param oversAvailable a rain-reduced allocation, when there is one.
     * @param overLimit overs of playing time left in the match. Multi-day
     *   cricket has no innings limit but it does have a clock, and an innings
     *   that runs out of match ends unfinished rather than all out.
     * @param declarationPolicy asked at the end of each over whether the captain
     *   is closing the innings. Multi-day only; a limited-overs side cannot
     *   declare.
     */
    fun simulate(
        target: Int? = null,
        oversAvailable: Int? = format.oversPerInnings,
        overLimit: Int? = null,
        declarationPolicy: ((InningsState) -> Boolean)? = null,
    ): InningsState = simulate(
        state = InningsState(
            format = format,
            battingTeam = "BAT",
            bowlingTeam = "BOWL",
            battingOrder = battingSide.map { it.id },
            oversAvailable = oversAvailable,
            target = target,
        ),
        overLimit = overLimit,
        declarationPolicy = declarationPolicy,
    )

    /**
     * Drive an innings the caller already owns.
     *
     * A whole match needs the match's own [InningsState] filled in, not a copy
     * of somebody else's: the result logic reads it, and copying a driven
     * innings back into a second object is a bug waiting to happen.
     */
    fun simulate(
        state: InningsState,
        overLimit: Int? = null,
        declarationPolicy: ((InningsState) -> Boolean)? = null,
    ): InningsState {
        var previousBowler: PlayerId? = null
        while (!state.isComplete) {
            val bowler = chooseBowler(state, previousBowler)
            state.setBowler(bowler.id)
            startOrContinueSpell(bowler.id, state.completedOvers)
            currentField = FieldCaptain.setField(
                bowlingSide = bowlingSide,
                bowler = bowler.id,
                keeper = if (keeper == bowler.id) bowlingSide.first { it.id != bowler.id }.id else keeper,
                phase = phaseFor(state),
                bowlerIsSpin = bowler.bowlingStyle.isSpin,
                fieldersOutsideLimit = format.fieldersOutsideCircleLimit(state.completedOvers),
            )

            val overStartedAt = state.legalBalls
            while (!state.isComplete && state.legalBalls - overStartedAt < MatchFormat.BALLS_PER_OVER) {
                bowlOne(state, bowler)
            }
            previousBowler = bowler.id

            if (declarationPolicy != null && !state.isComplete && declarationPolicy(state)) {
                state.declare()
            }
            if (overLimit != null && state.completedOvers >= overLimit) break

            val oversBowled = (state.legalBalls - overStartedAt) / MatchFormat.BALLS_PER_OVER.toDouble()
            oversSinceNewBall += oversBowled
            oversThisSession += oversBowled
            totalOversBowled += oversBowled
            takeNewBallIfDue(state)
            endSessionIfDue()
        }
        return state
    }

    /**
     * The second new ball.
     *
     * Available after [MatchFormat.newBallAfterOvers] and worth taking when the
     * side has quick bowlers to use it: a hard ball that carries to the cordon
     * and swings again is the single biggest lever a captain has in the middle
     * of a long innings. He does not always take it immediately - if the ball is
     * reversing and the spinners are on top, the old one is better.
     */
    private fun takeNewBallIfDue(state: InningsState) {
        val due = format.newBallAfterOvers ?: return
        if (oversSinceNewBall < due) return
        val hasPace = bowlers.any { it.bowlingStyle.isPace }
        if (!hasPace) return
        // Reverse swing on a rough old ball is a real reason to wait.
        val reversing = pitch.abrasion > 0.55 && pitch.moisture < 0.35
        val takeIt = !reversing || captaincyRng.chance(0.55)
        if (takeIt) {
            oversSinceNewBall = 0.0
            newBallsTaken++
        }
    }

    /** Wear the pitch at the end of each session of play. */
    private fun endSessionIfDue() {
        if (oversThisSession < tuning.pitch.sessionOvers) return
        pitch = PitchEvolution.afterSession(
            pitch = pitch,
            weather = weather,
            session = Session.entries[sessionIndex % Session.entries.size],
            oversThisSession = oversThisSession,
            uncoveredRain = 0.0,
            tuning = tuning.pitch,
            rng = conditionsRng,
        )
        oversThisSession = 0.0
        sessionIndex++
    }

    private fun bowlOne(state: InningsState, bowler: Player) {
        val striker = byId.getValue(state.striker)
        val nonStriker = byId.getValue(state.nonStriker)

        val situation = MatchSituation(
            over = state.completedOvers,
            ballInOver = state.ballsIntoOver,
            totalOvers = state.oversAvailable,
            runs = state.runs,
            wicketsLost = state.wickets,
            target = state.target,
            ballsRemaining = state.ballsRemaining,
            ballsSinceLastWicket = ballsSinceWicket,
            recentDots = recentOutcomes.count { it.totalRuns == 0 },
            recentBoundaries = recentOutcomes.takeLast(3).count { it.runsOffBat >= 4 },
            level = level,
        )

        val pressure = Pressure.index(
            situation = situation,
            strikerAttributes = striker.attributes,
            strikerHiddenBigMatch = striker.hidden.bigMatchFactor,
            parRunRate = parRunRate(),
            tuning = tuning.pressure,
        )

        val key = bowler.id to striker.id
        val bowledAt = ballsBowledAt.getOrDefault(key, 0)
        val belief = (1.0 - kotlin.math.exp(-bowledAt * tuning.intent.beliefLearningRate)).coerceIn(0.0, 0.9)
        val plan = plans.getOrPut(key) { Stage1Intent.drawPlan(bowler, striker, belief, intentRng, tuning) }
            .let { existing ->
                val failing = situation.recentBoundaries >= tuning.intent.planFailureBoundaries
                if (failing || intentRng.chance(tuning.intent.planRedrawChance)) {
                    Stage1Intent.drawPlan(bowler, striker, belief, intentRng, tuning).also { plans[key] = it }
                } else {
                    existing.copy(beliefStrength = belief)
                }
            }

        val context = DeliveryContext(
            format = format,
            bowler = bowler,
            striker = striker,
            nonStriker = nonStriker,
            pitch = pitch,
            venue = venue,
            weather = weather,
            ball = BallCondition.at(oversSinceNewBall, tuning.movement, weather.outfieldAbrasion),
            situation = situation,
            pressure = pressure,
            field = currentField!!,
            fieldingSide = byId,
            tuning = tuning,
            strikerBallsFaced = ballsFaced.getOrDefault(striker.id, 0),
            bowlerSpellFatigue = fatigueFor(bowler),
            plan = plan,
            battingIntent = battingIntent,
        )

        // --- The six stages.
        val intent = Stage1Intent.choose(context, intentRng)
        val release = Stage2Execution.execute(context, intent, executionRng)
        val delivered = Stage3Movement.apply(context, intent, release, movementRng)
        val perceived = Stage4Read.perceive(context, intent, delivered, readRng)
        val selection = Stage4Read.selectShot(context, perceived, readRng)
        val contact = Stage5Contact.resolve(context, delivered, perceived, selection, contactRng)
        val resolution = Stage6Outcome.resolve(
            context, intent, release, delivered, selection, contact,
            trajectoryRng, runningRng, umpiringRng,
        )

        val outcome = resolution.outcome
        val ballId = BallId(inningsNumber, state.completedOvers, state.ballsIntoOver + 1, state.legalBalls)
        state.record(outcome)

        if (outcome.isBallFaced) ballsFaced[striker.id] = ballsFaced.getOrDefault(striker.id, 0) + 1
        if (outcome.isLegalBall) ballsBowled[bowler.id] = ballsBowled.getOrDefault(bowler.id, 0) + 1
        ballsBowledAt[key] = bowledAt + 1
        ballsSinceWicket = if (outcome.dismissal != null) 0 else ballsSinceWicket + 1
        recentOutcomes.addLast(outcome)
        while (recentOutcomes.size > tuning.pressure.dotWindowBalls) recentOutcomes.removeFirst()

        sink.accept(
            BallEvent(
                id = ballId,
                striker = striker.id,
                nonStriker = nonStriker.id,
                bowler = bowler.id,
                intent = intent,
                release = release,
                delivered = delivered,
                perceived = perceived,
                shot = selection,
                contact = contact,
                trajectory = resolution.trajectory,
                fielding = resolution.fielding,
                outcome = outcome,
                facts = resolution.commentaryFacts,
                pressure = pressure,
                scoreAfter = state.runs,
                wicketsAfter = state.wickets,
            ),
        )
    }

    /**
     * Who bowls the next over.
     *
     * A captain saves his best for the phases that matter and does not burn a
     * strike bowler in the middle overs. Weighted rather than greedy, so two
     * innings with the same attack are not identical.
     */
    private fun chooseBowler(state: InningsState, previous: PlayerId?): Player {
        // mayBowlNextOver already enforces both the over cap and the rule that
        // nobody bowls consecutive overs.
        val legal = bowlers.filter { state.mayBowlNextOver(it.id) }

        // A captain must not paint himself into a corner. With five bowlers
        // capped at four overs in a twenty-over innings the allocation is exact,
        // and a careless over here leaves the last two overs to one man - which
        // the Laws forbid. Filter to choices that leave a legal allocation.
        val eligible = legal.filter { keepsAllocationFeasible(state, it.id) }
            .ifEmpty { legal }
            .ifEmpty { bowlers.filter { it.id != previous } }
            .ifEmpty { bowlers }

        val phase = phaseFor(state)
        val weights = DoubleArray(eligible.size) { i ->
            val bowler = eligible[i]
            val newBall = bowler.attributes.normalised(Attribute.NEW_BALL_SKILL)
            val death = bowler.attributes.normalised(Attribute.DEATH_BOWLING)
            val general = bowler.attributes.normalised(Attribute.ACCURACY) * 0.6 +
                bowler.attributes.normalised(Attribute.VARIATIONS) * 0.4
            val fit = when (phase) {
                MatchPhase.POWERPLAY -> 0.65 * newBall + 0.35 * general
                MatchPhase.MIDDLE -> general
                MatchPhase.DEATH -> 0.70 * death + 0.30 * general
            }
            // Spread the overs: a bowler who has already bowled his share is
            // less likely to be thrown the ball again.
            val used = ballsBowled.getOrDefault(bowler.id, 0) / MatchFormat.BALLS_PER_OVER.toDouble()
            val cap = format.maxOversPerBowler?.toDouble() ?: 20.0
            val freshness = (1.0 - used / cap).coerceIn(0.05, 1.0)
            // Squared freshness: spreading the overs matters more than the last
            // few points of suitability, because running out of legal bowlers is
            // not a trade-off, it is a dead end.
            (0.15 + fit).pow(2.0) * freshness * freshness
        }
        return captaincyRng.pickWeighted(eligible, weights)
    }

    /**
     * Would giving [candidate] the next over still leave a legal way to bowl
     * out the innings?
     *
     * A bowler can bowl at most every other over, so with `left` overs
     * remaining no one man can bowl more than ceil(left / 2) of them. The
     * innings is coverable when the bowlers' remaining capacity, each capped at
     * that share, adds up to the overs left.
     */
    private fun keepsAllocationFeasible(state: InningsState, candidate: PlayerId): Boolean {
        val cap = format.maxOversPerBowler ?: return true
        val total = state.oversAvailable ?: return true
        val oversLeft = total - state.completedOvers - 1
        if (oversLeft <= 0) return true

        val maxShare = (oversLeft + 1) / 2
        var usable = 0
        bowlers.forEach { bowler ->
            val bowled = state.oversBowledBy(bowler.id) + if (bowler.id == candidate) 1 else 0
            val remaining = (cap - bowled).coerceAtLeast(0)
            // The man who just bowled cannot bowl the very next over, which
            // costs him one of his available slots.
            val share = if (bowler.id == candidate) minOf(remaining, oversLeft / 2) else minOf(remaining, maxShare)
            usable += share
        }
        return usable >= oversLeft
    }

    private fun phaseFor(state: InningsState): MatchPhase {
        val total = state.oversAvailable ?: return MatchPhase.MIDDLE
        val over = state.completedOvers
        val limit = format.fieldersOutsideCircleLimit(over)
        return when {
            limit != null && limit <= 2 -> MatchPhase.POWERPLAY
            over >= total * 0.8 -> MatchPhase.DEATH
            else -> MatchPhase.MIDDLE
        }
    }

    /**
     * A spell continues when a bowler is operating from one end and comes back
     * every other over; anything longer than a two-over gap is a new spell and
     * he has had a rest.
     */
    private fun startOrContinueSpell(bowler: PlayerId, over: Int) {
        val last = lastOverBowled[bowler]
        val continuing = last != null && over - last <= SPELL_GAP_OVERS
        spellOvers[bowler] = if (continuing) spellOvers.getOrDefault(bowler, 0) + 1 else 1
        lastOverBowled[bowler] = over
    }

    /**
     * Bowler fatigue.
     *
     * Overwhelmingly a **spell** effect, with a smaller cumulative one behind
     * it. A bowler in his sixth over on the trot is measurably worse; the same
     * bowler in his tenth over of the innings, having had two rests, is not.
     *
     * Modelling it as cumulative overs — as this did at first — put every bowler
     * in a fifty-over innings at maximum fatigue, which handed the batting side
     * eight an over.
     */
    private fun fatigueFor(bowler: Player): Double {
        val stamina = bowler.attributes.normalised(Attribute.STAMINA)
        val fitness = bowler.attributes.normalised(Attribute.FITNESS)

        // Overs he can bowl on the trot before it starts to tell: five for a
        // modest bowler, nine for a workhorse.
        val spellCapacity = SPELL_CAPACITY_FLOOR + SPELL_CAPACITY_RANGE * stamina
        val spell = (spellOvers.getOrDefault(bowler.id, 0) / spellCapacity).coerceIn(0.0, 1.0)

        // And the long grind of a day in the field.
        val dayCapacity = if (format.isMultiDay) {
            MULTI_DAY_WORKLOAD_OVERS * (0.6 + 0.7 * fitness)
        } else {
            LIMITED_OVERS_WORKLOAD_OVERS * (0.6 + 0.7 * fitness)
        }
        val overs = ballsBowled.getOrDefault(bowler.id, 0) / MatchFormat.BALLS_PER_OVER.toDouble()
        val cumulative = (overs / dayCapacity).coerceIn(0.0, 1.0)

        return (spell * SPELL_WEIGHT + cumulative * CUMULATIVE_WEIGHT).coerceIn(0.0, 1.0)
    }

    private companion object {
        /** A gap longer than this, in overs, ends a spell and gives him a rest. */
        const val SPELL_GAP_OVERS = 2

        /** Overs on the trot before fatigue tells, at stamina 0 and at stamina 100. */
        const val SPELL_CAPACITY_FLOOR = 5.0
        const val SPELL_CAPACITY_RANGE = 4.0

        /** Overs in an innings before the grind tells. */
        const val LIMITED_OVERS_WORKLOAD_OVERS = 11.0
        const val MULTI_DAY_WORKLOAD_OVERS = 28.0

        /** Fatigue is mostly about the current spell, not the day's total. */
        const val SPELL_WEIGHT = 0.72
        const val CUMULATIVE_WEIGHT = 0.28
    }

    /** What a good score would be scored at here, used as the pressure yardstick. */
    private fun parRunRate(): Double = when {
        format.isMultiDay -> 3.2
        (format.oversPerInnings ?: 50) <= 20 -> 8.3
        (format.oversPerInnings ?: 50) <= 40 -> 6.2
        else -> 5.8
    }
}
