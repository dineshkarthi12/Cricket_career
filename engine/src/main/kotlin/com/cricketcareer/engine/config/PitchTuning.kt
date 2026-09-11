package com.cricketcareer.engine.config

/**
 * How fast a pitch changes, per session of play.
 *
 * All rates are "per session" rather than per over, because a pitch does not
 * change inside an over and evolving it per ball would cost the per-ball budget
 * for nothing.
 */
data class PitchTuning(
    /** Overs in a full session. Used to scale wear when a session is cut short. */
    val sessionOvers: Double = 30.0,

    /** Moisture lost to a full session of sun and wind. */
    val dryingPerSession: Double = 0.17,

    /** How much of a session's uncovered rain soaks back into the square. */
    val rainSoaking: Double = 0.55,

    /** Grass worn off by a session of play. */
    val grassWearPerSession: Double = 0.14,

    /**
     * Cracking per session on neutral soil, and how sharply it depends on the
     * surface being dry. The exponent is what makes cracks a day-four
     * phenomenon rather than a gradual slope from the first morning.
     */
    val crackGrowthPerSession: Double = 0.075,
    val crackDrynessExponent: Double = 1.8,

    /** Footmarks and ball-scuffing per session. */
    val abrasionPerSession: Double = 0.085,

    /** Overall breakdown per session. */
    val deteriorationPerSession: Double = 0.075,

    /** Grip the spinner gains, and the seamer loses, per session. */
    val turnGainPerSession: Double = 0.085,
    val seamLossPerSession: Double = 0.075,

    /** A surface loosening under foot. */
    val hardnessLossPerSession: Double = 0.035,
    val paceLossPerSession: Double = 0.045,
    val bounceLossPerSession: Double = 0.040,

    /**
     * Consistency lost per session as the surface breaks up.
     *
     * This is the number that makes batting last on a worn pitch genuinely
     * different rather than just slightly worse: it feeds the variable-bounce
     * noise, and by day five a good length can shoot or climb.
     */
    val evennessLossPerSession: Double = 0.075,

    /** Session-to-session variation, so two identical matches do not wear identically. */
    val sessionJitter: Double = 0.04,
) {
    companion object {
        val DEFAULT: PitchTuning = PitchTuning()
    }
}
