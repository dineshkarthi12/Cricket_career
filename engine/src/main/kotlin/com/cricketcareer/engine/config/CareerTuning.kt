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
     * Points per year lost at the steepest part of the decline, ten years past
     * the class peak. Decline is quadratic in years-past-peak rather than
     * linear, so the first two years past peak cost almost nothing and the
     * tenth costs a great deal. A linear decline makes every player fade at the
     * same dull rate.
     */
    val declinePerYearAtTenPastPeak: Double = 3.4,

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
     * Sharpness lost per day without a match. At 0.014 a player is down to
     * about 0.75 after two months out, which is the point at which a returning
     * batter looks visibly short of cricket.
     */
    val sharpnessLossPerDay: Double = 0.014,

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
