package com.cricketcareer.engine.config

/**
 * How often rain stops a limited-overs match, and for how long.
 *
 * Rain is not a nuisance to be smoothed away. A shortened chase is one of the
 * most distinctive things that happens in one-day cricket, it changes how both
 * sides play, and a career without a single rain-ruined match in it is missing
 * something a player would remember.
 *
 * The shape these numbers aim at: most matches lose nothing, a few lose a
 * handful of overs, and a small number are wrecked. Not a flat draw — that is
 * not how rain falls on a cricket match.
 *
 * See docs/SIMULATION_MODEL.md §12.
 */
data class RainTuning(
    /**
     * Chance of a stoppage regardless of conditions — the shower nobody saw
     * coming.
     *
     * Small. Rain out of a clear sky is a real thing and a rare one, and this
     * is the only term that survives perfect weather.
     */
    val chanceBase: Double = 0.03,

    /**
     * Chance from the conditions, at maximum cloud and humidity.
     *
     * Read off the same weather the swing model reads, so an overcast, muggy
     * day is both the day the ball hoops and the day the covers come on. Two
     * separate draws would let it swing prodigiously under a clear sky.
     *
     * Applied to the **square** of how wet the day is, because rain needs cloud
     * and humidity *together*: a muggy clear day and an overcast dry one are
     * both much safer than the average of the two suggests. With the reference
     * weather this puts about one match in eleven under the covers, which is
     * roughly how often a one-day game is interrupted; a genuinely filthy day
     * gets to better than one in three.
     */
    val chanceWeather: Double = 0.40,

    /**
     * Chance that a match which is rained on is washed out without a ball.
     *
     * Drawn separately rather than falling out of a long-enough stoppage,
     * because an abandoned match is not a very bad interruption — it is the
     * umpires looking at the forecast in the morning and calling it off. Left
     * to the severity draw it would need two uniforms both close to one, and a
     * total washout would essentially never happen.
     */
    val washoutChance: Double = 0.18,

    /** Chance that a match which is interrupted is interrupted a second time. */
    val secondStoppageChance: Double = 0.28,

    /**
     * The most of an innings a single stoppage can take, as a share.
     *
     * Not 1.0: a stoppage that wipes out everything remaining is the abandoned
     * case, and it is reached by two stoppages or by one late one rather than
     * by a single draw at the top of the innings washing out a whole match.
     */
    val maximumShareLost: Double = 0.85,

    /**
     * Shape of how much is lost once it does rain.
     *
     * The share lost is the product of two uniform draws, which piles the mass
     * near zero: the usual interruption costs a few overs and the washout is
     * the tail. One uniform draw would make "half the innings gone" as likely
     * as "two overs off", which is not a thing that happens.
     */
    val severityDraws: Int = 2,
) {
    init {
        require(chanceBase in 0.0..1.0) { "chanceBase $chanceBase" }
        require(chanceWeather in 0.0..1.0) { "chanceWeather $chanceWeather" }
        require(chanceBase + chanceWeather <= 1.0) { "rain cannot be more than certain" }
        require(washoutChance in 0.0..1.0) { "washoutChance $washoutChance" }
        require(secondStoppageChance in 0.0..1.0) { "secondStoppageChance $secondStoppageChance" }
        require(maximumShareLost in 0.0..1.0) { "maximumShareLost $maximumShareLost" }
        require(severityDraws >= 1) { "severityDraws $severityDraws" }
    }
}
