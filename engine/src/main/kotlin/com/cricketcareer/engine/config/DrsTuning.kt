package com.cricketcareer.engine.config

/**
 * The Decision Review System.
 *
 * Two things are being modelled, and they are not the same thing: what the
 * ball actually did, and what the players *believed* it did. A review is a
 * judgement made in two seconds by somebody at the wrong end of the pitch, and
 * a model where sides only ever review correct-to-overturn decisions would give
 * every team three good reviews a match and no drama at all.
 *
 * Margins below are in the same units Stage 6 works in: one unit is one
 * tolerance width, so a margin of 1.0 is comfortably clear and 0.1 is the sort
 * of thing that goes to the third umpire and comes back as umpire's call.
 *
 * See docs/SIMULATION_MODEL.md §13.
 */
data class DrsTuning(
    /**
     * Half-width of the umpire's-call band.
     *
     * Inside it, the on-field decision stands and the review is retained. This
     * is the single most argued-about number in modern cricket and it is
     * deliberately generous: a narrow band turns every marginal lbw into a
     * coin-flip overturn, which is precisely what umpire's call exists to stop.
     */
    val umpiresCallBand: Double = 0.20,

    /**
     * How badly the reviewing side misreads the margin, at the bottom of the
     * ladder and at the top.
     *
     * The whole reason a review is a resource. A side that could see the truth
     * would never waste one, and the tension of holding the last review into
     * the final session would not exist.
     *
     * Deliberately worse than the umpire's own judgement of the same margin. A
     * batter halfway down the wicket, or a bowler in his follow-through, is at
     * the wrong end to see whether a ball would have clipped leg stump; the
     * umpire is directly behind it. Set them level and about two reviews in
     * five succeed, against roughly one in four in real cricket.
     */
    val perceptionSigmaWorst: Double = 2.05,
    val perceptionSigmaBest: Double = 1.20,

    /**
     * How much better a side reads pitching and impact than wicket-hitting.
     *
     * The thing that makes reviews sane rather than random. A batter *knows*
     * where he was hit and whether it pitched outside his leg stump: he felt
     * it. Nobody on the field knows whether it was going on to hit, which is
     * exactly why the technology was invented for that question.
     *
     * With one noise for all three, reviews were triggered overwhelmingly by
     * misreading marginal wicket-hitting - measured, 76% of them came back
     * umpire's call against about a quarter in real cricket - because marginal
     * balls vastly outnumber clear umpiring errors.
     */
    val positionSigmaScale: Double = 0.22,

    /**
     * How wrong a side has to believe the umpire is before it goes upstairs.
     *
     * Over and above the umpire's-call band, which a side already discounts.
     * Raising it makes sides hoard reviews and lose lbws they would have won;
     * lowering it burns reviews on hope. Calibrated against how often reviews
     * succeed - about a quarter of them, in real cricket.
     */
    val reviewThreshold: Double = 0.45,

    /**
     * Extra willingness when it is the last wicket standing or the last over.
     *
     * A side with nothing left to protect reviews everything, which is why the
     * last pair burns both reviews on nothing and the crowd groans.
     */
    val desperationBonus: Double = 0.9,

    /**
     * The rung at which the technology exists.
     *
     * District cricket has one umpire, no cameras and no appeals process. Below
     * this standard a decision is final, and a player's whole career at that
     * level is played under umpires who are wrong more often and cannot be
     * corrected - which is one of the things that makes climbing the ladder
     * mean something.
     */
    val minimumLevelStandard: Double = 0.75,
) {
    init {
        require(umpiresCallBand >= 0.0) { "umpiresCallBand $umpiresCallBand" }
        require(perceptionSigmaWorst >= perceptionSigmaBest) { "a better side must read it better" }
        require(perceptionSigmaBest > 0.0) { "nobody reads it perfectly" }
        require(positionSigmaScale in 0.0..1.0) { "positionSigmaScale $positionSigmaScale" }
        require(reviewThreshold >= 0.0) { "reviewThreshold $reviewThreshold" }
        require(desperationBonus >= 0.0) { "desperationBonus $desperationBonus" }
        require(minimumLevelStandard in 0.0..1.0) { "minimumLevelStandard $minimumLevelStandard" }
    }
}
