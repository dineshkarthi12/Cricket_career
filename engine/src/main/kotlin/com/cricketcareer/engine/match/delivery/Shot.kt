package com.cricketcareer.engine.match.delivery

import com.cricketcareer.engine.model.player.Attribute
import kotlinx.serialization.Serializable

/** Which foot the shot is played off, deciding whether frontfoot or backfoot play applies. */
@Serializable
enum class FootMovement { FRONT, BACK, NONE }

/**
 * A stroke, and what it is for.
 *
 * The ideal length/line/height is where this shot *wants* the ball; Stage 5
 * measures the ball that actually arrived against it. [toleranceScale] is how
 * forgiving the shot is — a forward defensive has a wide margin, a ramp has
 * almost none, which is why one goes wrong far more often than the other at the
 * same level of skill.
 *
 * Azimuth convention (batter-relative, docs/SIMULATION_MODEL.md §1): 0° straight
 * past the bowler, 90° square on the off side, 180° behind the keeper, 270°
 * square leg. Mirrored for left-handers only when placing fielders on the
 * actual ground.
 */
@Serializable
enum class Shot(
    val displayName: String,
    val idealLengthMetres: Double,
    val idealLineMetres: Double,
    val idealHeightMetres: Double,
    val toleranceScale: Double,
    val azimuthDegrees: Double,
    val elevationDegrees: Double,
    /** How much of the batter's power reaches the ball. */
    val powerFactor: Double,
    val foot: FootMovement,
    /** 0 is a pure defensive stroke, 1 is a maximum-risk swing. */
    val risk: Double,
    /** The attribute that most decides whether this shot comes off. */
    val keyAttribute: Attribute,
    val makesContact: Boolean = true,
    /**
     * Played with a horizontal bat.
     *
     * Matters because a leading edge is a *face-turning* fault: it comes from
     * playing across the line of a ball that is fuller than expected. Getting
     * the length wrong to a straight-batted drive produces a mistimed shot off
     * the middle, not a leading edge.
     */
    val crossBat: Boolean = false,
) {
    // A leave has a TIGHT tolerance, not a wide one. Tolerance here is how well
    // the stroke suits the ball, and a leave suits exactly one kind of ball: one
    // going past off stump. Giving it a forgiving scale made it fit everything
    // and batters left a third of all deliveries in a Twenty20.
    LEAVE("leave", 7.0, 0.58, 0.80, 0.80, 0.0, 0.0, 0.0, FootMovement.NONE, 0.0, Attribute.PATIENCE, makesContact = false),

    DEFEND_FRONT("forward defensive", 5.0, 0.10, 0.45, 1.15, 10.0, 1.0, 0.12, FootMovement.FRONT, 0.02, Attribute.TECHNIQUE),
    DEFEND_BACK("back-foot defensive", 9.2, 0.10, 0.80, 1.12, 20.0, 1.0, 0.12, FootMovement.BACK, 0.03, Attribute.TECHNIQUE),
    BLOCK_AND_RUN("push into the off side", 6.5, 0.25, 0.55, 1.02, 75.0, 2.0, 0.22, FootMovement.FRONT, 0.10, Attribute.STRIKE_ROTATION),

    DRIVE_STRAIGHT("straight drive", 3.0, 0.05, 0.35, 0.92, 2.0, 5.0, 0.88, FootMovement.FRONT, 0.30, Attribute.FRONTFOOT_PLAY),
    DRIVE_COVER("cover drive", 3.2, 0.36, 0.40, 0.88, 48.0, 4.0, 0.86, FootMovement.FRONT, 0.34, Attribute.FRONTFOOT_PLAY),
    DRIVE_ON("on drive", 3.0, -0.18, 0.38, 0.82, 332.0, 4.0, 0.84, FootMovement.FRONT, 0.36, Attribute.FRONTFOOT_PLAY),

    // The good-length band needs productive answers or a batter has nothing to
    // play at the most common delivery in cricket but a block or a leave. These
    // three are how runs are actually made off a length: punched off the back
    // foot, worked off the hip, or hit down the ground.
    BACK_FOOT_PUNCH("back-foot punch", 8.6, 0.30, 0.85, 0.95, 46.0, 4.0, 0.74, FootMovement.BACK, 0.26, Attribute.BACKFOOT_PLAY),
    WORK_OFF_HIP("work off the hip", 6.8, -0.14, 0.65, 1.00, 300.0, 4.0, 0.56, FootMovement.FRONT, 0.20, Attribute.STRIKE_ROTATION),
    HIT_DOWN_THE_GROUND("hit down the ground", 6.4, 0.06, 0.60, 0.80, 6.0, 25.0, 1.06, FootMovement.FRONT, 0.58, Attribute.RANGE_HITTING),

    CUT("cut", 10.6, 0.52, 0.90, 0.90, 100.0, 4.0, 0.80, FootMovement.BACK, 0.34, Attribute.BACKFOOT_PLAY, crossBat = true),
    LATE_CUT("late cut", 9.8, 0.50, 0.75, 0.72, 136.0, 3.0, 0.48, FootMovement.BACK, 0.40, Attribute.BACKFOOT_PLAY, crossBat = true),

    PULL("pull", 11.6, 0.02, 1.05, 0.86, 286.0, 11.0, 0.94, FootMovement.BACK, 0.40, Attribute.SHORT_BALL_PLAY, crossBat = true),
    // Genuinely hard and genuinely rare: a hook is played to a ball climbing at
    // the throat, off the back foot, into the area behind square where the
    // catchers are. Anything more forgiving and batters hook every short ball
    // instead of pulling it.
    HOOK("hook", 13.2, -0.05, 1.50, 0.46, 238.0, 24.0, 0.90, FootMovement.BACK, 0.72, Attribute.SHORT_BALL_PLAY, crossBat = true),

    FLICK("flick off the pads", 3.6, -0.22, 0.42, 0.90, 316.0, 4.0, 0.66, FootMovement.FRONT, 0.24, Attribute.STRIKE_ROTATION),
    GLANCE("glance", 6.4, -0.24, 0.60, 0.95, 202.0, 2.0, 0.34, FootMovement.BACK, 0.18, Attribute.STRIKE_ROTATION),

    SWEEP("sweep", 4.6, -0.04, 0.40, 0.78, 250.0, 7.0, 0.62, FootMovement.FRONT, 0.42, Attribute.SPIN_PLAY, crossBat = true),
    SLOG_SWEEP("slog sweep", 4.8, -0.02, 0.45, 0.66, 292.0, 27.0, 1.08, FootMovement.FRONT, 0.66, Attribute.RANGE_HITTING, crossBat = true),
    REVERSE_SWEEP("reverse sweep", 4.4, 0.02, 0.42, 0.52, 116.0, 8.0, 0.58, FootMovement.FRONT, 0.70, Attribute.RANGE_HITTING, crossBat = true),

    LOFT_OFF("loft over the off side", 5.4, 0.28, 0.52, 0.78, 22.0, 29.0, 1.10, FootMovement.FRONT, 0.62, Attribute.RANGE_HITTING),
    LOFT_LEG("loft over the leg side", 5.6, -0.12, 0.52, 0.78, 338.0, 29.0, 1.10, FootMovement.FRONT, 0.62, Attribute.RANGE_HITTING),

    RAMP("ramp", 8.6, 0.30, 1.00, 0.46, 168.0, 34.0, 0.56, FootMovement.BACK, 0.78, Attribute.RANGE_HITTING),
    ;

    /** Shots that put the ball in the air by design. An edge can loft any of them. */
    val isAerial: Boolean get() = elevationDegrees >= 20.0

    companion object {
        val ALL: List<Shot> = entries.toList()

        /** Everything except the leave, for the cases where a stroke must be played. */
        val STROKES: List<Shot> = entries.filter { it.makesContact }
    }
}
