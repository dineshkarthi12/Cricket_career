package com.cricketcareer.engine.config

/**
 * Every tunable number in the match engine.
 *
 * Per CLAUDE.md §5, no magic number lives at a call site. Each field here says
 * what it is, what moving it does, and which calibration band it answers to.
 *
 * Three tiers, per docs/SIMULATION_MODEL.md §12:
 *  - **Tier 1** physical constants live in `Geometry` and are not tunable at all.
 *  - **Tier 2** model parameters are the sub-configs below. Change one only with
 *    a cricketing argument, never to chase a number.
 *  - **Tier 3** is [knobs] — a deliberately small set of global scalars, each
 *    chosen to move one target family roughly monotonically. Calibration is
 *    coordinate descent over those, and nothing else.
 *
 * Passed into the simulation, never read from a global, so the harness can
 * sweep a parameter and difficulty settings can be data rather than code.
 */
data class EngineTuning(
    val intent: IntentTuning = IntentTuning(),
    val execution: ExecutionTuning = ExecutionTuning(),
    val movement: MovementTuning = MovementTuning(),
    val perception: PerceptionTuning = PerceptionTuning(),
    val contact: ContactTuning = ContactTuning(),
    val outcome: OutcomeTuning = OutcomeTuning(),
    val pressure: PressureTuning = PressureTuning(),
    val pitch: PitchTuning = PitchTuning(),
    val formatIntent: FormatIntentTuning = FormatIntentTuning(),
    val dls: DlsTuning = DlsTuning(),
    val rain: RainTuning = RainTuning(),
    val drs: DrsTuning = DrsTuning(),
    val knobs: CalibrationKnobs = CalibrationKnobs(),
) {
    companion object {
        // Lazy for the same reason DlsTuning.DEFAULT is: a sub-tuning whose
        // own defaults fail validation must not make the engine unloadable
        // before the tool that repairs them can run.
        val DEFAULT: EngineTuning by lazy { EngineTuning() }
    }
}

/**
 * Tier 3. The only things calibration is allowed to turn.
 *
 * All default to 1.0, so a fresh engine is "the model as designed" and every
 * deviation from 1.0 is a visible, reviewable admission that the model needed
 * help. When two bands can only be satisfied by opposite moves of one knob,
 * stop turning it — that is the signal a Tier 2 model is wrong.
 */
data class CalibrationKnobs(
    /** Widens or narrows every bowler's execution error. Moves dot %, boundary %, wicket rate; wides follow. */
    val executionSpreadScale: Double = 1.0,

    /**
     * Scales how badly the batter mis-reads the ball. The primary wicket-rate
     * control.
     *
     * Held below 1.0 to keep the run rate down. That is treating a symptom -
     * see docs/SIMULATION_MODEL.md §15 on the play-and-miss surplus, which is
     * the defect underneath it. Dot % and balls per wicket move in *opposite*
     * directions on this knob, which CLAUDE.md §5 names as the signal to stop
     * turning it and fix the model instead.
     */
    val perceptionErrorScale: Double = 0.96,

    /** Scales every shot's tolerance ellipsoid. Up means better contact: more boundaries, fewer edges. */
    val contactToleranceScale: Double = 1.0,

    /** Scales catch difficulty. Moves the drop rate and the caught share of dismissals. */
    val catchDifficultyScale: Double = 1.0,

    /** Shifts batter risk appetite. Moves run rate and dot % together, and wicket rate with them. */
    val aggressionBias: Double = 0.0,

    /** Scales how readily an umpire raises the finger. Moves the LBW share against the bowled share. */
    val lbwStrictnessScale: Double = 1.12,

    /** Scales exit speed off the bat. Moves boundary % without touching the dismissal mix much. */
    val batPowerScale: Double = 1.20,
)

/** Stage 1 — what the bowler is trying to bowl. */
data class IntentTuning(
    /**
     * Softmax temperature on delivery choice.
     *
     * Low makes bowlers relentless and *readable*; high makes them scattergun.
     * The trade-off is real cricket: a disciplined bowler is predictable, and a
     * batter can set up against him.
     */
    val baseTemperature: Double = 0.35,

    /** How much poor discipline raises the temperature. */
    val disciplineTemperatureWeight: Double = 0.60,

    /** How much aggression raises it. */
    val aggressionTemperatureWeight: Double = 0.40,

    /** Chance per ball that a bowler re-draws his plan for the batter he is bowling at. */
    val planRedrawChance: Double = 1.0 / 6.0,

    /** Boundaries in the last three balls that force an immediate plan change. */
    val planFailureBoundaries: Int = 2,

    /**
     * How fast a bowler's belief about a batter converges on the truth, per ball
     * bowled at him. Slow: a debutant's weakness against the short ball is not
     * known on day one, it gets found out.
     */
    val beliefLearningRate: Double = 0.035,
)

/** Stage 2 — execution error. */
data class ExecutionTuning(
    /**
     * Base standard deviation of length error, in metres, for a bowler with
     * accuracy 50 bowling a stock ball, unfatigued, no pressure.
     *
     * Raising it widens the spread of lengths, which lifts boundary rate *and*
     * wicket rate together. The primary control on T20 dot % (target 30-36%).
     */
    val lengthSigmaBase: Double = 0.62,

    /**
     * Base standard deviation of line error, in metres.
     *
     * Far tighter than length: bowlers miss their length much more often than
     * their line, and that asymmetry is what makes length the more valuable
     * skill. Also the emergent driver of the wide rate (target 3-5% white ball).
     */
    val lineSigmaBase: Double = 0.255,

    /** Multiplier at accuracy 0. */
    val accuracyWorst: Double = 1.55,

    /** Multiplier at accuracy 100. */
    val accuracyBest: Double = 0.45,

    /** How much fatigue widens the error, and how non-linearly. */
    val fatigueWeight: Double = 0.45,
    val fatigueExponent: Double = 1.40,

    /** How much pressure widens it, before composure damps the effect. */
    val pressureWeight: Double = 0.30,

    /**
     * Fraction of deliveries drawn from the wide tail, and how much wider it is.
     *
     * A single Gaussian produces far too few genuine long hops and rank full
     * tosses. Every bowler has an occasional one that gets away, and the heavy
     * tail is where free boundaries — and a good share of the wickets that
     * follow them — come from.
     */
    val tailProbability: Double = 0.035,
    val tailWidening: Double = 2.60,

    /** Correlation between length and line error along the bowler's release angle. */
    val lengthLineCorrelation: Double = 0.15,

    /** Base per-ball probability of overstepping, for a disciplined fresh bowler. */
    val noBallBase: Double = 0.0045,
    val noBallDisciplineWeight: Double = 1.20,
    val noBallFatigueWeight: Double = 0.50,

    /** Relative spread of delivered pace around the target. */
    val paceSigmaFraction: Double = 0.020,

    /** How much fatigue costs a bowler off his pace. */
    val paceFatigueLoss: Double = 0.060,
)

/** Stage 3 — what the ball does. */
data class MovementTuning(
    /** Overs constants for ball condition: shine, roughness and hardness decay. */
    val shineDecayOvers: Double = 22.0,
    val roughnessGrowthOvers: Double = 30.0,
    val hardnessDecayOvers: Double = 25.0,
    val hardnessFloor: Double = 0.35,

    /** Maximum conventional swing at the stumps, in metres, for a great swing bowler. */
    val swingMax: Double = 0.55,

    /** Shine differential builds over this many overs, then decays over this many. */
    val swingBuildOvers: Double = 2.5,
    val swingDecayOvers: Double = 18.0,

    /** Atmospheric term: base, plus humidity and cloud weights, clamped. */
    val atmosphereBase: Double = 0.75,
    val humidityWeight: Double = 0.50,
    val cloudWeight: Double = 0.35,
    val atmosphereMin: Double = 0.60,
    val atmosphereMax: Double = 1.60,

    /**
     * Pace at which conventional swing peaks, km/h, and the width of that peak.
     *
     * Very fast bowlers swing it *less*, not more: the ball has less time in the
     * air to deviate. This is why a 135 km/h swing bowler can be more dangerous
     * with a new ball than a 148 km/h quick.
     */
    val swingPeakKph: Double = 133.0,
    val swingPaceWidth: Double = 28.0,

    /** Ball-to-ball variation in swing. Without it the batter's model of the world becomes exact. */
    val swingVariation: Double = 0.25,

    /** Exponent on flight fraction for conventional swing. 2 means it accrues steadily. */
    val conventionalLateness: Double = 2.0,

    /** Reverse swing: gate centre in overs, gate width, and the roughness it needs. */
    val reverseGateOvers: Double = 34.0,
    val reverseGateWidth: Double = 4.0,
    val reverseRoughnessThreshold: Double = 0.55,
    val reversePaceThresholdKph: Double = 128.0,
    val reverseMax: Double = 0.42,

    /** Reverse swing happens very late, which is why it is dangerous at lower deviation. */
    val reverseLateness: Double = 3.4,

    /** Seam movement off the pitch: scale, and how grass and moisture feed it. */
    val seamMax: Double = 0.20,
    val seamGrassExponent: Double = 0.70,
    val seamMoistureBase: Double = 0.50,
    val seamMoistureWeight: Double = 0.80,

    /** Probability the seam lands upright, and the penalty when it does not. */
    val seamUprightBase: Double = 0.45,
    val seamUprightAccuracyWeight: Double = 0.40,
    val seamScuffedPenalty: Double = 0.25,

    /** Spin: maximum lateral deviation, and how pitch grip scales it. */
    val turnMax: Double = 0.55,
    val gripBase: Double = 0.25,
    val gripWeight: Double = 0.75,

    /** Drift, and how much dip shortens the effective length, in metres. */
    val driftMax: Double = 0.35,
    val dipMaxMetres: Double = 0.60,

    /** Bounce: the pitch factor range, and the variable-bounce noise. */
    val bounceFactorMin: Double = 0.72,
    val bounceFactorMax: Double = 1.28,
    val variableBounceBase: Double = 0.02,
    val variableBounceCrackWeight: Double = 0.16,
)

/** Stage 4 — what the batter thinks he sees, and what he plays. */
data class PerceptionTuning(
    /**
     * Base standard deviation of the batter's length estimate, in metres.
     *
     * The primary wicket-rate control. Raising it makes batting harder in every
     * format at once, which is why it answers to the balls-per-wicket bands
     * (T20 target 16-19) rather than to any single one.
     */
    val baseSigmaMetres: Double = 0.295,

    /**
     * Line estimate as a fraction of the length estimate's error.
     *
     * Batters judge line far better than length — a ball's lateral position is
     * visible for the whole flight, its length only near the end. At 0.42 the
     * line error came out around 19 cm, which is wider than a bat and meant
     * nobody could middle anything.
     */
    val lineSigmaFraction: Double = 0.26,

    val techniqueWorst: Double = 1.60,
    val techniqueBest: Double = 0.70,
    val concentrationWorst: Double = 1.50,
    val concentrationBest: Double = 1.00,

    /**
     * Settling: perception error is multiplied by `1 + weight * exp(-balls/scale)`.
     *
     * At 0 balls faced that is 1.58x; by 15 balls 1.05x; by 40 essentially 1.0.
     *
     * Raised from 0.45 in Phase 4, when feathering the bat's edge gave some of
     * the settling effect away: a new batter's extra mis-reads used to produce
     * *beaten* balls, which can be bowled or lbw, and feathered they become
     * edges that the cordon sometimes puts down. There was no room to raise it
     * before - it took dot % out of band - and the feather made the room.
     * This single decaying term produces the brief's "far more vulnerable in his
     * first 10-15 balls", a dismissal hazard that *falls* through an innings,
     * and therefore the innings-score distribution Section 3 demands — without
     * ever sampling from a score distribution.
     */
    val settleWeight: Double = 0.58,
    val settleScaleBalls: Double = 6.0,

    /**
     * The second, slower half of settling: getting *properly* in.
     *
     * Playing yourself in is two processes, not one. Sighting the ball takes a
     * handful of deliveries; being genuinely set — knowing the pace of the
     * pitch, the bowlers' plans, where the gaps are — takes the better part of
     * an hour.
     *
     * With only the fast term, a batter who had faced a hundred balls was no
     * safer than one who had faced twenty. That flattened the survival hazard
     * after the first twenty deliveries and, worse, made a fifty-over innings
     * no safer than a Twenty20 one — the formats collapsed into each other.
     */
    val deepSettleWeight: Double = 0.30,
    val deepSettleScaleBalls: Double = 50.0,

    /** Pace discomfort: comfort speed at pacePlay 0 and 100, and how fast it bites above that. */
    val paceComfortFloorKph: Double = 120.0,
    val paceComfortRangeKph: Double = 30.0,
    val paceDiscomfortWeight: Double = 0.90,
    val paceDiscomfortScaleKph: Double = 40.0,

    /** Bad light. */
    val lightWeight: Double = 0.35,

    /** How sharply spin-reading skill decides whether a wrong'un is picked. */
    val wrongUnDetectionSlope: Double = 3.2,

    /** Softmax temperature on shot choice, and how composure and pressure raise it. */
    val shotTemperature: Double = 0.30,
    val shotComposureWeight: Double = 0.50,
    val shotPressureWeight: Double = 0.40,

    /**
     * Risk appetite terms.
     *
     * [baseRisk] is where an unremarkable batter starts before the situation
     * says anything. Twenty20 is an attacking format from the first ball, and a
     * base low enough to suit a Test opener produces a 4-an-over T20.
     */
    val baseRisk: Double = 0.42,
    val aggressionRiskWeight: Double = 0.35,
    val requiredRateWeight: Double = 0.90,
    val settlednessCaution: Double = 0.35,
    val newBatterCaution: Double = 0.30,
)

/** Stage 5 — bat on ball. */
data class ContactTuning(
    /**
     * Base timing error in SECONDS for a timing-50 settled batter under no
     * pressure.
     *
     * Stage 5 multiplies this by the ball's speed to get a length error, so the
     * units bite hard: 8 ms at 140 km/h is 0.31 m, which is about half a shot's
     * tolerance. Anything near 50 ms would put the bat a full two metres from
     * the ball and nobody would ever middle anything.
     *
     * It also means timing matters more against pace than against spin without
     * anything in the code saying so, which is correct.
     */
    val timingSigmaSeconds: Double = 0.008,
    val timingSkillWorst: Double = 1.50,
    val timingSkillBest: Double = 0.60,
    val timingPressureWeight: Double = 0.50,

    /**
     * Half-widths of the reference shot's tolerance ellipsoid, in metres.
     *
     * A ball this far from where the batter sent the bat still middles.
     * Multiplied per shot by its own difficulty and by the batter's technique
     * and footwork. Raising these lifts strike rate and cuts edges — the
     * boundary-% control (T20 target 17-20%).
     */
    val lengthTolerance: Double = 0.80,
    val lineTolerance: Double = 0.21,
    val heightTolerance: Double = 0.30,

    /** How much technique and footwork widen tolerance. */
    val techniqueWeight: Double = 0.55,
    val footworkWeight: Double = 0.40,

    /**
     * How far past the shot's tolerance the ball has to be before the bat
     * misses it altogether.
     *
     * Play-and-miss is only about 10-12% of deliveries in real cricket. Setting
     * this low made it 19%, and since a beaten ball is a forced dot, the extra
     * play-and-misses alone put the dot rate eight points over its band. Raising
     * it turns those near-misses into the thin edges they should have been,
     * which cuts dots and finds the cordon more.
     */
    val missThreshold: Double = 1.88,

    /**
     * How far past the tolerance envelope a ball still catches the edge of the
     * bat, as a multiple of [missThreshold].
     *
     * A bat has an edge; the envelope does not. See
     * docs/SIMULATION_MODEL.md §16 for the full diagnosis - in short, modelling
     * the boundary as a cliff put play-and-miss at 19% of deliveries against a
     * real 10-12%, and carried dot % and balls per wicket out of band with it.
     *
     * Feathering is geometry, not a draw: a ball that clips the bat clips it,
     * and nothing here is sampled.
     */
    val edgeFeatherFactor: Double = 1.12,

    /**
     * How far the bat can actually be put, laterally, measured at the stumps.
     *
     * Asymmetric because a batter's reach is: he can stretch a long way outside
     * off and barely at all outside leg. Without this cap the bat followed the
     * ball wherever it went and batters middled deliveries a metre wide of off
     * — which erased wides, bowled and lbw from the game at once.
     */
    val batReachOffSideMetres: Double = 0.86,
    val batReachLegSideMetres: Double = -0.46,

    /** Highest and lowest the bat can meet the ball, in metres. */
    val batReachHighMetres: Double = 1.75,
    val batReachLowMetres: Double = 0.0,
)

/** Stage 6 — where it goes and what happens. */
data class OutcomeTuning(
    /**
     * Exit speed floor as a fraction of a middled shot, and the bat's full
     * contribution in m/s.
     *
     * A well-middled drive leaves the bat around 38-44 m/s (140-160 km/h), and
     * clearing a 70 m rope needs roughly 30 m/s at 30 degrees. Set these too low
     * and nothing reaches the boundary at all — the whole scoring model collapses
     * into singles.
     */
    val mistimedSpeedFloor: Double = 0.30,
    val batSpeedContribution: Double = 46.0,

    /** Fraction of the incoming pace redirected on a middled shot. */
    val incomingPaceTransfer: Double = 0.28,

    /**
     * How much of that rebound survives a completely dead bat.
     *
     * Soft hands are a skill: a batter dropping the ball at his feet is
     * deliberately killing its pace. At 1.0 every defensive push rebounds like a
     * drive and rolls into the covers for a single.
     */
    val deadBatAbsorption: Double = 0.22,

    /** Spread of the exit azimuth, in degrees, at perfect and at zero contact quality. */
    val azimuthSpreadBest: Double = 9.0,
    val azimuthSpreadWorst: Double = 46.0,

    /** Drag on a struck ball. Ball mass 0.156 kg, Cd 0.5, cross-section 0.0041 m². */
    val dragCoefficient: Double = 0.50,
    val ballMassKg: Double = 0.156,
    val ballAreaM2: Double = 0.0041,
    val airDensity: Double = 1.225,

    /**
     * Catch model, as a logistic in skill and difficulty.
     *
     * The intercept sets the rate for a regulation chance to an average pair of
     * hands; the difficulty weight sets how fast that falls away as he has to
     * run, dive or take it flat. Calibrated against the 20-25% overall drop rate
     * and 75-85% slip catching in docs/CALIBRATION.md.
     */
    val catchSkillWeight: Double = 4.2,
    val catchDifficultyWeight: Double = 2.4,
    val catchPressureWeight: Double = 0.45,
    val catchIntercept: Double = 3.48,

    /**
     * How long a fielder takes to pick up a ball off the bat and move.
     *
     * Longer than his reaction to a ball already in the air: he has to see it
     * off the face first. At the old 0.25 s an infielder cut off a straight
     * drive travelling at 30 m/s, which put the boundary rate on the floor.
     */
    val interceptReactionSeconds: Double = 0.38,

    /**
     * Where a feathered edge goes, in degrees above the horizontal, and the
     * spread around it.
     *
     * Shallow, but reliably off the ground: it leaves the bat at about the
     * height it arrived and reaches the cordon at chest height and the keeper
     * at his gloves.
     *
     * Both numbers are set by where the cordon actually stands - fourteen
     * metres - because a nick that pitches in front of the keeper is not a
     * wicket. Given a defensive stroke's own elevation instead, two feathers in
     * five never got off the ground at all, and the median of the rest carried
     * 10.7 metres and died in front of him.
     */
    val featherElevationDegrees: Double = 13.0,
    val featherElevationSpread: Double = 2.6,

    /**
     * Share of the ball's own pace a feathered edge keeps.
     *
     * A feather is a deflection rather than a stroke: the bat puts almost
     * nothing into it and takes almost nothing out. Scoring it by contact
     * quality, as every other contact is scored, made it the slowest ball on
     * the field - it died at the batter's feet and the cordon never saw it.
     *
     * Tuned so the median feather carries to about where the cordon stands
     * rather than to a number that sounded physical.
     */
    val featherPaceRetained: Double = 0.66,

    /**
     * Directional spread of a feathered edge, in degrees.
     *
     * Tight, and fixed rather than scaled by contact quality. Every other
     * contact is scattered in proportion to how badly it was struck, which is
     * right for a stroke and wrong for a deflection: the ball barely changed
     * direction, so it cannot have changed direction by very much. Left on the
     * quality scale, feathers sprayed thirty degrees either side of the keeper
     * and most of them missed the cordon entirely.
     */
    val featherAzimuthSpread: Double = 11.0,

    /** Fielder reach in metres, and how fast one closes on the ball. */
    val fielderReachMetres: Double = 2.1,
    val fielderSpeedMetresPerSecond: Double = 7.2,
    val fielderReactionSeconds: Double = 0.25,

    /**
     * Sideways speed when cutting off a ball hit past you, m/s.
     *
     * Much slower than a chase: this is a step-and-dive across the line of a
     * ball already travelling, not a sprint to the rope. At full sprint speed an
     * infielder cut off roughly nine metres either side of himself, which
     * stopped every shot in the game and left the boundary rate near zero.
     */
    val interceptSpeedMetresPerSecond: Double = 3.0,

    /**
     * Closing speed on a ball in the air, m/s.
     *
     * Slower than a flat sprint: he has to pick the ball up, turn, and run with
     * his head back. At full sprint speed a deep fielder covered eighteen metres
     * under a skier and caught nearly everything hit in the air.
     */
    val aerialClosingSpeed: Double = 5.4,

    /** How often a ground fielder fumbles, at groundFielding 50. */
    val misfieldBase: Double = 0.055,

    /** Running: batter speed range in m/s, and the cost of turning for a second run. */
    val runSpeedSlowest: Double = 6.6,
    val runSpeedFastest: Double = 8.6,
    val turnCostSeconds: Double = 1.15,

    /**
     * Time for a fielder who has cut the ball off inside the ring to gather it
     * and get rid, moving onto the ball rather than waiting for it.
     *
     * Much quicker than a standing pick-up, and it is what makes a ring fielder
     * worth having: he turns an arithmetically available single into a dot.
     */
    val attackingPickUpSeconds: Double = 0.40,

    /**
     * How close to the ball's line a fielder has to be to be *attacking* it
     * rather than chasing it.
     *
     * Inside this he is moving onto the ball and the single is off; outside it
     * he is running across and the batters go. This is the difference between a
     * push to cover and a push into the gap beside him, and it is why a denser
     * ring — six in a fifty-over middle session against five in a Twenty20 —
     * produces more dot balls without anyone changing their intent.
     */
    val attackingReachMetres: Double = 6.5,

    /** Throw speed range, in m/s, from throwArm. */
    val throwSpeedSlowest: Double = 18.0,
    val throwSpeedFastest: Double = 31.0,

    /**
     * Direct-hit probability at throwArm 50, and how far into the red a batter
     * will run anyway.
     *
     * Both deliberately small: run outs are only 4-6% of dismissals, and a
     * generous risk margin here floods the game with them.
     */
    val directHitBase: Double = 0.045,

    /**
     * Share of successful run outs completed by hitting the stumps directly,
     * rather than by a throw to the keeper or the bowler.
     *
     * A direct hit from the deep and a relayed throw to the keeper are two
     * different pieces of cricket and a scorecard names a different fielder for
     * each. Roughly a third, which is about what a real season looks like;
     * close-in run outs skew direct, throws from the boundary skew relayed, and
     * [directHitRangeMetres] is where the balance tips.
     */
    val directHitShare: Double = 0.34,

    /** Beyond this a throw is far more likely to be gathered and relayed than to hit. */
    val directHitRangeMetres: Double = 30.0,
    val riskyRunMarginSeconds: Double = 0.34,

    /**
     * How badly a batter misjudges the margin on a run, in seconds, for the
     * best runner in the game and the worst.
     *
     * A batter cannot see the pick-up, the turn or the strength of the arm. He
     * calls off the ball and commits, and what he commits to is an *estimate*.
     * Run outs in this model are that estimate turning out to be wrong — a
     * misjudgement — rather than a dice roll over a run he could plainly see he
     * was going to lose. Before this, a batter knew the true margin and set off
     * anyway 79% of the time when it was already red, and run outs ran at
     * 8-9% of dismissals against a band of 4-6%.
     *
     * Widening this makes running between the wickets more dangerous in every
     * format at once; narrowing it makes it nearly safe and pushes dismissals
     * back onto the bowlers.
     */
    val runJudgementSigmaBest: Double = 0.13,
    val runJudgementSigmaWorst: Double = 0.38,

    /**
     * How far behind the throw a batter has to be, in seconds, before a throw
     * that hits gets him every time.
     *
     * This is the physics of the run out and has nothing to do with how brave
     * the batter was: scaling it by his willingness, as it used to be, made a
     * *good* runner more likely to be out for the same true margin, because his
     * willingness figure was smaller.
     */
    val runOutCertaintySeconds: Double = 0.80,

    /**
     * Seconds of margin a batter wants before he sets off without thinking.
     *
     * Without it the model took a run whenever one was arithmetically possible,
     * which turned every push to a close fielder into a single and left the
     * game with too few dots and too few boundaries at the same time. Real
     * batters refuse a lot of technically available singles.
     */
    val comfortableRunMarginSeconds: Double = 0.74,

    /** Chance a batter takes a tight single anyway, at running judgement 50. */
    val tightSingleAppetite: Double = 0.49,

    /** LBW: how sharply a clear decision becomes an out. */
    val lbwDecisionSlope: Double = 4.0,

    /**
     * How badly an umpire misjudges the margin, at the top of the ladder and at
     * the bottom, in the same units the tracking uses.
     *
     * **Noise on the margin, not a coin flip on the verdict.** That distinction
     * is the whole reason a review system is worth simulating. A flat error
     * probability - which this was - turns a plumb lbw into not out at the same
     * rate it turns a marginal one, so clear mistakes essentially never happen
     * and there is nothing for a review to catch: measured, over half of all
     * reviews came back umpire's call and one in six was overturned, against
     * roughly a quarter and a quarter in real cricket.
     *
     * Misjudging the *margin* puts the errors where they really are: on the
     * balls that were close, and above all on height, which is the thing a
     * standing umpire genuinely cannot see.
     */
    val umpireMarginSigmaBest: Double = 0.55,
    val umpireMarginSigmaWorst: Double = 1.60,

    /**
     * How often a keeper completes a stumping when the batter is out of his
     * ground and has missed it.
     *
     * Stumpings are only 1-3% of all dismissals, so this is small: most balls
     * that beat a batter who has come down the pitch are still gathered too late,
     * or he gets back.
     */
    val stumpingCollectionBase: Double = 0.20,

    /** Byes: probability the keeper lets a beaten ball through, at glovework 50. */
    val byeBase: Double = 0.042,
)

/** The pressure index, consumed by four stages. */
data class PressureTuning(
    val chaseWeight: Double = 0.30,
    val wicketWeight: Double = 0.28,
    val dotWeight: Double = 0.18,
    val phaseWeight: Double = 0.14,
    val occasionWeight: Double = 0.10,

    /** Logistic shaping of the weighted sum. */
    val slope: Double = 1.9,
    val intercept: Double = 0.95,

    /** Balls over which a recent wicket keeps mattering. Two in two is far more than two in fifty. */
    val wicketClusterScaleBalls: Double = 24.0,

    /** Balls of history in the dot-ball term. */
    val dotWindowBalls: Int = 12,

    /** Exponent on wickets lost. */
    val wicketExponent: Double = 1.4,
)
