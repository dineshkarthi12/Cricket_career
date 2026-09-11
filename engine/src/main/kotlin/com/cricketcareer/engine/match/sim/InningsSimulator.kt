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
    private val pitch: Pitch,
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

    private val ballsFaced = mutableMapOf<PlayerId, Int>()
    private val ballsBowled = mutableMapOf<PlayerId, Int>()
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
    fun simulate(target: Int? = null, oversAvailable: Int? = format.oversPerInnings): InningsState {
        val state = InningsState(
            format = format,
            battingTeam = "BAT",
            bowlingTeam = "BOWL",
            battingOrder = battingSide.map { it.id },
            oversAvailable = oversAvailable,
            target = target,
        )

        var previousBowler: PlayerId? = null
        while (!state.isComplete) {
            val bowler = chooseBowler(state, previousBowler)
            state.setBowler(bowler.id)
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
        }
        return state
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
            ball = BallCondition.at(state.completedOvers.toDouble(), tuning.movement, weather.outfieldAbrasion),
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
     * Bowler fatigue inside this match.
     *
     * Rises with overs bowled and is slowed by stamina and fitness. A bowler in
     * his fourth over of a spell is measurably worse, which is the clearest
     * observable fatigue effect in real cricket.
     */
    private fun fatigueFor(bowler: Player): Double {
        val overs = ballsBowled.getOrDefault(bowler.id, 0) / MatchFormat.BALLS_PER_OVER.toDouble()
        val endurance = 0.5 + 0.5 * bowler.attributes.normalised(Attribute.STAMINA) +
            0.3 * bowler.attributes.normalised(Attribute.FITNESS)
        val reference = if (format.isMultiDay) 22.0 else 7.0
        return (overs / (reference * endurance)).coerceIn(0.0, 1.0)
    }

    /** What a good score would be scored at here, used as the pressure yardstick. */
    private fun parRunRate(): Double = when {
        format.isMultiDay -> 3.2
        (format.oversPerInnings ?: 50) <= 20 -> 8.3
        (format.oversPerInnings ?: 50) <= 40 -> 6.2
        else -> 5.8
    }
}
