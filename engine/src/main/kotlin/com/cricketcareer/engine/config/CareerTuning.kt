package com.cricketcareer.engine.config

/**
 * Every tunable constant in the career layer.
 *
 * Same rule as [EngineTuning]: no magic number lives at a call site, and each
 * one carries a comment saying why that value and what moving it does. If the
 * comment cannot be written, the number is not yet understood.
 *
 * See docs/CAREER_MODEL.md.
 */
data class CareerTuning(
    val ageing: AgeingTuning = AgeingTuning(),
    val form: FormTuning = FormTuning(),
    val fatigue: FatigueTuning = FatigueTuning(),
    val injury: InjuryTuning = InjuryTuning(),
    val training: TrainingTuning = TrainingTuning(),
    val selection: SelectionTuning = SelectionTuning(),
    val progression: ProgressionTuning = ProgressionTuning(),
    val contracts: ContractTuning = ContractTuning(),
    val world: WorldTuning = WorldTuning(),
    val worldAgeing: WorldAgeingTuning = WorldAgeingTuning(),
) {
    companion object {
        /** The calibration reference. Every career test measures against this. */
        val DEFAULT: CareerTuning = CareerTuning()
    }
}

/**
 * Development and decline.
 *
 * Peak ages differ by development class because that divergence is the entire
 * reason for modelling ageing rather than applying one curve: a quick bowler is
 * losing pace at 28 while his composure is still climbing at 34.
 */
data class AgeingTuning(
    /**
     * Age at which the physical class stops improving and starts going
     * backwards: pace, speed, stamina, raw fitness.
     * Lowering it shortens fast-bowling careers sharply, because physical
     * decline also drives the injury hazard.
     */
    val peakAgePhysical: Double = 26.0,

    /**
     * Peak for the technical class: technique, footwork, accuracy, the skills
     * built by repetition. Late because method keeps improving long after the
     * legs stop.
     */
    val peakAgeTechnical: Double = 30.0,

    /**
     * Peak for the mental class: composure, concentration, patience,
     * leadership. Set beyond the usual retirement age on purpose — these
     * should still be rising when a player retires, so that an old pro is
     * genuinely better at the thinking parts than a prodigy.
     */
    val peakAgeMental: Double = 34.0,

    /**
     * Points per year gained in one attribute by a player with maximum
     * learning rate, full exposure, the best coaching and an attribute at the
     * bottom of its range.
     *
     * The single most sensitive number in the career layer: it sets how long a
     * career takes to arrive. At 9.0, a 17-year-old rated 42 with potential 84,
     * a learning rate of 70 and a full season at his level reaches about 60 at
     * 22 and peaks near 78 at 30 — which is the shape a real career has.
     * No single year moves an attribute by more than about three points.
     */
    val growthPerYearAtFullHeadroom: Double = 9.0,

    /**
     * Exponent applied to headroom.
     *
     * Below 1 on purpose. At 1.0 the approach to potential is a clean
     * exponential, which means an enormous first year and a long flat tail —
     * a raw 16-year-old would gain twenty points in a season. The square root
     * flattens that into steady improvement that tapers, which is both what
     * development looks like and what makes a training decision at 24 still
     * worth making.
     */
    val headroomExponent: Double = 0.5,

    /**
     * Points per year lost ten years past the class peak, by a player rated
     * [declineReferenceRating] in that attribute.
     *
     * Quadratic in years past peak rather than linear, so the first two years
     * past peak cost almost nothing and the tenth costs a great deal. Nobody
     * notices a 31-year-old slowing down and everybody notices a 37-year-old;
     * a linear decline makes every player fade at the same dull rate.
     */
    val declinePerYearAtTenPastPeak: Double = 3.4,

    /**
     * The rating at which [declinePerYearAtTenPastPeak] is the literal figure.
     * Decline scales with how much a player has to lose, so a 90-rated
     * attribute sheds more points a year than a 20-rated one and both shed a
     * similar *fraction*.
     *
     * Absolute decline was the first thing a printed career exposed: a bowler
     * whose power started at 26 was at 1 by 39, because thirteen years of a
     * flat five points a year wipes out anything that did not start high. You
     * do not lose what you never had.
     */
    val declineReferenceRating: Double = 50.0,

    /**
     * How far above hidden potential an attribute may be pushed by playing
     * consistently above one's level, as a fraction of the 1-100 span.
     * Non-zero because a hard cap makes a career feel pre-written: the player
     * should be able to surprise the model that generated him.
     */
    val potentialOvershoot: Double = 0.06,

    /**
     * Standard deviation of the yearly noise term, in attribute points.
     * This is the difference between two identical players having identical
     * careers. Raising it makes progression feel arbitrary; zero makes it feel
     * like a spreadsheet.
     */
    val yearlyNoiseSigma: Double = 0.55,

    /**
     * Exposure below which a year develops nobody: the fraction of a full
     * season's minutes, played at or above the player's own standard, needed
     * before growth reaches its full rate. A year of second-XI cricket at 0.2
     * exposure returns a fifth of the growth.
     */
    val fullExposureMinutes: Double = 1.0,

    /** Youngest age at which the model will apply a growth or decline step. */
    val minAge: Int = 14,

    /** Age past which decline is applied but growth is not, whatever the headroom. */
    val growthStopsAt: Int = 33,
)

/**
 * Form, confidence and the surprise term that drives both.
 */
data class FormTuning(
    /**
     * Form change for a performance one full standard deviation above
     * expectation, at neutral temperament. Small because form is a rolling
     * average of a career, not a reaction to one innings: at 0.16 it takes
     * about four good scores to move a player from neutral to clearly in form.
     */
    val formGainPerSigma: Double = 0.16,

    /**
     * Confidence moves this multiple of form. Greater than one because
     * confidence is the volatile one: a player can be brimming after one
     * counter-attacking fifty while his form figure has barely moved.
     */
    val confidenceToFormRatio: Double = 2.1,

    /**
     * Divisor inside the tanh. Raising it makes the response more linear and
     * lets a single enormous score dominate; lowering it saturates sooner.
     * At 2.0 a two-sigma innings returns about 76% of the maximum move, so a
     * triple century is worth clearly more than a century and nothing like
     * three times as much.
     */
    val surpriseSaturation: Double = 2.0,

    /**
     * Fraction of the gap to neutral that form closes per rest day.
     * Slow: a purple patch should survive a fortnight off.
     */
    val formDecayPerDay: Double = 0.03,

    /** Fraction of the gap to neutral that confidence closes per rest day. Faster than form. */
    val confidenceDecayPerDay: Double = 0.08,

    /**
     * Fraction of the gap to [sharpnessFloor] that sharpness closes per day
     * without a match.
     *
     * Exponential rather than linear. A linear loss reaches the floor after
     * about seventy days and sits there, which makes a four-month off-season
     * and a two-year absence identical and every player start every season
     * equally rusty. At 0.022 a player is at roughly 0.45 after two months and
     * 0.29 after a normal off-season, so time out keeps costing something.
     */
    val sharpnessDecayPerDay: Double = 0.022,

    /**
     * Sharpness a player decays toward, never past.
     *
     * Above zero because a professional who has not played for a year is still
     * a professional: he is short of cricket, not incapable of it. Zero here
     * would make a long injury career-ending by arithmetic rather than by
     * anything that happened to him.
     */
    val sharpnessFloor: Double = 0.25,

    /** Sharpness regained per competitive innings or bowling spell. */
    val sharpnessGainPerAppearance: Double = 0.11,

    /**
     * How far hidden temperament scales the swing. A temperament of 1 swings
     * (1 + this) times as far as a temperament of 100, in both directions.
     * The volatile player is the one who is unplayable for a month and then
     * cannot buy a run.
     */
    val temperamentSwingRange: Double = 0.8,
)

/**
 * Workload and recovery.
 *
 * Fatigue is charged by work done, not by matches played, so that a Test
 * seamer's day and a T20 opener's day cost what they actually cost.
 */
data class FatigueTuning(
    /**
     * Fatigue per over bowled by a fast bowler at full effort, unfatigued.
     * At 0.011 a 25-over day costs 0.28, which a fit bowler clears in about
     * four days and an unfit one carries into the next match.
     */
    val fatiguePerOverPace: Double = 0.011,

    /** Fatigue per over bowled by a spinner. Roughly half a seamer's: no run-up. */
    val fatiguePerOverSpin: Double = 0.0052,

    /**
     * Fatigue per ball faced. Small per ball but a 250-ball Test innings in
     * the heat is a real physical cost, and it is the reason a batter who has
     * just batted all day fields badly.
     */
    val fatiguePerBallFaced: Double = 0.00042,

    /** Fatigue per hour in the field. */
    val fatiguePerFieldingHour: Double = 0.019,

    /**
     * Flat cost of turning up: travel, warm-ups, the day itself. Does not scale
     * with workload, so a run of matches tires a player even if he does nothing
     * in them. This is what makes a congested calendar bite.
     */
    val fatiguePerMatchAppearance: Double = 0.035,

    /**
     * Fraction of current fatigue cleared per rest day by a player of average
     * fitness and stamina. Exponential rather than linear because recovery from
     * 0.8 is fast and the last 0.1 is slow.
     */
    val recoveryPerRestDay: Double = 0.21,

    /**
     * How far fitness and stamina scale recovery. A player at attribute 100
     * recovers (1 + this) times as fast as one at 1.
     */
    val fitnessRecoveryRange: Double = 0.9,
)

/**
 * The injury hazard and what it costs.
 *
 * One roll per match and one per training week. The point of the model is that
 * playing tired is how a niggle becomes a tear — it is the mechanism that
 * punishes over-scheduling without anyone writing a rule that says
 * "do not over-schedule".
 */
data class InjuryTuning(
    /**
     * Probability that a fast bowler with average proneness, unfatigued, at 24,
     * breaks down in a single match. At 0.030 a seamer playing forty matches a
     * year misses parts of one or two of them, which is roughly a real career.
     */
    val baseRiskPerMatchPace: Double = 0.030,

    /** The same for a spinner. Lower: no run-up, no landing forces. */
    val baseRiskPerMatchSpin: Double = 0.012,

    /** The same for a specialist batter or keeper. Mostly fielding and running. */
    val baseRiskPerMatchOutfield: Double = 0.010,

    /**
     * Risk of a training week at full intensity, relative to a match.
     * Below one because nets are controlled, but not near zero: side strains
     * happen in the nets and a player who only ever trains hard still breaks.
     */
    val trainingRiskRelativeToMatch: Double = 0.40,

    /**
     * How far fatigue multiplies the hazard. At 1.9, a spent player is nearly
     * three times as likely to break down as a fresh one. This is the single
     * most important number in the career layer's feedback loop: without it,
     * resting a player is a pure loss and nobody would ever do it.
     */
    val fatigueRiskMultiplier: Double = 1.9,

    /**
     * How far hidden injury proneness multiplies the hazard. A player at
     * proneness 100 is (1 + this) times as likely to break as one at 1 —
     * the glass cricketer everyone has watched, whose fitness numbers never
     * explained it.
     */
    val pronenessRiskMultiplier: Double = 1.6,

    /** Age past which the hazard begins to climb. Bodies stop bouncing back. */
    val riskRisesFromAge: Int = 29,

    /** Extra hazard multiplier per year past [riskRisesFromAge]. */
    val riskPerYearPastPeak: Double = 0.07,

    /**
     * Severity split for a player who has just broken down, fresh and young,
     * as cumulative weights over NIGGLE, MINOR, MODERATE, SERIOUS, SEVERE.
     * Heavily weighted to the light end: most "injuries" in a season are a
     * player missing one game, not a player missing one year.
     */
    val severityWeights: List<Double> = listOf(0.46, 0.27, 0.17, 0.08, 0.02),

    /**
     * How far fatigue and age shift the severity draw toward the serious end.
     * Playing a spent 34-year-old does not just make an injury more likely, it
     * makes it a worse one.
     */
    val severityShiftFromStrain: Double = 0.28,

    /** Rehab days, per severity, for an average recoverer. */
    val rehabDaysNiggle: Int = 3,
    val rehabDaysMinor: Int = 12,
    val rehabDaysModerate: Int = 34,
    val rehabDaysSerious: Int = 96,
    val rehabDaysSevere: Int = 260,

    /**
     * How far fitness shortens rehab. A player at 100 heals in
     * (1 - this) of the time a player at 1 takes.
     */
    val rehabFitnessRange: Double = 0.35,

    /** Permanent points taken off every physical attribute when a severe injury resolves. */
    val severePermanentDamage: Int = 4,
)

/**
 * Training: what a week of work is worth.
 *
 * A week is 100 points split across focus areas. The gain is multiplied by
 * `(1 - fatigue)` on purpose, so that maximum intensity forever is *not* the
 * optimal strategy — otherwise this screen collapses into a single button.
 */
data class TrainingTuning(
    /**
     * Attribute points gained by spending a whole week (100 points) on one
     * focus area, at maximum learning rate, full headroom, the best coaching
     * and no fatigue.
     *
     * Small on purpose: a week is a week. At 0.85 a full off-season of eight
     * focused weeks moves one area about two and a half points for a 50-rated
     * player with potential 85 — visible over a season, and secondary to the
     * development that comes from actually playing. Training is the part of
     * progression the player controls, not the main engine of it.
     */
    val pointsPerFullWeek: Double = 0.85,

    /** Fatigue added by a week at full training intensity, before rest is deducted. */
    val fatiguePerFullWeek: Double = 0.16,

    /** Fatigue cleared by a week spent entirely on rest, as a fraction of current fatigue. */
    val restWeekRecovery: Double = 0.55,

    /**
     * Exponent on headroom, matching [AgeingTuning.headroomExponent] so that
     * training and playing agree about how hard improvement gets near
     * potential. If these two ever disagree, one of them is wrong.
     */
    val headroomExponent: Double = 0.5,

    /** Standard deviation of the noise on a week's gain, in attribute points. */
    val weeklyNoiseSigma: Double = 0.12,
)

/**
 * How a selection panel makes up its mind.
 *
 * Weights here are the *average* panel. An individual selector's personality
 * shifts them, which is what makes a career feel like something happening to
 * the player rather than a scoreboard he controls.
 */
/**
 * Moving between rungs of the ladder.
 *
 * The numbers that decide whether a season was a promotion, a holding pattern
 * or the end of something. They are the difference between a career that reads
 * as a story and one that reads as a random walk, and they are deliberately
 * asymmetric: going up is hard and going down is slow, because that is what
 * being in a system with selectors in it feels like.
 *
 * See docs/CAREER_MODEL.md §9.
 */
data class ProgressionTuning(
    /**
     * Places outside the XI a player may rank in the side above and still be
     * called up.
     *
     * A call-up is a place in a squad, but the squad place has to *mean*
     * something: a man named eighteenth of eighteen is not in the plans, he is
     * a net bowler. Judging it on squad size alone promoted a district
     * cricketer to an international in eight seasons and then left him carrying
     * drinks for three, with twenty-seven omissions a year and no cricket in
     * them - technically a career, unmistakably not one.
     *
     * Zero: a side calls a player up when it intends to *play* him. Anything
     * looser and a call-up stops being a reward and becomes a sentence - two
     * places outside the XI reads fine on paper and produced three seasons of
     * eighteen omissions and no cricket, because the XI that ranked above him
     * in April still ranks above him in August.
     *
     * Raising it makes the ladder easier to climb and much harder to play on.
     */
    val callUpMargin: Int = 0,

    /**
     * Share of his side's matches a player must play to keep his place.
     *
     * Below this he is not in the plans, and the rung below wants him playing.
     * Low, because carrying a man for a season is a real thing selectors do.
     */
    val retentionShare: Double = 0.25,

    /**
     * Seasons at a new rung before he can be sent back down.
     *
     * A player called up in April and dropped in September has not been given
     * a chance, and a model that does that produces careers that yo-yo instead
     * of developing.
     */
    val graceSeasons: Int = 1,

    /**
     * Seasons a player can be kept out of the reckoning before the selectors at
     * the rung above stop looking at him at all.
     *
     * This is what makes a missed opportunity cost something. Without it a
     * thirty-year-old who never played is assessed exactly like a nineteen-
     * year-old who never played, and being overlooked has no consequence.
     */
    val forgottenAfterSeasons: Int = 3,
) {
    init {
        require(callUpMargin >= 0) { "callUpMargin $callUpMargin cannot be negative" }
        require(retentionShare in 0.0..1.0) { "retentionShare $retentionShare must be in 0..1" }
        require(graceSeasons >= 0) { "graceSeasons $graceSeasons" }
        require(forgottenAfterSeasons >= 1) { "forgottenAfterSeasons $forgottenAfterSeasons" }
    }
}

data class SelectionTuning(
    /** Weight on raw ability for the format. The largest term, and it should be. */
    val weightStandard: Double = 1.00,

    /**
     * Weight on recent form. Substantial but well under ability, because a
     * panel that picks on form alone churns the side every week and never
     * builds anything.
     */
    val weightForm: Double = 0.34,

    /** Weight on suitability for these conditions: a second spinner on a turner. */
    val weightSuitability: Double = 0.22,

    /**
     * Weight on reputation, which is what an incumbent has and a challenger
     * does not. Non-zero so that a player is not dropped for one failure, and
     * so a young player must be clearly better rather than marginally better
     * to displace someone. This is the term that makes a debut feel earned.
     */
    val weightReputation: Double = 0.28,

    /** Weight subtracted for fitness doubt and a lack of match sharpness. */
    val weightRisk: Double = 0.45,

    /**
     * Standard deviation of the panel's judgement noise, on the same scale as
     * the score. The difference between a selection meeting and a spreadsheet:
     * two selectors looking at the same numbers do not always agree, and the
     * player on the wrong end of that is having a career, not a calculation.
     */
    val judgementSigma: Double = 0.06,

    /** Minimum genuine bowling options in an XI. Below this the side cannot bowl its overs. */
    val minimumBowlers: Int = 4,

    /** Minimum specialist batters, keeper included. */
    val minimumBatters: Int = 6,

    /** How far a player short of match sharpness is marked down, at sharpness 0. */
    val sharpnessPenalty: Double = 0.5,

    /** How far a carried niggle is marked down. A player is pickable with one; he is not free. */
    val nigglePenalty: Double = 0.35,
)

/**
 * What a cricketer is worth, and what he will sign.
 *
 * Money is in **units**, not rupees or pounds: the seed database attaches a
 * currency and a scale to a country, so the engine can compare a state contract
 * with a franchise deal without knowing what either is denominated in. Putting
 * a currency in here would bake one country's economy into the physics.
 */
data class ContractTuning(
    /**
     * Units per season a player of standard 1.0 commands at the top of the
     * ladder. Everything else is a fraction of this, so moving it rescales the
     * whole economy without changing any relative price.
     */
    val topOfMarketPerSeason: Double = 1000.0,

    /**
     * Exponent on standard in the valuation. Above one because the market for
     * cricketers is steeply convex: the best player in a competition is worth
     * far more than twice the median, and a linear market makes every squad
     * identical.
     */
    val standardExponent: Double = 2.6,

    /** How far current form moves a price, at form 1.0. Short-term and real. */
    val formPremium: Double = 0.22,

    /**
     * How far reputation moves a price. Bigger than form, because a famous
     * player sells shirts whatever he averaged last month, and franchises pay
     * for that.
     */
    val reputationPremium: Double = 0.40,

    /** Age at which a player commands his peak price. Earlier than his peak ability: clubs buy futures. */
    val peakMarketAge: Int = 27,

    /** Fraction of value lost per year either side of [peakMarketAge]. */
    val valueFallPerYearFromPeak: Double = 0.055,

    /**
     * How far a guaranteed place in the XI is worth to a player, as a fraction
     * of salary. Almost a doubling, because a career is made of matches and a
     * bench year at a big club costs sharpness, then form, then the next
     * selection — the model has to price all three or "sign for the rich club
     * and never play" becomes the optimal career.
     */
    val guaranteedPlaceWorth: Double = 0.90,

    /**
     * How far playing at a higher standard is worth, per unit of
     * [LadderLevel.standard], as a fraction of salary.
     *
     * The ladder spans 0.22 at college to 0.92 at international, so at 3.2 the
     * whole climb is worth about a 220% pay gap — a player will take a large
     * pay cut to go from district cricket to a state side, and a small one to
     * go from a state side to an international contract. Set this low and every
     * cricketer signs for whoever pays most and the ladder stops meaning
     * anything; set it very high and money never matters at all.
     */
    val standardWorth: Double = 3.2,

    /** Noise on a player's judgement of an offer, as a fraction of its value. */
    val decisionSigma: Double = 0.09,

    /**
     * Fraction above a team's valuation that it will still bid at auction.
     * Above zero because an auction is a room, not a spreadsheet, and the last
     * bid is always slightly mad.
     */
    val auctionOverbid: Double = 0.18,

    /** Smallest auction increment, as a fraction of the lot's base price. */
    val auctionIncrement: Double = 0.05,
)

/**
 * The reduced-form world model.
 *
 * Tier 1 is the full ball-by-ball engine. Tiers 2 and 3 exist because
 * simulating every ball of every match in every country would cost minutes per
 * season on a phone, and nobody would ever look at most of it.
 *
 * **These coefficients are fitted from tier-1 output, never hand-authored.**
 * `sim-harness` runs the fit and writes them here; a divergence between tiers
 * is a calibration bug, not a design choice. That is the only way a player's
 * statistics in another country stay comparable with the user's own — which is
 * the whole point of simulating the rest of the world at all.
 *
 * See docs/CAREER_MODEL.md §11.
 */
data class WorldTuning(
    /**
     * Runs an average batter (standard 0.5) scores per innings against an
     * average attack, per format.
     *
     * MEASURED, not chosen. Taken from 1200 T20, 700 List A and 300 four-day
     * innings of Fixtures.averageXI on Pitch.AVERAGE, which returned batting
     * averages of 27.62, 31.59 and 37.50. These means are those averages times
     * the corresponding dismissal rate below, so that
     * `WorldSim.average` reproduces the engine's own number.
     * Re-measure with WorldSimAgreementTest whenever the engine's calibration
     * moves; a divergence here is a bug, not a preference.
     */
    val meanRunsT20: Double = 21.5,
    val meanRunsListA: Double = 24.6,
    val meanRunsMultiDay: Double = 35.3,

    /**
     * Probability that a batter who batted was dismissed, per format.
     *
     * Not one minus a not-out rate plucked from the air: an innings has eleven
     * batters and at most ten wickets, so somebody is always not out, and over
     * five days almost everybody else is out.
     */
    val dismissalRateLimitedOvers: Double = 0.78,
    val dismissalRateMultiDay: Double = 0.94,

    /**
     * Balls per run for an average batter, per format — the inverse of a
     * strike rate. Measured alongside the means above: 140, 98 and 61.
     */
    val ballsPerRunT20: Double = 0.712,
    val ballsPerRunListA: Double = 1.024,
    val ballsPerRunMultiDay: Double = 1.652,

    /**
     * Ratio of the standard deviation of an innings to its mean.
     *
     * Just above 1 because cricket scores are roughly geometric: a batter's
     * most likely score is low, his mean is well above his median, and the
     * distribution has a long right tail. Anything close to a normal
     * distribution here would produce a world with no ducks and no hundreds.
     */
    val runsDispersion: Double = 1.15,

    /**
     * How far a one-unit difference in standard moves a batter's mean, as a
     * multiplier. At 2.8, a 0.8-standard batter averages roughly two and a
     * half times a 0.2-standard one, which is about the spread between an
     * international and a club cricketer.
     */
    val standardToRuns: Double = 2.8,

    /** The same for a bowler's strike rate, inverted: better bowlers strike sooner. */
    val standardToStrikeRate: Double = 2.2,

    /**
     * Balls per wicket for an average bowler against average batting, per
     * format. Measured from the same samples as the means above.
     */
    val meanStrikeRateT20: Double = 19.7,
    val meanStrikeRateListA: Double = 32.4,
    val meanStrikeRateMultiDay: Double = 62.0,

    /**
     * How far form moves a reduced-form innings, as a fraction of the mean at
     * form 1.0. Matched to the effect form has inside the real engine, so a
     * tier-2 player in a purple patch and a tier-1 player in one look alike.
     */
    val formEffect: Double = 0.18,

    /**
     * How far a batter's own scoring shape moves his strike rate.
     *
     * A range hitter and a blocker do not score at the same rate, and until
     * this existed every reduced innings in the world came back at exactly the
     * format's mean strike rate - a batter's whole tempo, which is half of what
     * distinguishes one cricketer from another, was not modelled at all. The
     * term is the balance between his scoring attributes and his occupying
     * ones, centred so an even player is unaffected.
     */
    val tempoFromAttributes: Double = 0.55,

    /**
     * Log-normal spread of the balls-per-run multiplier on one innings.
     *
     * A thirty off ninety and a thirty off twenty are different innings, and a
     * model that cannot tell them apart cannot produce a chase. Normalised to
     * mean one, so widening the spread changes the shape of a career's innings
     * without moving the strike rate the calibration suite measures.
     */
    val tempoSpread: Double = 0.26,
)
