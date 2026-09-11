package com.cricketcareer.engine.match.delivery

import kotlinx.serialization.Serializable

/**
 * The pitch, in metres, and the bands the engine reasons in.
 *
 * Frame (docs/SIMULATION_MODEL.md §1): origin at the centre of the striker's
 * stumps at ground level; `y` down the pitch towards the bowler; `x` positive
 * towards the striker's **off side**; `z` up.
 *
 * Because `x` is defined relative to the striker's off side the whole engine is
 * handedness-agnostic — a left-hander is simulated identically and the frame is
 * mirrored back to absolute ground coordinates only for field placement and the
 * wagon wheel. One implementation, no duplicated left-hander logic.
 */
object Geometry {
    /** Stump to stump. 22 yards. */
    const val PITCH_LENGTH_M: Double = 20.12

    /** Popping crease ahead of the stumps. */
    const val CREASE_M: Double = 1.22

    /** Outside to outside of the three stumps. */
    const val STUMP_WIDTH_M: Double = 0.2286

    /** Half the stump width: the |x| within which a ball is on the stumps. */
    const val STUMP_HALF_WIDTH_M: Double = STUMP_WIDTH_M / 2.0

    const val STUMP_HEIGHT_M: Double = 0.711

    const val BALL_RADIUS_M: Double = 0.036

    /** Return crease, either side of middle stump. */
    const val RETURN_CREASE_M: Double = 1.32

    /** White-ball off-side wide guide, from middle stump. */
    const val WHITE_BALL_WIDE_LINE_M: Double = 0.89

    /**
     * Off-side wide line in multi-day cricket, where the umpire's judgement is
     * far more generous — a ball outside off that the batter could have played
     * is not called.
     */
    const val RED_BALL_WIDE_LINE_M: Double = 1.30

    /**
     * Any ball passing this far down the leg side is a wide in every format.
     *
     * The Law is simply "passing down the leg side of the striker", and umpires
     * call it just outside leg stump. The old value of -0.72 m was most of the
     * way to the return crease and meant leg-side wides never happened.
     */
    const val LEG_SIDE_WIDE_LINE_M: Double = -0.38

    /** 30-yard circle. */
    const val INNER_RING_M: Double = 27.43

    /** Where the ball leaves the bowler's hand, measured down the pitch. */
    const val RELEASE_Y_M: Double = 17.7

    /** Release height for a bowler of average height and action. */
    const val RELEASE_HEIGHT_M: Double = 2.05

    /** Waist height for the above-waist full-toss no-ball. */
    const val WAIST_HEIGHT_M: Double = 1.05

    /** Above this at the stumps and a bouncer is called a wide over the batter's head. */
    const val HEAD_HEIGHT_M: Double = 1.95

    /**
     * Where the batter commits to a shot, as a fraction of the flight.
     *
     * Around 0.38 s before impact for a quick bowler. Only deviation *after*
     * this point beats him, which is the entire mechanism by which late swing
     * is more dangerous than early swing.
     */
    const val DECISION_POINT_FRACTION: Double = 0.55
}

/** Where the ball pitched, in the pace-bowling vocabulary. */
@Serializable
enum class LengthBand(val displayName: String, val minMetres: Double, val maxMetres: Double) {
    FULL_TOSS("full toss", -5.0, 0.0),
    YORKER("yorker", 0.0, 1.0),
    HALF_VOLLEY("half-volley", 1.0, 4.0),
    FULLISH("fullish", 4.0, 6.0),
    GOOD("good length", 6.0, 8.0),
    BACK_OF_LENGTH("back of a length", 8.0, 10.0),
    SHORT("short", 10.0, 14.0),
    LONG_HOP("long hop", 14.0, 25.0),
    ;

    val centreMetres: Double get() = (minMetres + maxMetres) / 2.0

    companion object {
        /**
         * Band for a pitch point.
         *
         * Spin uses the same axis with the bands shifted [SPIN_SHIFT_M] fuller —
         * a spinner's good length is roughly 4-6 m — because a slower ball with
         * more dip has to land closer to be equally awkward. The engine does not
         * pretend one set of names means the same thing for both.
         */
        const val SPIN_SHIFT_M: Double = 2.0

        fun of(pitchPointMetres: Double, spin: Boolean): LengthBand {
            val adjusted = if (spin) pitchPointMetres + SPIN_SHIFT_M else pitchPointMetres
            return entries.firstOrNull { adjusted < it.maxMetres } ?: LONG_HOP
        }
    }
}

/** Where the ball passed the stumps, laterally. */
@Serializable
enum class LineBand(val displayName: String, val minMetres: Double, val maxMetres: Double) {
    DOWN_LEG("down the leg side", -3.0, -0.35),
    LEG_STUMP("leg stump", -0.35, -0.12),
    MIDDLE("middle stump", -0.12, 0.12),
    OFF_STUMP("off stump", 0.12, 0.25),
    FOURTH_STUMP("fourth stump", 0.25, 0.42),
    CHANNEL("the channel", 0.42, 0.62),
    WIDE_OUTSIDE_OFF("wide outside off", 0.62, 3.0),
    ;

    val centreMetres: Double get() = (minMetres + maxMetres) / 2.0

    companion object {
        fun of(lineMetres: Double): LineBand = entries.firstOrNull { lineMetres < it.maxMetres } ?: WIDE_OUTSIDE_OFF
    }
}

/**
 * What the bowler was trying to bowl.
 *
 * [executionDifficulty] is the `D` multiplier on execution error from
 * docs/SIMULATION_MODEL.md §5: a yorker missed is a full toss, which is why it
 * costs 1.35 to attempt one.
 */
@Serializable
enum class DeliveryType(
    val displayName: String,
    val executionDifficulty: Double,
    val forPace: Boolean,
    val forSpin: Boolean,
    /** How hard it is to read out of the hand, before the batter's own skill. */
    val deception: Double = 0.0,
    /** Multiplier on the bowler's target pace. */
    val paceFactor: Double = 1.0,
) {
    STOCK("stock ball", 1.00, forPace = true, forSpin = true),
    BOUNCER("bouncer", 1.10, forPace = true, forSpin = false, paceFactor = 1.02),
    YORKER("yorker", 1.35, forPace = true, forSpin = false, paceFactor = 1.01),
    SLOWER_BALL("slower ball", 1.25, forPace = true, forSpin = false, deception = 0.45, paceFactor = 0.78),
    CUTTER("cutter", 1.15, forPace = true, forSpin = false, deception = 0.25, paceFactor = 0.90),

    TOP_SPINNER("top spinner", 1.10, forPace = false, forSpin = true, deception = 0.20),
    GOOGLY("googly", 1.20, forPace = false, forSpin = true, deception = 0.70),
    DOOSRA("doosra", 1.40, forPace = false, forSpin = true, deception = 0.75),
    ARM_BALL("arm ball", 1.05, forPace = false, forSpin = true, deception = 0.50),
    CARROM("carrom ball", 1.45, forPace = false, forSpin = true, deception = 0.65),
    FLIGHTED("flighted ball", 1.05, forPace = false, forSpin = true, paceFactor = 0.88),
    QUICKER_BALL("quicker ball", 1.10, forPace = false, forSpin = true, deception = 0.35, paceFactor = 1.15),
    ;

    companion object {
        val PACE_TYPES: List<DeliveryType> = entries.filter { it.forPace }
        val SPIN_TYPES: List<DeliveryType> = entries.filter { it.forSpin }
    }
}
