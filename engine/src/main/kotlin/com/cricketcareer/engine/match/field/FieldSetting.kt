package com.cricketcareer.engine.match.field

import com.cricketcareer.engine.match.delivery.Geometry
import com.cricketcareer.engine.model.player.PlayerId
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A fielding position, as a bearing and a distance from the striker.
 *
 * Stored in the same batter-relative frame as a shot's azimuth (0° straight past
 * the bowler, 90° square on the off side, 180° behind the keeper, 270° square
 * leg), so "did anyone get near it?" is a comparison of two numbers in one
 * frame rather than a coordinate conversion per ball.
 *
 * [catchesWithReflexes] marks the close catchers — slips, gully, short leg — who
 * have no time to judge the ball and so use `reflexes` rather than `catching`.
 */
@Serializable
enum class FieldPosition(
    val displayName: String,
    val azimuthDegrees: Double,
    val distanceMetres: Double,
    val catchesWithReflexes: Boolean = false,
    /**
     * How freely this fielder can move to the ball, relative to a fielder set
     * and balanced in the ring.
     *
     * The bowler is finishing his action off-balance and going the wrong way:
     * he stops what comes to him and takes the occasional return catch, but he
     * does not cut off a drive travelling at 30 m/s. Giving him an ordinary
     * fielder's mobility took seven points off the boundary rate on its own.
     */
    val mobility: Double = 1.0,
) {
    /**
     * The bowler, in his follow-through.
     *
     * Never placed by the captain — he is running in, not standing anywhere —
     * but he is unquestionably a fielder, and leaving him out meant nothing
     * stopped a ball pushed back down the pitch. Every soft defensive shot
     * became a single.
     */
    BOWLER("the bowler", 0.0, 16.0, catchesWithReflexes = true, mobility = 0.32),

    WICKETKEEPER("the keeper", 180.0, 14.0, catchesWithReflexes = true, mobility = 0.85),
    FIRST_SLIP("first slip", 163.0, 14.5, catchesWithReflexes = true),
    SECOND_SLIP("second slip", 157.0, 15.0, catchesWithReflexes = true),
    THIRD_SLIP("third slip", 151.0, 15.5, catchesWithReflexes = true),
    FOURTH_SLIP("fourth slip", 145.0, 16.0, catchesWithReflexes = true),
    LEG_SLIP("leg slip", 197.0, 14.5, catchesWithReflexes = true),
    GULLY("gully", 129.0, 18.0, catchesWithReflexes = true),
    SHORT_LEG("short leg", 252.0, 7.0, catchesWithReflexes = true),
    SILLY_POINT("silly point", 84.0, 7.0, catchesWithReflexes = true),

    POINT("point", 96.0, 25.0),
    BACKWARD_POINT("backward point", 110.0, 26.0),
    COVER("cover", 56.0, 27.0),
    EXTRA_COVER("extra cover", 40.0, 29.0),
    MID_OFF("mid-off", 14.0, 29.0),
    MID_ON("mid-on", 346.0, 29.0),
    MIDWICKET("midwicket", 306.0, 27.0),
    SQUARE_LEG("square leg", 273.0, 26.0),
    SHORT_FINE_LEG("short fine leg", 224.0, 22.0),

    THIRD_MAN("third man", 144.0, 62.0),
    DEEP_BACKWARD_POINT("deep backward point", 112.0, 62.0),
    DEEP_POINT("deep point", 96.0, 63.0),
    DEEP_COVER("deep cover", 52.0, 64.0),
    LONG_OFF("long-off", 16.0, 68.0),
    LONG_ON("long-on", 344.0, 68.0),
    COW_CORNER("deep midwicket", 312.0, 65.0),
    DEEP_MIDWICKET("wide long-on", 300.0, 64.0),
    DEEP_SQUARE_LEG("deep square leg", 272.0, 62.0),
    DEEP_FINE_LEG("deep fine leg", 212.0, 62.0),
    ;

    /** Is this fielder outside the 30-yard circle? Decides powerplay legality. */
    val isOutsideRing: Boolean get() = distanceMetres > Geometry.INNER_RING_M

    /** Cartesian position in the batter-relative frame, metres. */
    val x: Double get() = distanceMetres * sin(azimuthDegrees * PI / 180.0)
    val y: Double get() = distanceMetres * cos(azimuthDegrees * PI / 180.0)
}

/** One fielder in one position. */
@Serializable
data class Fielder(val position: FieldPosition, val player: PlayerId)

/**
 * The eleven, placed.
 *
 * Field settings matter to the batter because Stage 4 reads them: lofting over
 * a vacant deep midwicket scores more than lofting to a man there, and a gap at
 * third man makes the steer worth playing. Without that, a field setting would
 * be decoration.
 */
@Serializable
data class FieldSetting(val fielders: List<Fielder>) {
    init {
        require(fielders.size == FIELDERS_ON_THE_PARK) {
            "a field setting needs $FIELDERS_ON_THE_PARK fielders including the keeper, got ${fielders.size}"
        }
        require(fielders.count { it.position == FieldPosition.WICKETKEEPER } == 1) {
            "exactly one keeper, please"
        }
        require(fielders.map { it.position }.distinct().size == fielders.size) {
            "two fielders in the same position"
        }
    }

    val outsideRing: Int get() = fielders.count { it.position.isOutsideRing }

    /** Whether this field is legal given the powerplay limit, if there is one. */
    fun isLegal(fieldersOutsideCircleLimit: Int?): Boolean =
        fieldersOutsideCircleLimit == null || outsideRing <= fieldersOutsideCircleLimit

    fun has(position: FieldPosition): Boolean = fielders.any { it.position == position }

    companion object {
        /** Ten in the field plus the bowler, who is not placed. */
        const val FIELDERS_ON_THE_PARK: Int = 10
    }
}
