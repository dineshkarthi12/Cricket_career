package com.cricketcareer.engine.match.state

import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.MatchFormat

/**
 * The scorer.
 *
 * This is the one deliberately *mutable* part of the engine (CLAUDE.md §5): a
 * match plays 250-2,700 balls, and rebuilding an immutable innings — with its
 * batting card, bowling card and partnership list — on every one of them would
 * copy those lists per ball and blow the per-ball budget in
 * docs/ARCHITECTURE.md §7. Mutation is confined here and to the RNG; everything
 * that leaves is an immutable snapshot.
 *
 * It knows the Laws of scoring and nothing about simulation, so it can be
 * tested exhaustively without an engine to drive it — which matters, because
 * silently mis-scoring a leg bye corrupts every career statistic downstream and
 * no distribution test would ever catch it.
 */
class InningsState(
    val format: MatchFormat,
    val battingTeam: String,
    val bowlingTeam: String,
    /** The batting order, 1 to 11. Batters come in from here as wickets fall. */
    val battingOrder: List<PlayerId>,
    /** Overs available, when a rain-reduced innings shortens them. Null means the format's full allocation. */
    oversAvailable: Int? = format.oversPerInnings,
    /** Runs needed to win, when batting last. Null in a first innings. */
    target: Int? = null,
) {
    init {
        require(battingOrder.size >= 2) { "an innings needs at least two batters" }
        require(battingOrder.distinct().size == battingOrder.size) { "duplicate player in the batting order" }
        require(oversAvailable == null || oversAvailable > 0) { "oversAvailable must be positive" }
        require(target == null || target > 0) { "target must be positive" }
    }

    // --- Running totals -----------------------------------------------------

    var runs: Int = 0
        private set

    /**
     * Overs this innings has, as it stands.
     *
     * Mutable only downwards, and only through [reduceOversTo]: rain takes
     * overs away mid-innings, and the batting side has to be told — the intent
     * model reads [ballsRemaining], so a side told it has fifteen overs instead
     * of thirty starts playing like it. That acceleration is most of what a
     * rain-shortened chase feels like, and it comes out of the existing model
     * rather than a special case.
     */
    var oversAvailable: Int? = oversAvailable
        private set

    /**
     * Runs needed to win, as it stands.
     *
     * Revised by rain, through [reviseTarget]. A chasing side that loses overs
     * is chasing a different number afterwards, and the scoreboard has to say
     * so from the moment the players come back.
     */
    var target: Int? = target
        private set

    var wickets: Int = 0
        private set

    /** Legal balls bowled. Wides and no-balls do not advance this. */
    var legalBalls: Int = 0
        private set

    var byes: Int = 0
        private set
    var legByes: Int = 0
        private set
    var wides: Int = 0
        private set
    var noBalls: Int = 0
        private set
    var penaltyRuns: Int = 0
        private set

    /** Set when the captain declares, or when the innings is forfeited. */
    var declared: Boolean = false
        private set

    // --- Who is where -------------------------------------------------------

    var striker: PlayerId = battingOrder[0]
        private set

    var nonStriker: PlayerId = battingOrder[1]
        private set

    var bowler: PlayerId? = null
        private set

    /** Index of the next batter to come in. Starts at 2 — the openers are already out there. */
    private var nextBatterIndex: Int = 2

    // LinkedHashMap throughout: iteration order is the order players appeared,
    // which is the order a scorecard prints them, and is deterministic.
    private val batterCards = LinkedHashMap<PlayerId, MutableBatterInnings>()
    private val bowlerCards = LinkedHashMap<PlayerId, MutableBowlerFigures>()
    private val fallOfWickets = mutableListOf<FallOfWicket>()
    private val completedPartnerships = mutableListOf<Partnership>()

    private var partnershipRuns: Int = 0
    private var partnershipBalls: Int = 0

    /**
     * Whether a stand is currently in progress. False only between a wicket
     * falling and the next batter reaching the crease, and after a declaration
     * or the final wicket — so a completed chase still shows its unbroken
     * winning partnership.
     */
    private var partnershipOpen: Boolean = true
    private var overRunsChargedToBowler: Int = 0
    private var overLegalBalls: Int = 0

    init {
        cardFor(battingOrder[0], position = 1)
        cardFor(battingOrder[1], position = 2)
    }

    // --- Reading the state --------------------------------------------------

    val extras: Int get() = byes + legByes + wides + noBalls + penaltyRuns

    /** Completed overs and balls into the current over, as the scoreboard shows it. */
    val oversBowled: String get() = "${legalBalls / MatchFormat.BALLS_PER_OVER}.${legalBalls % MatchFormat.BALLS_PER_OVER}"

    val completedOvers: Int get() = legalBalls / MatchFormat.BALLS_PER_OVER

    val ballsIntoOver: Int get() = legalBalls % MatchFormat.BALLS_PER_OVER

    /** True at the exact moment an over has been completed and a new bowler is needed. */
    val atEndOfOver: Boolean get() = legalBalls > 0 && ballsIntoOver == 0

    val runRate: Double get() = if (legalBalls == 0) 0.0 else runs * MatchFormat.BALLS_PER_OVER.toDouble() / legalBalls

    /** Balls left in the innings, or null in multi-day cricket where there is no limit. */
    val ballsRemaining: Int?
        get() = oversAvailable?.let { (it * MatchFormat.BALLS_PER_OVER - legalBalls).coerceAtLeast(0) }

    /** Runs still needed to win, or null when not chasing. */
    val runsRequired: Int? get() = target?.let { (it - runs).coerceAtLeast(0) }

    /** Every batter has been dismissed, or there is nobody left to partner the survivor. */
    /**
     * Cut this innings short. Rain only ever takes overs away.
     *
     * Refuses to go below what has already been bowled: an innings cannot be
     * reduced to fewer overs than it has played, and a stoppage that would do
     * that is an innings that is simply over.
     */
    /**
     * Set the revised target after an interruption.
     *
     * Only rain revises a target, and when it does the new number is what the
     * resource table says the innings is worth — never the opposition's score
     * plus one. Refuses to be called on an innings that is not chasing, because
     * a first innings with a target is a contradiction rather than a typo.
     */
    fun reviseTarget(runs: Int) {
        check(target != null) { "this innings is not chasing anything" }
        require(runs >= 1) { "a target of $runs is not a target" }
        target = runs
    }

    fun reduceOversTo(overs: Int) {
        check(!isComplete) { "the innings is already over" }
        val current = oversAvailable
        require(current == null || overs <= current) { "rain cannot hand overs back: $current -> $overs" }
        require(overs >= completedOvers) { "cannot reduce to $overs overs with $completedOvers already bowled" }
        oversAvailable = overs
    }

    val allOut: Boolean get() = wickets >= MatchFormat.WICKETS_PER_INNINGS || nextBatterIndex > battingOrder.size

    val chaseComplete: Boolean get() = target?.let { runs >= it } == true

    val oversExhausted: Boolean get() = ballsRemaining == 0

    val isComplete: Boolean get() = allOut || chaseComplete || oversExhausted || declared

    fun batterCard(player: PlayerId): BatterInnings? = batterCards[player]?.snapshot()

    fun bowlerCard(player: PlayerId): BowlerFigures? = bowlerCards[player]?.snapshot()

    /** Overs already bowled by [player], for enforcing the per-bowler cap. */
    fun oversBowledBy(player: PlayerId): Int =
        (bowlerCards[player]?.legalBalls ?: 0) / MatchFormat.BALLS_PER_OVER

    /** Whether [player] may bowl the next over under the format's cap and the two-in-a-row rule. */
    fun mayBowlNextOver(player: PlayerId): Boolean {
        if (player == bowler) return false // nobody bowls consecutive overs
        val cap = format.maxOversPerBowler ?: return true
        return oversBowledBy(player) < cap
    }

    // --- Driving the innings ------------------------------------------------

    /**
     * Nominate the bowler for the next over. Must be called before the first
     * ball of every over, including the first of the innings.
     */
    fun setBowler(player: PlayerId) {
        require(mayBowlNextOver(player)) {
            "$player may not bowl: ${if (player == bowler) "bowled the previous over" else "has reached the over limit"}"
        }
        bowler = player
        bowlerCards.getOrPut(player) { MutableBowlerFigures(player) }
        overRunsChargedToBowler = 0
        overLegalBalls = 0
    }

    /**
     * Score one delivery.
     *
     * Order matters and is the order a scorer works in: credit the batter who
     * actually faced it, charge the bowler, add to the team, resolve the
     * wicket, then work out who is on strike for the next ball.
     */
    fun record(outcome: DeliveryOutcome) {
        check(!isComplete) { "the innings is already complete" }
        val currentBowler = checkNotNull(bowler) { "no bowler has been nominated for this over" }

        val facingBatter = striker
        val batterCard = batterCards.getValue(facingBatter)
        val bowlerCard = bowlerCards.getValue(currentBowler)

        // 1. The batter who faced it.
        batterCard.runs += outcome.runsOffBat
        if (outcome.isBallFaced) batterCard.balls++
        when (outcome.runsOffBat) {
            4 -> batterCard.fours++
            6 -> batterCard.sixes++
        }

        // 2. The bowler. Byes and leg byes are not his.
        bowlerCard.runsConceded += outcome.runsChargedToBowler
        bowlerCard.wides += outcome.wides
        if (outcome.noBall) bowlerCard.noBalls++
        if (outcome.isLegalBall) bowlerCard.legalBalls++
        overRunsChargedToBowler += outcome.runsChargedToBowler
        if (outcome.isLegalBall) overLegalBalls++

        // 3. The team.
        runs += outcome.totalRuns
        byes += outcome.byes
        legByes += outcome.legByes
        wides += outcome.wides
        if (outcome.noBall) noBalls += DeliveryOutcome.NO_BALL_PENALTY
        penaltyRuns += outcome.penaltyRuns
        if (outcome.isLegalBall) legalBalls++

        partnershipRuns += outcome.totalRuns
        if (outcome.isLegalBall) partnershipBalls++

        // 4. The wicket, if there was one.
        val dismissal = outcome.dismissal
        if (dismissal != null) {
            recordWicket(dismissal, bowlerCard)
        }

        // 5. Ends. Without a wicket the batters swap on an odd number of runs
        //    run; with one, `battersCrossed` is authoritative, because a catch
        //    taken after they crossed leaves the survivor on strike.
        val endsSwapped = if (dismissal != null) outcome.battersCrossed else outcome.runsRun % 2 == 1
        var strikersEnd = if (endsSwapped) nonStriker else striker
        var otherEnd = if (endsSwapped) striker else nonStriker

        if (dismissal != null && !isComplete) {
            val replacement = battingOrder[nextBatterIndex - 1]
            if (strikersEnd == dismissal.batterOut) strikersEnd = replacement else otherEnd = replacement
            partnershipOpen = true
        }
        striker = strikersEnd
        nonStriker = otherEnd

        // 6. End of the over: maiden check, then the batters change ends.
        if (outcome.isLegalBall && overLegalBalls == MatchFormat.BALLS_PER_OVER) {
            if (overRunsChargedToBowler == 0) bowlerCard.maidens++
            val swap = striker
            striker = nonStriker
            nonStriker = swap
            overLegalBalls = 0
            overRunsChargedToBowler = 0
        }
    }

    /** Declare the innings closed. */
    fun declare() {
        check(!isComplete) { "the innings is already complete" }
        declared = true
        closePartnership(unbroken = true)
    }

    private fun recordWicket(dismissal: Dismissal, bowlerCard: MutableBowlerFigures) {
        val out = dismissal.batterOut
        require(out == striker || out == nonStriker) { "$out is not at the crease" }

        wickets++
        if (dismissal.mode.creditedToBowler) bowlerCard.wickets++

        batterCards.getValue(out).dismissal = dismissal
        fallOfWickets += FallOfWicket(
            wicketNumber = wickets,
            runs = runs,
            legalBalls = legalBalls,
            batterOut = out,
        )
        closePartnership(unbroken = false)

        // Bring in the next batter, if there is one. When there is not, `allOut`
        // becomes true and no replacement is placed at the crease.
        if (nextBatterIndex < battingOrder.size) {
            nextBatterIndex++
            cardFor(battingOrder[nextBatterIndex - 1], position = nextBatterIndex)
        } else {
            nextBatterIndex++
        }
    }

    private fun closePartnership(unbroken: Boolean) {
        completedPartnerships += Partnership(
            wicketNumber = if (unbroken) wickets + 1 else wickets,
            batterA = striker,
            batterB = nonStriker,
            runs = partnershipRuns,
            balls = partnershipBalls,
            unbroken = unbroken,
        )
        partnershipRuns = 0
        partnershipBalls = 0
        partnershipOpen = false
    }

    private fun cardFor(player: PlayerId, position: Int) {
        batterCards[player] = MutableBatterInnings(player, position)
    }

    /** An immutable copy of the innings as it stands. */
    fun snapshot(): InningsScorecard = InningsScorecard(
        battingTeam = battingTeam,
        bowlingTeam = bowlingTeam,
        runs = runs,
        wickets = wickets,
        legalBalls = legalBalls,
        declared = declared,
        byes = byes,
        legByes = legByes,
        wides = wides,
        noBalls = noBalls,
        penaltyRuns = penaltyRuns,
        batting = batterCards.values.map { it.snapshot() },
        bowling = bowlerCards.values.map { it.snapshot() },
        fallOfWickets = fallOfWickets.toList(),
        partnerships = buildList {
            addAll(completedPartnerships)
            if (partnershipOpen) {
                add(
                    Partnership(
                        wicketNumber = wickets + 1,
                        batterA = striker,
                        batterB = nonStriker,
                        runs = partnershipRuns,
                        balls = partnershipBalls,
                        unbroken = true,
                    ),
                )
            }
        },
    )
}
