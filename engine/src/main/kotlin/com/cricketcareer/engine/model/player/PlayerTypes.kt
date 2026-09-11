package com.cricketcareer.engine.model.player

import kotlinx.serialization.Serializable

/** Which hand a batter holds the bat with. */
@Serializable
enum class BattingHand(val displayName: String, val short: String) {
    RIGHT("Right-hand bat", "RHB"),
    LEFT("Left-hand bat", "LHB"),
    ;

    val isLeft: Boolean get() = this == LEFT
}

/**
 * Bowling style, in the standard shorthand.
 *
 * [turnsAwayFromRightHander] is what the spin model actually needs: a leg break
 * and a left-arm orthodox both turn away from a right-hander, and an off break
 * and a left-arm wrist-spinner's stock ball both come into him. Storing the
 * *effect* rather than the wrist means the engine never has to reason about
 * arm and wrist together to work out which way the ball goes.
 *
 * [typicalPaceKph] is the centre of the pace range for the style, used by the
 * generator and as the default target pace for a stock ball.
 *
 * [wristSpin] separates wrist spin from finger spin, which is what actually
 * decides a spinner's repertoire: a googly belongs to a wrist-spinner and a
 * doosra or carrom ball to a finger-spinner. Turn direction does not decide it
 * — a left-arm wrist-spinner turns the ball into a right-hander and still bowls
 * a googly.
 */
@Serializable
enum class BowlingStyle(
    val displayName: String,
    val short: String,
    val kind: BowlingKind,
    val rightArm: Boolean,
    val typicalPaceKph: Double,
    val turnsAwayFromRightHander: Boolean?,
    val wristSpin: Boolean = false,
) {
    RIGHT_FAST("Right-arm fast", "RF", BowlingKind.PACE, true, 145.0, null),
    RIGHT_FAST_MEDIUM("Right-arm fast-medium", "RFM", BowlingKind.PACE, true, 134.0, null),
    RIGHT_MEDIUM_FAST("Right-arm medium-fast", "RMF", BowlingKind.PACE, true, 127.0, null),
    RIGHT_MEDIUM("Right-arm medium", "RM", BowlingKind.PACE, true, 119.0, null),
    LEFT_FAST("Left-arm fast", "LF", BowlingKind.PACE, false, 144.0, null),
    LEFT_FAST_MEDIUM("Left-arm fast-medium", "LFM", BowlingKind.PACE, false, 133.0, null),
    LEFT_MEDIUM("Left-arm medium", "LM", BowlingKind.PACE, false, 118.0, null),

    OFF_BREAK("Right-arm off break", "OB", BowlingKind.SPIN, true, 86.0, false),
    LEG_BREAK("Right-arm leg break", "LB", BowlingKind.SPIN, true, 83.0, true, wristSpin = true),
    SLOW_LEFT_ARM_ORTHODOX("Slow left-arm orthodox", "SLA", BowlingKind.SPIN, false, 85.0, true),
    SLOW_LEFT_ARM_CHINAMAN("Slow left-arm wrist spin", "SLC", BowlingKind.SPIN, false, 82.0, false, wristSpin = true),

    /** A pure batter or keeper who does not bowl. */
    NONE("Does not bowl", "-", BowlingKind.NONE, true, 0.0, null),
    ;

    val isPace: Boolean get() = kind == BowlingKind.PACE
    val isSpin: Boolean get() = kind == BowlingKind.SPIN
    val isFingerSpin: Boolean get() = isSpin && !wristSpin
    val bowls: Boolean get() = kind != BowlingKind.NONE

    companion object {
        val PACE_STYLES: List<BowlingStyle> = entries.filter { it.isPace }
        val SPIN_STYLES: List<BowlingStyle> = entries.filter { it.isSpin }
    }
}

@Serializable
enum class BowlingKind { PACE, SPIN, NONE }

/**
 * What a player is picked to do.
 *
 * The role drives generation (which attribute groups get the points), batting
 * order, and how the selection model compares him to a rival — a finisher and
 * an opener are not competing for the same slot even at the same average.
 */
@Serializable
enum class PlayerRole(
    val displayName: String,
    val primary: RoleDiscipline,
    val keeps: Boolean,
    /** Typical batting position, used as the generator's and the AI's starting point. */
    val typicalBattingPosition: Int,
) {
    OPENING_BAT("Opening batter", RoleDiscipline.BATTING, keeps = false, typicalBattingPosition = 1),
    TOP_ORDER_BAT("Top-order batter", RoleDiscipline.BATTING, keeps = false, typicalBattingPosition = 3),
    MIDDLE_ORDER_BAT("Middle-order batter", RoleDiscipline.BATTING, keeps = false, typicalBattingPosition = 5),
    FINISHER("Finisher", RoleDiscipline.BATTING, keeps = false, typicalBattingPosition = 6),
    WICKETKEEPER_BAT("Wicketkeeper-batter", RoleDiscipline.BATTING, keeps = true, typicalBattingPosition = 6),
    SEAM_ALLROUNDER("Seam-bowling all-rounder", RoleDiscipline.ALLROUNDER, keeps = false, typicalBattingPosition = 7),
    SPIN_ALLROUNDER("Spin-bowling all-rounder", RoleDiscipline.ALLROUNDER, keeps = false, typicalBattingPosition = 7),
    FAST_BOWLER("Fast bowler", RoleDiscipline.BOWLING, keeps = false, typicalBattingPosition = 10),
    SPINNER("Spinner", RoleDiscipline.BOWLING, keeps = false, typicalBattingPosition = 9),
    ;

    /** Batting roles may still bowl a few overs; this marks a genuine bowling option. */
    val bowls: Boolean get() = primary != RoleDiscipline.BATTING
}

@Serializable
enum class RoleDiscipline { BATTING, BOWLING, ALLROUNDER }
