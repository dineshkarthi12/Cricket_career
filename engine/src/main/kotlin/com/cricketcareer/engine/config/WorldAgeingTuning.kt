package com.cricketcareer.engine.config

/**
 * The rest of the world getting older.
 *
 * Without this, everyone a player competes with is the cricketer the seed file
 * froze: they never improve, never decline and never retire. A career against
 * them is harder at the top and easier at the bottom than it should be, and — the
 * part that really shows — a twenty-year career ends with the same men in the
 * same sides, which is not a world, it is a photograph.
 *
 * This is tier 3 of the world simulation (docs/CAREER_MODEL.md §11): no balls
 * are bowled, the year simply happens to everybody.
 */
data class WorldAgeingTuning(
    /**
     * A season's exposure for a squad member at his own rung.
     *
     * Below 1.0 because a squad is eighteen and an XI is eleven: the average
     * contracted player does not play every match, and development follows
     * minutes on the field rather than membership of a list.
     */
    val squadExposure: Double = 0.62,

    /**
     * Age at which retirement becomes a real possibility.
     *
     * Nobody retires before it. Above it the hazard climbs, and a player whose
     * game has gone goes sooner than one whose has not.
     */
    val retirementFromAge: Int = 31,

    /** Age by which everybody has gone, whatever their form. */
    val retirementByAge: Int = 43,

    /**
     * How steeply retirement becomes likely with age, per year past
     * [retirementFromAge].
     *
     * Raising it empties the top of the age range and speeds turnover, which
     * makes places easier to win and careers around the player shorter.
     */
    val retirementAgeHazard: Double = 0.055,

    /**
     * Extra hazard for a player who has fallen below the standard of his rung.
     *
     * The thing that separates a thirty-five-year-old still worth his place from
     * one who is being carried. Without it, retirement is a birthday rather than
     * a decision.
     */
    val retirementDeclineHazard: Double = 0.70,

    /**
     * Match sharpness a tier-3 cricketer carries out of a season he played.
     *
     * **They played a season; it simply was not simulated.** The career clock
     * decays sharpness on every day without a match, so running the world
     * through it left two thousand nine hundred professionals at the rust floor
     * all year - and then the career layer compared a player fresh off his own
     * season against a squad that looked, on paper, as though it had spent the
     * winter in bed. Call-ups were won on rust rather than on cricket, and the
     * promoted player was never picked once the new season levelled everybody
     * up again.
     *
     * Slightly under one because a squad is eighteen and an XI is eleven: the
     * average contracted player has not played every match.
     */
    val seasonEndSharpness: Double = 0.82,
) {
    init {
        require(seasonEndSharpness in 0.0..1.0) { "seasonEndSharpness $seasonEndSharpness" }
        require(squadExposure in 0.0..1.0) { "squadExposure $squadExposure" }
        require(retirementFromAge in 20..60) { "retirementFromAge $retirementFromAge" }
        require(retirementByAge > retirementFromAge) { "retirementByAge $retirementByAge" }
        require(retirementAgeHazard >= 0.0) { "retirementAgeHazard $retirementAgeHazard" }
        require(retirementDeclineHazard >= 0.0) { "retirementDeclineHazard $retirementDeclineHazard" }
    }
}
