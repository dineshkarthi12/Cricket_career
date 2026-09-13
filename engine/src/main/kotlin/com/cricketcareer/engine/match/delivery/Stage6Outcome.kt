package com.cricketcareer.engine.match.delivery

import com.cricketcareer.engine.match.drs.BallTracking
import com.cricketcareer.engine.match.field.FieldPosition
import com.cricketcareer.engine.match.field.Fielder
import com.cricketcareer.engine.match.state.DeliveryOutcome
import com.cricketcareer.engine.match.state.Dismissal
import com.cricketcareer.engine.match.state.DismissalMode
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.world.FormatKind
import com.cricketcareer.engine.rng.SimRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Where the ball went after it was hit. */
data class Trajectory(
    /** Batter-relative bearing: 0 straight past the bowler, 90 square on the off side. */
    val azimuthDegrees: Double,
    val elevationDegrees: Double,
    val exitSpeedMetresPerSecond: Double,
    /** How far it carries before pitching, in metres. 0 for a ball hit along the ground. */
    val carryMetres: Double,
    val isAerial: Boolean,
)

/** What the fielding side did about it. */
data class FieldingResolution(
    val nearestFielder: Fielder?,
    val wasChance: Boolean,
    val caught: Boolean,
    val dropped: Boolean,
    val misfielded: Boolean,
    /**
     * Whether a fielder cut the ball off on its line, as opposed to chasing it
     * down after it had stopped. An intercepted ball is one he is moving onto,
     * which is a different proposition for the batters.
     */
    val intercepted: Boolean = false,
    /**
     * How far the gathering fielder was from the ball's line, in metres.
     *
     * This is what separates a push straight to cover from a push into the gap
     * beside him. The first is a dot because he is moving onto the ball with
     * his arm cocked; the second is a single because he is chasing it.
     */
    val fielderLateralMetres: Double = 0.0,
    /** Distance from the batter at which the ball was gathered. */
    val fieldedAtMetres: Double,
    val reachedBoundary: Boolean,
    val clearedBoundary: Boolean,
    /**
     * How hard the chance was, on the same scale the catch model works in, or
     * null when there was no chance.
     *
     * Kept rather than thrown away because "brilliant catch" and "regulation
     * catch" are different sentences, and the difference is already computed —
     * discarding it meant commentary had to guess from the outcome, which is
     * how you end up calling a simple one at midwicket a screamer.
     */
    val catchDifficulty: Double? = null,
    /** Whether a run out was completed by hitting the stumps rather than by a relay. */
    val directHit: Boolean = false,
)

/** Everything Stage 6 produced, including the scoring result. */
data class DeliveryResolution(
    val trajectory: Trajectory?,
    val fielding: FieldingResolution?,
    val outcome: DeliveryOutcome,
    val commentaryFacts: CommentaryFacts,
    /**
     * What a camera would have shown, for a ball that struck the pad.
     *
     * Produced whether or not anybody appealed and whether or not the umpire
     * gave it, because a review is a question asked *after* the decision and
     * the answer cannot depend on what the umpire said. Null on every ball that
     * was not an lbw shout.
     */
    val tracking: BallTracking? = null,
)

/**
 * The facts a commentary line is assembled from.
 *
 * Deliberately not a string. Commentary is generated from what actually
 * happened, never chosen from a table by outcome, so the generator gets the
 * causal chain and builds the sentence.
 */
data class CommentaryFacts(
    val beaten: Boolean,
    val edged: Boolean,
    val contactPoint: ContactPoint,
    val shot: Shot,
    val droppedBy: FieldPosition?,
    val caughtBy: FieldPosition?,
    val throughVacant: FieldPosition?,
    /** A run out completed by hitting the stumps, rather than by a relay. */
    val directHit: Boolean = false,
    /**
     * How hard the catch was, or null when there was no chance.
     *
     * Here so that "brilliant catch" and "regulation catch" are different
     * sentences. Commentary that has to infer difficulty from the outcome ends
     * up calling a simple one at midwicket a screamer.
     */
    val catchDifficulty: Double? = null,
)

/**
 * Stage 6 — trajectory, fielding and dismissal.
 *
 * Wides and no-balls decided geometrically, bowled, lbw with ball tracking a
 * review can be taken on, caught with real drops and a difficulty a commentator
 * can read, stumped, run out at either end by a direct hit or a relay, byes.
 *
 * See docs/SIMULATION_MODEL.md §9.
 */
object Stage6Outcome {

    fun resolve(
        context: DeliveryContext,
        intent: BowlerIntent,
        release: Release,
        ball: DeliveredBall,
        selection: ShotSelection,
        contact: Contact,
        rng: SimRandom,
        runningRng: SimRandom,
        umpiringRng: SimRandom,
    ): DeliveryResolution {
        val striker = context.striker
        val bowler = context.bowler

        // --- Wides. Geometric, never sampled: the rate is an emergent
        // consequence of the line error, which is the honest construction.
        val wideLine = if (context.format.kind == FormatKind.MULTI_DAY) {
            Geometry.RED_BALL_WIDE_LINE_M
        } else {
            Geometry.WHITE_BALL_WIDE_LINE_M
        }
        val offSideWide = ball.lineAtStumpsMetres > wideLine && !contact.point.hitTheBat
        val legSideWide = ball.lineAtStumpsMetres < Geometry.LEG_SIDE_WIDE_LINE_M && !contact.point.hitTheBat
        val bouncerWide = ball.heightAtStumpsMetres > Geometry.HEAD_HEIGHT_M && !contact.point.hitTheBat

        if (offSideWide || legSideWide || bouncerWide) {
            // The keeper may still let it through for more.
            val extra = if (rng.chance(0.05)) rng.nextInt(1, 5) else 0
            return DeliveryResolution(
                trajectory = null,
                fielding = null,
                outcome = DeliveryOutcome.wide(extra),
                commentaryFacts = CommentaryFacts(
                    beaten = false, edged = false, contactPoint = ContactPoint.MISSED,
                    shot = selection.shot, droppedBy = null, caughtBy = null, throughVacant = null,
                ),
            )
        }

        val noBall = release.isNoBall

        // --- No contact, or contact with the body. Bowled, lbw, stumped, byes.
        if (!contact.point.hitTheBat) {
            return resolveBeaten(context, intent, ball, selection, contact, noBall, rng, umpiringRng, runningRng)
        }

        // --- He hit it.
        val trajectory = trajectoryFor(context, ball, selection.shot, contact, rng)
        val fielding = resolveFielding(context, trajectory, contact, rng)

        // Caught.
        if (fielding.caught) {
            val catcher = fielding.nearestFielder!!
            return DeliveryResolution(
                trajectory = trajectory,
                fielding = fielding,
                outcome = DeliveryOutcome(
                    noBall = noBall,
                    // A no-ball cannot be a caught dismissal.
                    dismissal = if (noBall) null else Dismissal(
                        DismissalMode.CAUGHT,
                        striker.id,
                        bowler.id,
                        catcher.player,
                    ),
                    battersCrossed = rng.chance(0.28),
                ),
                commentaryFacts = CommentaryFacts(
                    beaten = false,
                    edged = contact.point.isEdge,
                    contactPoint = contact.point,
                    shot = selection.shot,
                    droppedBy = null,
                    caughtBy = catcher.position,
                    throughVacant = null,
                ),
            )
        }

        // An inside edge can still bowl him.
        if (contact.point == ContactPoint.INSIDE_EDGE && !noBall &&
            hitsStumps(ball.lineAtStumpsMetres - 0.10, ball.heightAtStumpsMetres) && rng.chance(0.30)
        ) {
            return DeliveryResolution(
                trajectory = trajectory,
                fielding = fielding,
                outcome = DeliveryOutcome(dismissal = Dismissal(DismissalMode.BOWLED, striker.id, bowler.id)),
                commentaryFacts = CommentaryFacts(
                    beaten = false, edged = true, contactPoint = contact.point,
                    shot = selection.shot, droppedBy = null, caughtBy = null, throughVacant = null,
                ),
            )
        }

        // Runs, and whether anybody was run out going for one too many.
        val runs = runsFor(context, trajectory, fielding, runningRng)
        val runOut = runs.runOutVictim?.let { victim ->
            Dismissal(DismissalMode.RUN_OUT, victim, bowler = null, fielder = fielding.nearestFielder?.player)
        }
        val resolved = fielding.copy(directHit = runs.directHit)

        return DeliveryResolution(
            trajectory = trajectory,
            fielding = resolved,
            outcome = DeliveryOutcome(
                runsOffBat = runs.runs,
                noBall = noBall,
                dismissal = runOut,
                battersCrossed = runs.crossed,
            ),
            commentaryFacts = CommentaryFacts(
                beaten = false,
                edged = contact.point.isEdge,
                contactPoint = contact.point,
                shot = selection.shot,
                droppedBy = if (fielding.dropped) fielding.nearestFielder?.position else null,
                caughtBy = null,
                throughVacant = if (fielding.reachedBoundary) null else fielding.nearestFielder?.position,
                directHit = runs.directHit,
                catchDifficulty = fielding.catchDifficulty,
            ),
        )
    }

    // --- Beaten, padded up, or hit on the body ------------------------------

    private fun resolveBeaten(
        context: DeliveryContext,
        intent: BowlerIntent,
        ball: DeliveredBall,
        selection: ShotSelection,
        contact: Contact,
        noBall: Boolean,
        rng: SimRandom,
        umpiringRng: SimRandom,
        runningRng: SimRandom,
    ): DeliveryResolution {
        val striker = context.striker
        val bowler = context.bowler
        val facts = CommentaryFacts(
            beaten = contact.point == ContactPoint.MISSED,
            edged = false,
            contactPoint = contact.point,
            shot = selection.shot,
            droppedBy = null,
            caughtBy = null,
            throughVacant = null,
        )

        fun resolution(outcome: DeliveryOutcome) = DeliveryResolution(null, null, outcome, facts)

        // He was beaten. Three things can be in the ball's way: the stumps, the
        // front pad, or nothing at all.
        var point = contact.point
        if (point == ContactPoint.MISSED) {
            val onStumps = hitsStumps(ball.lineAtStumpsMetres, ball.heightAtStumpsMetres)
            val inPadRange = ball.heightAtStumpsMetres < Geometry.STUMP_HEIGHT_M + 0.24 &&
                ball.lineAtStumpsMetres > -0.50 && ball.lineAtStumpsMetres < 0.40
            // A front-foot stride puts the pad in front of the stumps far more
            // often than a back-foot shot does, which is why lbw is a front-foot
            // dismissal and bowled is not.
            val padChance = when (selection.shot.foot) {
                FootMovement.FRONT -> 0.675
                FootMovement.BACK -> 0.38
                FootMovement.NONE -> 0.46
            }
            val padInTheWay = inPadRange && rng.chance(padChance)
            point = if (padInTheWay) ContactPoint.PAD else ContactPoint.MISSED

            if (!padInTheWay && onStumps && !noBall) {
                return resolution(
                    DeliveryOutcome(dismissal = Dismissal(DismissalMode.BOWLED, striker.id, bowler.id)),
                )
            }
        }

        // LBW. The tracking is produced whether or not the umpire gives it,
        // because a review asks what the ball did rather than what he said.
        if (point == ContactPoint.PAD && !noBall) {
            val tracking = track(context, ball, selection)
            val appeal = lbwDecision(context, ball, selection, umpiringRng, tracking)
            if (appeal) {
                return resolution(
                    DeliveryOutcome(dismissal = Dismissal(DismissalMode.LBW, striker.id, bowler.id)),
                ).copy(tracking = tracking)
            }
            // Struck on the pad and survived: leg byes are possible but the
            // batters rarely bother unless it ran away.
            val legBye = if (rng.chance(0.10)) runningRng.nextInt(1, 3) else 0
            return resolution(DeliveryOutcome(noBall = noBall, legByes = legBye)).copy(tracking = tracking)
        }

        if (point == ContactPoint.BODY) {
            val legBye = if (rng.chance(0.16)) runningRng.nextInt(1, 3) else 0
            return resolution(DeliveryOutcome(noBall = noBall, legByes = legBye))
        }

        // Beaten and through to the keeper. Stumping, then byes.
        val keeper = context.field.fielders.firstOrNull { it.position == FieldPosition.WICKETKEEPER }
        if (!noBall && context.bowlerIsSpin && keeper != null) {
            // Out of his ground: he has committed to an attacking front-foot
            // stroke, or gone down the pitch to a ball that was not there.
            val camedown = selection.shot.foot == FootMovement.FRONT &&
                (selection.shot.risk > 0.52 || ball.effectiveLengthMetres < 2.6)
            if (camedown) {
                val keeperSkill = context.fielderSkill(keeper.player, Attribute.STANDING_UP)
                val chance = context.tuning.outcome.stumpingCollectionBase * (0.5 + keeperSkill) *
                    (1.0 - context.strikerSkill(Attribute.FOOTWORK) * 0.55)
                if (rng.chance(chance)) {
                    return resolution(
                        DeliveryOutcome(
                            dismissal = Dismissal(DismissalMode.STUMPED, striker.id, bowler.id, keeper.player),
                        ),
                    )
                }
            }
        }

        // Byes: the keeper does not always gather it, and a big deviation or a
        // steep bounce makes him work.
        val difficulty = (abs(ball.totalDeviationMetres) * 1.6 + abs(ball.heightAtStumpsMetres - 0.8)).coerceIn(0.0, 1.6)
        val byeChance = context.tuning.outcome.byeBase * (0.5 + difficulty)
        // Byes are nearly always a single scampered through, occasionally four
        // past a diving keeper. A flat 1-4 would make them a scoring shot.
        val byes = if (rng.chance(byeChance)) {
            if (runningRng.chance(0.18)) 4 else 1
        } else {
            0
        }
        return resolution(DeliveryOutcome(noBall = noBall, byes = byes))
    }

    /** Would this ball have hit the stumps? */
    private fun hitsStumps(lineMetres: Double, heightMetres: Double): Boolean =
        abs(lineMetres) < Geometry.STUMP_HALF_WIDTH_M + Geometry.BALL_RADIUS_M &&
            heightMetres < Geometry.STUMP_HEIGHT_M

    /**
     * LBW, as the Laws lay it out.
     *
     * Pitching outside leg is not out however plumb it looks, and a ball
     * striking the pad outside off is only out if no stroke was offered. Then
     * the umpire, who is wrong more often the further down the ladder you are.
     */
    private fun lbwDecision(
        context: DeliveryContext,
        ball: DeliveredBall,
        selection: ShotSelection,
        umpiringRng: SimRandom,
    ): Boolean = lbwDecision(context, ball, selection, umpiringRng, track(context, ball, selection))

    private fun lbwDecision(
        context: DeliveryContext,
        ball: DeliveredBall,
        selection: ShotSelection,
        umpiringRng: SimRandom,
        tracking: BallTracking,
    ): Boolean {
        val tuning = context.tuning.outcome

        // Any of the three failing is not out however plumb the rest looks.
        // The Laws are a conjunction, and the umpire is only allowed to be
        // wrong about how close it was, not about the shape of the question.
        if (tracking.pitchingMargin <= 0.0 || tracking.impactMargin <= 0.0) return false

        val umpireQuality = context.situation.level.standard
        val sigma = tuning.umpireMarginSigmaWorst -
            (tuning.umpireMarginSigmaWorst - tuning.umpireMarginSigmaBest) * umpireQuality

        // He judges the *weakest* of the three, not wicket-hitting alone: an
        // umpire is less willing to raise the finger when impact was barely in
        // line, exactly as he is when the ball was barely clipping leg.
        //
        // And he misjudges the margin rather than flipping a coin on the
        // verdict. That is what puts his mistakes where real ones are - on the
        // balls that were close, and on height above all - and it is what gives
        // a review system something to catch.
        val perceivedMargin = tracking.outMargin + umpiringRng.nextGaussian() * sigma
        val probability = logistic(
            tuning.lbwDecisionSlope * perceivedMargin * context.tuning.knobs.lbwStrictnessScale,
        )
        return umpiringRng.chance(probability)
    }

    /**
     * The three lbw questions, as signed margins.
     *
     * Separated from the umpire's decision because a review asks what the ball
     * *did*, and the answer cannot depend on what the umpire said about it.
     * Before this existed the two were one function and there was nothing for a
     * third umpire to look at.
     *
     * One unit is one tolerance width: the distance over which a decision goes
     * from clear to marginal.
     */
    fun track(context: DeliveryContext, ball: DeliveredBall, selection: ShotSelection): BallTracking {
        val edge = Geometry.STUMP_HALF_WIDTH_M + Geometry.BALL_RADIUS_M

        // Pitching. Only outside leg is fatal, so the margin is how far inside
        // the leg-stump line it landed. A full toss never pitched at all.
        val pitchLine = ball.lineAtStumpsMetres - ball.totalDeviationMetres
        val pitchingMargin = if (ball.isFullToss) CLEAR else (pitchLine + edge) / LINE_TOLERANCE_M

        // Impact. In line is in line; outside off is only fatal if a stroke was
        // offered; outside leg is never out.
        val impactLine = ball.lineAtStumpsMetres
        val offeredShot = selection.shot.makesContact
        val impactMargin = when {
            impactLine < -edge -> -CLEAR
            impactLine > edge && offeredShot -> (edge - impactLine) / LINE_TOLERANCE_M
            impactLine > edge -> CLEAR
            else -> (edge - abs(impactLine)) / LINE_TOLERANCE_M
        }

        // Wickets. The pad is in front of the stumps, so the ball still has to
        // climb or turn past them from where it struck.
        val projectedLine = impactLine + ball.turnMetres * 0.30 + ball.swingMetres * 0.15
        val projectedHeight = ball.heightAtStumpsMetres + 0.13
        val lineMargin = (edge - abs(projectedLine)) / LINE_TOLERANCE_M
        val heightMargin = (Geometry.STUMP_HEIGHT_M - projectedHeight) / HEIGHT_TOLERANCE_M

        return BallTracking(
            pitchingMargin = pitchingMargin,
            impactMargin = impactMargin,
            wicketsMargin = minOf(lineMargin, heightMargin),
        )
    }

    /** Half a stump's width. Beyond this a line decision stops being arguable. */
    private const val LINE_TOLERANCE_M = 0.16

    /** Roughly a bail's height. The same idea, vertically. */
    private const val HEIGHT_TOLERANCE_M = 0.22

    /** A margin nobody would look at twice. */
    private const val CLEAR = 9.0

    // --- Off the bat --------------------------------------------------------

    private fun trajectoryFor(
        context: DeliveryContext,
        ball: DeliveredBall,
        shot: Shot,
        contact: Contact,
        rng: SimRandom,
    ): Trajectory {
        val tuning = context.tuning.outcome
        val quality = contact.quality

        // An edge decides where the ball goes; the shot barely gets a say. A
        // thin one carries to the keeper, a thick one is gully's problem.
        //
        // Thickness is *not* monotonic in how far the bat missed by. Beyond the
        // envelope the ball is catching the very outer edge, which is the
        // thinnest contact there is - so a feather is a near-straight deflection
        // to the keeper, not the squarest one of the lot. Reading it the other
        // way sent every feather to gully, which is where the wickets the
        // feather should have produced were going instead.
        val thickness = if (contact.feathered) {
            0.0
        } else {
            (abs(contact.lineMismatch) / 2.5).coerceIn(0.0, 1.0)
        }
        val baseAzimuth = when (contact.point) {
            ContactPoint.OUTSIDE_EDGE -> 178.0 - thickness * 52.0
            ContactPoint.INSIDE_EDGE -> 196.0 + thickness * 34.0
            ContactPoint.LEADING_EDGE -> 34.0 + rng.nextDouble(-16.0, 34.0)
            ContactPoint.TOP_EDGE -> shot.azimuthDegrees - 42.0 + rng.nextDouble(-22.0, 22.0)
            ContactPoint.BOTTOM_EDGE -> shot.azimuthDegrees + rng.nextDouble(-18.0, 18.0)
            ContactPoint.SPLICE, ContactPoint.GLOVE -> shot.azimuthDegrees - 20.0 + rng.nextDouble(-40.0, 40.0)
            else -> shot.azimuthDegrees
        }
        // Every other contact scatters in proportion to how badly it was struck,
        // which is right for a stroke and wrong for a deflection: a feather
        // barely changed the ball's direction, so it cannot have changed it by
        // very much.
        val spread = if (contact.feathered) {
            tuning.featherAzimuthSpread
        } else {
            tuning.azimuthSpreadBest +
                (tuning.azimuthSpreadWorst - tuning.azimuthSpreadBest) * (1.0 - quality)
        }
        val azimuth = (baseAzimuth + rng.nextGaussian() * spread).mod(360.0)

        // Elevation. An edge or a top edge goes up whether he meant it or not.
        val baseElevation = when (contact.point) {
            ContactPoint.TOP_EDGE -> 52.0 + rng.nextDouble(-14.0, 20.0)
            ContactPoint.SPLICE, ContactPoint.GLOVE -> 38.0 + rng.nextDouble(-16.0, 20.0)
            ContactPoint.LEADING_EDGE -> 28.0 + rng.nextDouble(-14.0, 22.0)
            ContactPoint.OUTSIDE_EDGE, ContactPoint.INSIDE_EDGE -> shot.elevationDegrees * 0.4 + rng.nextDouble(-3.0, 13.0)
            ContactPoint.BOTTOM_EDGE -> rng.nextDouble(-2.0, 5.0)
            // Scales sharply with how badly it was struck: a middled drive goes
            // along the ground, a half-hit one loops. A flat noise term put two
            // thirds of all shots in the air.
            else -> shot.elevationDegrees +
                rng.nextGaussian() * 3.5 * (1.5 - quality).pow(1.5)
        }
        // A feather leaves the bat at about the height it arrived, which is
        // chest height at the cordon and glove height at the keeper. Not the
        // near-flat deflection a defensive stroke's elevation would give it.
        val elevation = if (contact.feathered) {
            (tuning.featherElevationDegrees + rng.nextGaussian() * tuning.featherElevationSpread)
                .coerceIn(1.0, 26.0)
        } else {
            baseElevation.coerceIn(-4.0, 78.0)
        }

        val power = context.strikerSkill(Attribute.POWER)
        val timing = context.strikerSkill(Attribute.TIMING)
        val speedFromBat = tuning.batSpeedContribution * shot.powerFactor *
            (0.45 + 0.75 * power) * (0.7 + 0.45 * timing)
        // How much of the ball's own pace comes back off the bat depends on how
        // firmly it was struck. A dead bat absorbs it; a drive redirects it.
        // Giving every stroke the full rebound sent a forward defensive
        // twenty-four metres, which turned defence into a single-taking shot and
        // left Test cricket scoring at six an over.
        val firmness = tuning.deadBatAbsorption +
            (1.0 - tuning.deadBatAbsorption) * shot.powerFactor.coerceAtMost(1.0)
        val speedFromBall = tuning.incomingPaceTransfer * firmness * ball.paceKph / 3.6
        val qualityFactor = tuning.mistimedSpeedFloor + (1.0 - tuning.mistimedSpeedFloor) * quality
        val exitSpeed = if (contact.feathered) {
            // A feather is a deflection, not a stroke. The bat puts almost
            // nothing into it, but the ball keeps most of the pace it arrived
            // with - which is exactly why it carries to the cordon. Scoring it
            // by contact quality made it the slowest ball on the field and it
            // died at the batter's feet.
            (ball.paceKph / 3.6 * tuning.featherPaceRetained).coerceAtLeast(1.0)
        } else {
            ((speedFromBat + speedFromBall) * qualityFactor * context.tuning.knobs.batPowerScale)
                .coerceAtLeast(1.0)
        }

        // Below this it is a ball along the ground with a bit of air under it,
        // not a catching chance.
        val aerial = elevation > 8.0
        val carry = if (aerial) carryDistance(exitSpeed, elevation, context.venue.altitudeMetres) else 0.0

        return Trajectory(azimuth, elevation, exitSpeed, carry, aerial)
    }

    /**
     * Carry, from projectile motion with a drag correction.
     *
     * The correction is a closed form rather than an integration: this runs
     * once per ball in play and the per-ball budget does not allow a numerical
     * solve. Calibrated so a well-struck 35 m/s hit at 35 degrees carries about
     * 75 m, which is the distance a six actually needs.
     */
    fun carryDistance(exitSpeed: Double, elevationDegrees: Double, altitudeMetres: Double): Double {
        val radians = elevationDegrees * PI / 180.0
        val vacuum = exitSpeed * exitSpeed * sin(2.0 * radians) / GRAVITY
        val drag = 1.0 / (1.0 + DRAG_RANGE_COEFFICIENT * exitSpeed)
        // Thin air at altitude: the ball goes further.
        val altitude = 1.0 + altitudeMetres / 12000.0
        return (vacuum * drag * altitude).coerceAtLeast(0.0)
    }

    // --- Fielding -----------------------------------------------------------

    /**
     * Who gets to it, and what they do about it.
     *
     * Three distinct cases, because they are three distinct pieces of cricket:
     *
     *  - **The cordon** takes the ball *on its way through*, at chest height, with
     *    no time to judge it. That is an along-the-flight-path question.
     *  - **The outfield** takes it *coming down*, having run to where it will
     *    land. That is a can-he-get-there-in-time question, and modelling it
     *    like the cordon is why an earlier version of this never caught anything
     *    in the deep: a fielder 62 m out is four metres under a ball that is
     *    still ten metres up as it passes him.
     *  - **Along the ground**, nobody intercepts a ball hit into a gap; the
     *    nearest man chases it down, and how long that takes is what decides
     *    whether the batters get one, two or three.
     */
    private fun resolveFielding(
        context: DeliveryContext,
        trajectory: Trajectory,
        contact: Contact,
        rng: SimRandom,
    ): FieldingResolution {
        val tuning = context.tuning.outcome
        val boundary = context.venue.boundary.distanceAt(trajectory.azimuthDegrees)

        if (trajectory.isAerial && trajectory.carryMetres > boundary) {
            return FieldingResolution(
                nearestFielder = null, wasChance = false, caught = false, dropped = false,
                misfielded = false, fieldedAtMetres = boundary, reachedBoundary = true, clearedBoundary = true,
            )
        }

        // --- Catching chances.
        var catcher: Fielder? = null
        var catchDifficulty = Double.MAX_VALUE

        if (trajectory.isAerial) {
            val flightTime = timeOfFlight(trajectory)
            context.fieldersIncludingBowler.forEach { fielder ->
                val difficulty = if (fielder.position.catchesWithReflexes) {
                    cordonChance(trajectory, fielder, tuning)
                } else {
                    outfieldChance(trajectory, fielder, flightTime, tuning)
                } ?: return@forEach
                if (difficulty < catchDifficulty) {
                    catchDifficulty = difficulty
                    catcher = fielder
                }
            }
        }

        val chanceTaker = catcher
        if (chanceTaker != null) {
            // Close catchers use reflexes - there is no time to judge it in the
            // cordon or at short leg; the outfield uses catching.
            val catcherSkill = context.fielderSkill(
                chanceTaker.player,
                if (chanceTaker.position.catchesWithReflexes) Attribute.REFLEXES else Attribute.CATCHING,
            )
            val probability = catchProbability(context, catcherSkill, catchDifficulty)
            val held = rng.chance(probability)
            val genuine = probability >= GENUINE_CHANCE_THRESHOLD
            if (held) {
                return FieldingResolution(
                    nearestFielder = chanceTaker, wasChance = genuine, caught = true, dropped = false,
                    misfielded = false, fieldedAtMetres = chanceTaker.position.distanceMetres,
                    reachedBoundary = false, clearedBoundary = false,
                    catchDifficulty = catchDifficulty,
                )
            }
            if (genuine) {
                // Dropped. The ball is dead at his feet and they run one at most.
                return FieldingResolution(
                    nearestFielder = chanceTaker, wasChance = true, caught = false, dropped = true,
                    misfielded = false, fieldedAtMetres = chanceTaker.position.distanceMetres,
                    reachedBoundary = false, clearedBoundary = false,
                    catchDifficulty = catchDifficulty,
                )
            }
        }

        // --- Nobody caught it. How far would it run if left alone, and does
        // anybody cut it off first?
        val freeRunning = restingDistance(trajectory, boundary)

        // Interception: a fielder stops the ball where it passes him, if he can
        // get across to its line before it does. This is the whole infield/gap
        // dynamic - without it every firm shot runs to the rope and a field
        // setting means nothing.
        var chaser: Fielder? = null
        var stop = freeRunning
        var intercepted = false
        context.fieldersIncludingBowler.forEach { fielder ->
            val separation = FieldGaps.angularSeparation(trajectory.azimuthDegrees, fielder.position.azimuthDegrees)
            val along = fielder.position.distanceMetres * cos(separation * PI / 180.0)
            val lateral = fielder.position.distanceMetres * sin(separation * PI / 180.0)
            if (along <= 0.5 || along > freeRunning) return@forEach
            // A fielder cannot cut off a ball that flew over his head. Only
            // once it has pitched is it his to stop, which is the whole point
            // of hitting over the infield.
            if (trajectory.isAerial && along < trajectory.carryMetres) return@forEach
            if (intercepted && along >= stop) return@forEach

            val speed = tuning.interceptSpeedMetresPerSecond * fielder.position.mobility *
                (0.85 + 0.3 * context.fielderSkill(fielder.player, Attribute.SPEED))
            val fielderTime = tuning.interceptReactionSeconds +
                (lateral - tuning.fielderReachMetres).coerceAtLeast(0.0) / speed
            if (fielderTime <= ballTravelTime(trajectory, along)) {
                stop = along
                chaser = fielder
                intercepted = true
            }
        }

        if (!intercepted) {
            // Nobody cut it off; whoever is nearest goes and gets it.
            var bestTime = Double.MAX_VALUE
            context.fieldersIncludingBowler.forEach { fielder ->
                val separation = FieldGaps.angularSeparation(trajectory.azimuthDegrees, fielder.position.azimuthDegrees)
                val gap = distanceBetween(fielder.position.distanceMetres, separation, stop)
                val speed = tuning.fielderSpeedMetresPerSecond *
                    (0.85 + 0.3 * context.fielderSkill(fielder.player, Attribute.SPEED))
                val time = tuning.fielderReactionSeconds + gap / speed
                if (time < bestTime) {
                    bestTime = time
                    chaser = fielder
                }
            }
        }
        val reachedRope = !intercepted && freeRunning >= boundary - 0.01

        val misfield = rng.chance(
            tuning.misfieldBase * (1.6 - context.fielderSkill(
                chaser?.player ?: context.bowler.id, Attribute.GROUND_FIELDING,
            )),
        )

        val chaserLateral = chaser?.let { fielder ->
            val separation = FieldGaps.angularSeparation(trajectory.azimuthDegrees, fielder.position.azimuthDegrees)
            fielder.position.distanceMetres * sin(separation * PI / 180.0)
        } ?: Double.MAX_VALUE

        return FieldingResolution(
            nearestFielder = chaser,
            wasChance = false,
            caught = false,
            dropped = false,
            misfielded = misfield,
            intercepted = intercepted,
            fielderLateralMetres = chaserLateral,
            fieldedAtMetres = stop,
            reachedBoundary = reachedRope,
            clearedBoundary = false,
        )
    }

    /**
     * A catch in the cordon: can he reach the ball where it passes him, and is
     * it at a catchable height when it gets there?
     */
    private fun cordonChance(
        trajectory: Trajectory,
        fielder: Fielder,
        tuning: com.cricketcareer.engine.config.OutcomeTuning,
    ): Double? {
        val separation = FieldGaps.angularSeparation(trajectory.azimuthDegrees, fielder.position.azimuthDegrees)
        val along = fielder.position.distanceMetres * cos(separation * PI / 180.0)
        val lateral = fielder.position.distanceMetres * sin(separation * PI / 180.0)
        if (along <= 0.5 || along > trajectory.carryMetres) return null

        val height = heightAt(along, trajectory)
        if (height < 0.05 || height > 2.55) return null

        val horizontalSpeed = trajectory.exitSpeedMetresPerSecond * cos(trajectory.elevationDegrees * PI / 180.0)
        val arrival = along / horizontalSpeed.coerceAtLeast(0.5)
        val reach = (
            tuning.fielderReachMetres +
                tuning.fielderSpeedMetresPerSecond * (arrival - tuning.fielderReactionSeconds).coerceAtLeast(0.0) * 0.5
            ) * fielder.position.mobility
        if (lateral > reach) return null

        // Hard because there is no time, not because of distance.
        val stretch = (lateral / reach.coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
        val speed = (trajectory.exitSpeedMetresPerSecond / 30.0).coerceIn(0.0, 1.4)
        val awkward = (abs(height - 1.05) / 1.4).coerceIn(0.0, 1.0)
        return 0.80 * stretch + 0.50 * speed + 0.35 * awkward
    }

    /**
     * A catch in the deep: he runs to where it will land and waits for it.
     */
    private fun outfieldChance(
        trajectory: Trajectory,
        fielder: Fielder,
        flightTime: Double,
        tuning: com.cricketcareer.engine.config.OutcomeTuning,
    ): Double? {
        if (trajectory.carryMetres < 9.0) return null
        val separation = FieldGaps.angularSeparation(trajectory.azimuthDegrees, fielder.position.azimuthDegrees)
        val gap = distanceBetween(fielder.position.distanceMetres, separation, trajectory.carryMetres)
        val usable = (flightTime - tuning.fielderReactionSeconds).coerceAtLeast(0.0)
        val range = (tuning.fielderReachMetres + tuning.aerialClosingSpeed * usable) * fielder.position.mobility
        if (gap > range) return null

        // How much of his available ground he had to cover, and how steeply it
        // came down: a skier hanging up is easier than a flat, fast one.
        val stretch = (gap / range.coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
        val flat = (1.0 - (trajectory.elevationDegrees / 45.0)).coerceIn(0.0, 1.0)
        return 0.85 * stretch + 0.55 * flat
    }

    /** Straight-line distance between a fielder and a point on the ball's bearing. */
    private fun distanceBetween(fielderDistance: Double, separationDegrees: Double, pointDistance: Double): Double {
        // Law of cosines in the batter-relative frame.
        val theta = separationDegrees * PI / 180.0
        val squared = fielderDistance * fielderDistance + pointDistance * pointDistance -
            2.0 * fielderDistance * pointDistance * cos(theta)
        return kotlin.math.sqrt(squared.coerceAtLeast(0.0))
    }

    /** Time in the air, from the launch angle with the same drag correction as the carry. */
    private fun timeOfFlight(trajectory: Trajectory): Double {
        val radians = trajectory.elevationDegrees * PI / 180.0
        val vacuum = 2.0 * trajectory.exitSpeedMetresPerSecond * sin(radians) / GRAVITY
        return (vacuum / (1.0 + DRAG_RANGE_COEFFICIENT * trajectory.exitSpeedMetresPerSecond * 0.5))
            .coerceAtLeast(0.05)
    }

    /** Where the ball finally stops if nobody touches it, capped at the rope. */
    private fun restingDistance(trajectory: Trajectory, boundaryMetres: Double): Double {
        val speedAtGround = if (trajectory.isAerial) {
            // It has lost most of its pace by the time it pitches.
            trajectory.exitSpeedMetresPerSecond * 0.55 * cos(trajectory.elevationDegrees * PI / 180.0)
        } else {
            trajectory.exitSpeedMetresPerSecond
        }
        val roll = speedAtGround * speedAtGround / (2.0 * OUTFIELD_DECELERATION)
        return (trajectory.carryMetres + roll).coerceIn(0.0, boundaryMetres)
    }

    private fun heightAt(alongMetres: Double, trajectory: Trajectory): Double {
        if (trajectory.carryMetres <= 0.0) return 0.0
        val fraction = alongMetres / trajectory.carryMetres
        if (fraction > 1.0) return -1.0
        // Parabola through the launch point and the landing point, with the
        // apex set by the launch angle. Close enough for "could he have got a
        // hand to it".
        val apex = trajectory.carryMetres * kotlin.math.tan(trajectory.elevationDegrees * PI / 180.0) / 4.0
        return 4.0 * apex * fraction * (1.0 - fraction) + BAT_CONTACT_HEIGHT_M * (1.0 - fraction)
    }

    private fun catchProbability(context: DeliveryContext, skill: Double, difficulty: Double): Double {
        val tuning = context.tuning.outcome
        return logistic(
            tuning.catchIntercept +
                tuning.catchSkillWeight * (skill - 0.5) -
                tuning.catchDifficultyWeight * difficulty * context.tuning.knobs.catchDifficultyScale -
                tuning.catchPressureWeight * context.pressure,
        ).coerceIn(0.005, 0.995)
    }

    // --- Running ------------------------------------------------------------

    private data class RunResult(
        val runs: Int,
        val crossed: Boolean,
        val runOutVictim: com.cricketcareer.engine.model.player.PlayerId?,
        /** Whether the stumps were hit by the throw itself. */
        val directHit: Boolean = false,
    )

    /**
     * Which batter is out, when a run out happens on the [attemptedRun]th run.
     *
     * The man running *to* the end the ball is thrown to. Two facts decide it:
     *
     *  - **Which end the throw goes to.** A ball fielded in front of square is
     *    nearer the bowler's end; one behind square is nearer the keeper's. A
     *    fielder throws to the end he can reach, not the one he would prefer.
     *  - **Whose turn it is at that end.** The two batters swap ends on every
     *    completed run, so on an odd-numbered attempt the striker is running to
     *    the bowler's end and on an even one he is coming back.
     *
     * Before this the striker was out every single time, which is wrong about
     * half the time and — for a game about one cricketer — wrong in the way
     * that matters most: he could never be run out backing up.
     */
    private fun runOutVictim(
        context: DeliveryContext,
        trajectory: Trajectory,
        attemptedRun: Int,
    ): com.cricketcareer.engine.model.player.PlayerId {
        val azimuth = ((trajectory.azimuthDegrees % 360.0) + 360.0) % 360.0
        val throwToBowlersEnd = azimuth < 90.0 || azimuth > 270.0
        val strikerIsRunningToBowlersEnd = attemptedRun % 2 == 1
        return if (throwToBowlersEnd == strikerIsRunningToBowlersEnd) {
            context.striker.id
        } else {
            context.nonStriker.id
        }
    }

    private fun runsFor(
        context: DeliveryContext,
        trajectory: Trajectory,
        fielding: FieldingResolution,
        rng: SimRandom,
    ): RunResult {
        if (fielding.clearedBoundary) return RunResult(6, crossed = false, runOutVictim = null)
        if (fielding.reachedBoundary) return RunResult(4, crossed = false, runOutVictim = null)

        val tuning = context.tuning.outcome

        // A dropped catch is dead at the fielder's feet: one, if that.
        if (fielding.dropped) {
            return RunResult(if (rng.chance(0.45)) 1 else 0, crossed = rng.chance(0.45), runOutVictim = null)
        }

        // How long before the ball is under control and on its way back.
        val ballTime = ballTravelTime(trajectory, fielding.fieldedAtMetres)
        val chaseTime = fielding.nearestFielder?.let { fielder ->
            val separation = FieldGaps.angularSeparation(trajectory.azimuthDegrees, fielder.position.azimuthDegrees)
            val gap = distanceBetween(fielder.position.distanceMetres, separation, fielding.fieldedAtMetres)
            val speed = tuning.fielderSpeedMetresPerSecond *
                (0.85 + 0.3 * context.fielderSkill(fielder.player, Attribute.SPEED))
            (gap / speed - ballTime).coerceAtLeast(0.0)
        } ?: 2.0
        // Any ball that comes to rest inside the ring is being attacked: the
        // infielder has been running at it since it left the bat and gathers and
        // throws in one motion. The batters can see that, and a forward
        // defensive that trickles fifteen metres into the covers is a dot ball,
        // not the arithmetically available single the model used to give them.
        // Restricting this to balls he cut off on their line missed exactly the
        // deliveries it matters most for.
        val attacking = fielding.nearestFielder != null &&
            fielding.fieldedAtMetres < Geometry.INNER_RING_M &&
            fielding.fielderLateralMetres < tuning.attackingReachMetres
        val gather = when {
            fielding.misfielded -> tuning.fielderReactionSeconds + 1.10
            attacking -> tuning.attackingPickUpSeconds
            else -> tuning.fielderReactionSeconds + 0.30
        }
        val throwSpeed = fielding.nearestFielder?.let { fielder ->
            tuning.throwSpeedSlowest + (tuning.throwSpeedFastest - tuning.throwSpeedSlowest) *
                context.fielderSkill(fielder.player, Attribute.THROW_ARM)
        } ?: tuning.throwSpeedSlowest
        // Running in at the ball closes some of the throwing distance too.
        val throwDistance = if (attacking) fielding.fieldedAtMetres * 0.88 else fielding.fieldedAtMetres
        val throwTime = throwDistance / throwSpeed
        val availableTime = ballTime + chaseTime + gather + throwTime

        val speed = tuning.runSpeedSlowest +
            (tuning.runSpeedFastest - tuning.runSpeedSlowest) * context.strikerSkill(Attribute.SPEED)
        val judgement = context.strikerSkill(Attribute.RUNNING_BETWEEN_WICKETS)
        val runDistance = Geometry.PITCH_LENGTH_M - 2 * Geometry.CREASE_M

        // How wrong each batter's reading of the margin is likely to be.
        // Everything the pair decides below is decided on their judged margins;
        // everything the fielding side does is decided on the real one.
        val partnerJudgement = context.nonStrikerSkill(Attribute.RUNNING_BETWEEN_WICKETS)
        fun sigmaFor(j: Double) = tuning.runJudgementSigmaWorst -
            (tuning.runJudgementSigmaWorst - tuning.runJudgementSigmaBest) * j
        val judgementSigma = sigmaFor(judgement)
        val partnerSigma = sigmaFor(partnerJudgement)

        var runs = 0
        var time = 0.0
        while (runs < 3) {
            val legTime = runDistance / speed + if (runs > 0) tuning.turnCostSeconds else 0.0
            val after = time + legTime
            val margin = availableTime - after
            // What each of them *thinks* the margin is. A batter calls off the
            // ball, not off a stopwatch: he cannot see the pick-up, the turn or
            // the strength of the arm, and the call has to be made before any of
            // the three has happened. Deciding on the true margin made every run
            // out a dice roll over a run the batter could see he was losing,
            // which is not how a run out happens.
            //
            // The striker calls; the man at the other end can send him back.
            // That second read is a *veto on an obvious loss*, not a second
            // opinion on a close one — "no" is shouted at a run that plainly
            // is not there, and a tight single the striker fancies is run. So
            // the partner only stops it when his own read is clearly red.
            //
            // Both of them judging every single from scratch and taking the
            // more cautious view makes the pair systematically pessimistic —
            // the minimum of two unbiased reads is biased low — and it showed:
            // the dot rate rose four points in every format at once. Without
            // the veto at all, one bad read sent them on a run neither could
            // make, and run outs reached nineteen per cent of dismissals.
            val judged = margin + rng.nextGaussian() * judgementSigma
            val partnerJudged = margin + rng.nextGaussian() * partnerSigma
            // A good runner goes for a tight one; a poor one hesitates, or goes
            // when he should not.
            val willingness = tuning.riskyRunMarginSeconds * (1.6 - judgement)
            val partnerWillingness = tuning.riskyRunMarginSeconds * (1.6 - partnerJudgement)
            if (judged < -willingness || partnerJudged < -partnerWillingness) break
            val formatBonus = context.tuning.formatIntent.forFormat(context.format).singleAppetiteBonus
            val comfortThreshold = tuning.comfortableRunMarginSeconds -
                tuning.comfortFormatWeight * formatBonus
            if (judged < comfortThreshold) {
                // Available, but not comfortable. A push to a close fielder is
                // arithmetically a single and is refused nearly every time; a
                // slightly tight one is usually taken. Treating both the same
                // produced a game with too few dots and too few run outs at once.
                // Nobody scampers a tight one at a fielder already moving onto
                // the ball with his arm cocked.
                val attackedPenalty = if (attacking) 0.55 else 0.0
                val appetite = tuning.tightSingleAppetite + 0.35 * judgement +
                    0.20 * context.battingIntent + formatBonus - attackedPenalty
                if (!rng.chance(appetite.coerceIn(0.03, 0.97))) break
            }
            if (margin < 0.0) {
                val danger = (-margin / tuning.runOutCertaintySeconds).coerceIn(0.0, 1.0)
                val throwArm = fielding.nearestFielder
                    ?.let { context.fielderSkill(it.player, Attribute.THROW_ARM) } ?: 0.5
                if (rng.chance(danger * (tuning.directHitBase + 0.35 * throwArm))) {
                    // A direct hit from thirty metres and a relay to the keeper
                    // are different pieces of cricket, and a scorecard names a
                    // different fielder for each.
                    val closeEnough = (1.0 - fielding.fieldedAtMetres / tuning.directHitRangeMetres)
                        .coerceIn(0.0, 1.0)
                    val direct = rng.chance((tuning.directHitShare * (0.5 + closeEnough)).coerceIn(0.0, 1.0))
                    return RunResult(
                        runs = runs,
                        crossed = runs % 2 == 1,
                        runOutVictim = runOutVictim(context, trajectory, runs + 1),
                        directHit = direct,
                    )
                }
            }
            runs++
            time = after
        }

        return RunResult(runs, crossed = runs % 2 == 1, runOutVictim = null)
    }


    /** How long the ball itself takes to reach where it was gathered. */
    private fun ballTravelTime(trajectory: Trajectory, distanceMetres: Double): Double {
        if (trajectory.isAerial) {
            val flight = timeOfFlight(trajectory)
            val rolled = (distanceMetres - trajectory.carryMetres).coerceAtLeast(0.0)
            val rollSpeed = (trajectory.exitSpeedMetresPerSecond * 0.45).coerceAtLeast(3.0)
            return flight + rolled / rollSpeed * 1.6
        }
        // Decelerating along the ground. Over the first thirty metres a firmly
        // struck ball has only lost a quarter of its pace, so three-quarters of
        // the exit speed is the right average there.
        val average = (trajectory.exitSpeedMetresPerSecond * 0.75).coerceAtLeast(3.0)
        return distanceMetres / average
    }

    private fun logistic(x: Double): Double = 1.0 / (1.0 + exp(-x))

    /** Below this catch probability, a chance is not a "genuine chance" for drop-rate purposes. */
    const val GENUINE_CHANCE_THRESHOLD: Double = 0.15

    private const val GRAVITY = 9.81

    /** Tuned so 35 m/s at 35 degrees carries about 75 m — the distance a six needs. */
    private const val DRAG_RANGE_COEFFICIENT = 0.016

    /** Deceleration of a ball rolling on a firm outfield, m/s². */
    private const val OUTFIELD_DECELERATION = 3.1

    /** Where the bat meets the ball, used as the start height of a struck ball's arc. */
    private const val BAT_CONTACT_HEIGHT_M = 0.75

    private fun Double.mod(m: Double): Double {
        val r = this % m
        return if (r < 0) r + m else r
    }
}
