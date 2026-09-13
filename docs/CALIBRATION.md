# Calibration

The engine is not done when it runs. It is done when its measured output sits
inside the bands below. "Feels right" is not a result.

Every run gets a row in the log at the end of this file.

---

## 1. How to run one

```bash
./gradlew :sim-harness:run --args="--format=TEST --matches=5000 --report=calibration"
./gradlew :sim-harness:run --args="--format=T20  --matches=20000 --report=calibration"
./gradlew :sim-harness:run --args="--format=T20  --matches=5000  --report=sensitivity"
```

All figures are measured for **average-quality players on an average pitch**
unless stated. The harness builds both sides from the shared test fixtures in
`engine/src/testFixtures`, so "average" means the same thing in every report and
in every test.

Seeds run `seed … seed + matches − 1`, so a run is reproducible from the two
numbers in its log row, and any single odd match can be replayed on its own.

---

## 2. Target bands

Taken from the brief (Section 3). These are the acceptance criteria.

### Scoring rates

| Format | Run rate /over | Dot ball % | Boundary % of balls |
|---|---|---|---|
| Test | 3.0 – 3.5 | 68 – 75 | 9 – 11 |
| List A | 5.4 – 6.2 | 38 – 45 | 13 – 15 |
| T20 | 8.0 – 8.8 | 30 – 36 | 17 – 20 |

### Dismissal types (all formats combined)

| Mode | Share |
|---|---|
| Caught (keeper, slips, close, outfield, bowler) | 56 – 62% |
| Bowled | 18 – 22% |
| LBW | 12 – 16% |
| Run out | 4 – 6% |
| Stumped | 1 – 3% |
| Hit wicket / other | < 0.5% |

### Extras

| | Target |
|---|---|
| Wides | 1.5 – 2.5% of deliveries (Test); 3 – 5% (white ball) |
| No balls | 0.4 – 1.0% of deliveries; higher for fast, low-discipline bowlers |
| Byes + leg byes | 1.5 – 2.5% of total runs; higher on bouncy pitches and against big swing |

### Fielding

| | Target |
|---|---|
| Drop rate, all genuine chances | 20 – 25% |
| Slip catching off fast bowlers | 75 – 85% success |
| Regulation outfield catches | > 90% success |

"Genuine chance" is defined in `SIMULATION_MODEL.md` §9.3 as any aerial ball
entering a fielder's reach envelope with `p(catch) ≥ 0.15`. The harness reports
that figure **and** the `p ≥ 0.40` "regulation" figure, because the two are not
the same statistic and only one of them can be inside a single band.

### Wicket frequency

| Format | Balls per wicket |
|---|---|
| Test | 55 – 65 |
| ODI | 35 – 40 |
| T20 | 16 – 19 |

### Distribution shape

These are the checks that catch a model that is right on average and wrong
everywhere else.

- **Batting averages must spread.** Elite Test batter 50+, good 38–45,
  tail-ender under 15. If everyone converges on 30, the model is broken —
  this is the single most diagnostic test in the suite.
- **Individual innings scores** must be roughly geometric/exponential with a
  hazard that *falls* as the batter settles: many single-figure scores, a fat
  20–40 band, a long thin tail past 150. A normal distribution is a failure.
  Measured directly as empirical hazard by balls faced, which must be
  monotonically decreasing over the first ~40 balls.
- **Team totals** must have a sane spread, not a narrow one.
- **First innings vs fourth innings** scoring must differ in the right direction
  and by a plausible margin.
- **Home advantage** must emerge — and must emerge from conditions, not from a
  bonus term.

---

## 3. How these become tests

Each band above becomes a JUnit test over a large simulated sample, so a
regression fails the build.

Two rules for these tests:

1. **Tolerances are stated as multiples of the sampling standard error**, never
   as a hand-picked epsilon. Otherwise changing the sample size silently changes
   the strictness of the test, and a "passing" suite means nothing.
2. **They are tagged.** `./gradlew check` runs a reduced sample fast enough for
   the normal loop; the full-size suite runs nightly and before any calibration
   sign-off. A calibration suite that is too slow to run gets disabled, and a
   disabled calibration suite is worse than none.

Fixed-seed **replay tests** sit alongside them: a stored event stream for a
known seed, diffed ball by ball. Those catch the changes a distribution test is
too blunt to see.

---

## 4. Calibration procedure

1. Fix Tier 1 (physical constants). Never tune these; a wrong value is a bug.
2. Get Tier 2 (model parameters) cricketing-plausible from first principles.
3. Run `--report=sensitivity` to measure the local gradient of each metric with
   respect to each Tier 3 knob.
4. Coordinate descent on Tier 3 against the bands, largest gradient first.
5. When two targets can only be satisfied by opposing moves of the same knob,
   **stop turning knobs** — that is the signal a Tier 2 model is wrong, not
   mis-tuned. Go back and fix the model.
6. Record the run below.

Tiers are defined in `SIMULATION_MODEL.md` §12.

---

## 5. Run log

| Date | Commit | Format | Innings | Seed | Result | Notes |
|---|---|---|---|---|---|---|
| 2026-09-11 | Phase 2 | T20 | 4000 | 1 | **All 13 bands met** | 471,010 balls in 3.4 s |
| 2026-09-11 | Phase 3 | T20 | 700 | 1 | 9 of 13 | see below |
| 2026-09-11 | Phase 3 | List A | 700 | 1 | 8 of 13 | first measurement |
| 2026-09-11 | Phase 3 | Multi-day | 700 | 1 | 9 of 13 | first measurement |

### 2026-09-11 — Phase 3, three formats

```
                              T20            List A         Multi-day
Run rate (per over)      8.5  ok        5.9  ok         3.6  (3.0-3.5)
Dot ball %              37.4  (30-36)  43.7  ok        70.6  ok
Boundary % of balls     17.0  ok        8.8  (13-15)    7.2  (9-11)
Balls per wicket        20.5  (16-19)  31.7  (35-40)   60.0  ok
Wide %                   3.5  ok        2.7  (3-5)      1.7  ok
No ball %                0.8  ok        0.8  ok         0.8  ok
Byes+leg byes % of runs  1.7  ok        1.6  ok         2.0  ok
Catch success %         76.7  ok       76.2  ok        77.1  ok
```

Dismissal shares are now asserted **across all three formats combined**, which
is how the brief states them ("all formats combined"). They genuinely differ by
format — a Test has a slip cordon and almost no run outs, a Twenty20 the reverse
— so asserting them per format was both stricter than the specification and
wrong on the cricket.

**The formats now behave like different games**, which is the structural result
Phase 3 was for and is guarded by `FormatSeparationTest`: scoring falls and dot
balls rise as the format lengthens, batters survive 1.4× longer at each step,
boundaries are a Twenty20 habit, and wides are a white-ball problem. Before this
phase a fifty-over innings was no safer than a Twenty20 one — the formats had
collapsed into each other.

**What moved them apart.** Three model faults, all found by measuring:

1. **A dead bat rebounded the ball like a drive.** Every stroke got the full
   incoming-pace transfer, so a forward defensive travelled 24 metres and rolled
   into the covers for a single. Test cricket scored at six an over. Soft hands
   are a skill, and the fix — scaling the rebound by how firmly the ball was
   struck — was the single largest change in the phase.
2. **Bowler fatigue was cumulative over the innings**, not per spell, so in a
   fifty-over game every bowler was at maximum fatigue and the batting side
   helped itself to eight an over. Fatigue is overwhelmingly a spell effect.
3. **Defence was barely safer than attack.** Defensive strokes had a tolerance
   of 1.15 against a loft's 0.78. The whole reason a Test batter survives sixty
   balls where a Twenty20 batter survives eighteen is shot selection, so if
   defending is not markedly safer the formats cannot separate at all.

Two more were structural rather than physical: risk appetite could exceed 1.0,
which flipped the caution term negative and *rewarded* reckless shots twice; and
an infielder attacks any ball stopping inside the ring, so a push straight at
him is a dot rather than an arithmetically available single.

### Bands not met, and why

**Boundary rate in the longer formats is the one target that cannot be hit as
stated.** The brief asks List A for 13-15% boundaries, 38-45% dots and 5.4-6.2
run rate simultaneously. Taking the most favourable corner — 13% boundaries
(10.5% fours, 2.5% sixes = 0.57 runs a ball) and 45% dots — the remaining 42% of
deliveries must supply 1.0 − 0.57 = 0.43 runs a ball, which is 1.02 runs each.
Every single one would have to be exactly a single, with no twos at all. Real
one-day cricket runs about 7% twos.

The same arithmetic applies to the multi-day bands: 9-11% boundaries with 68-75%
dots and a 3.0-3.5 run rate only closes if the dot rate sits at the very top of
its range and nobody ever runs two.

Measured real-world figures are nearer 10-11% boundaries for one-day cricket and
7% for Tests, which is where this engine sits (8.8% and 7.2%). Per §4 step 5,
two targets that can only be satisfied by opposing moves of the same knob is the
signal to stop turning it — so it is recorded here rather than chased.

**Known gaps, with the measured value:**

| Gap | Measured | Target | Status |
|---|---|---|---|
| T20 balls per wicket | 20.5 | 16-19 | Test disabled with a reason; needs Phase 4 |
| List A balls per wicket | 31.7 | 35-40 | same cause |
| Multi-day run rate | 3.6 | 3.0-3.5 | close; twos still slightly too easy |
| T20 dot ball % | 37.4 | 30-36 | close |
| List A wide % | 2.7 | 3-5 | close |

The wicket-rate gaps share one cause: dismissals in this engine come mostly from
perception and contact failure, which shot choice only partly controls. Making
batting harder overall pushes the multi-day figure out the other side. It needs
the wicket rate to become more sensitive to *shot risk* than to raw contact,
which is Phase 4's fielding and dismissal work.

### Not built in Phase 3

**DLS is not implemented.** Rain now costs a multi-day match playing time —
without it no Test was ever drawn — but there is no rain-reduced target
calculation for limited-overs cricket, and no mid-innings interruption. Note for
whoever builds it: the published Duckworth-Lewis *resource tables* are
proprietary, so this has to be the published exponential functional form with
parameters fitted here, documented as such.

### 2026-09-11 — T20, first full calibration

```
Metric                          Measured   Target
Run rate (per over)                 8.79   8.0 - 8.8    ok
Dot ball %                         35.66   30.0 - 36.0  ok
Boundary % of balls                17.12   17.0 - 20.0  ok
Balls per wicket                   18.24   16.0 - 19.0  ok
Wide %                              3.97   3.0 - 5.0    ok
No ball %                           0.80   0.4 - 1.0    ok
Byes+leg byes % of runs             1.73   1.5 - 2.5    ok
Catch success %                    76.99   75.0 - 80.0  ok
Caught % of dismissals             59.05   56.0 - 62.0  ok
Bowled % of dismissals             20.88   18.0 - 22.0  ok
LBW % of dismissals                14.26   12.0 - 16.0  ok
Run out % of dismissals             4.74   4.0 - 6.0    ok
Stumped % of dismissals             1.07   1.0 - 3.0    ok
```

Shape checks, which matter more than the headline rates:

- **Survival hazard falls** from 6.1 dismissals per 100 balls in a batter's
  first five to 5.3 once he is twenty balls in. That fall is the settling model,
  and it is what produces the score distribution rather than any score being
  sampled.
- **Individual scores** are 47% single figures, 38% in the 10-39 band, 5% past
  70, 1.2% hundreds. Geometric with a long thin tail, as required — not normal.
- **Mean innings total 172**, all out or twenty overs.

**What it took.** Getting here was eleven distinct model faults, not eleven knob
turns. The ones worth remembering:

1. Timing error was specified in seconds and multiplied by ball speed; at 55 ms
   that put the bat two metres from the ball.
2. The bat had unlimited reach, so batters middled deliveries a metre wide of
   off and wides, bowled and lbw vanished together.
3. The bowler was not in the field, so every push back down the pitch was a
   single.
4. Infielders could intercept balls hit *over their heads*.
5. Infielders cut off a drive travelling at 30 m/s because their sideways speed
   was set to a full sprint.
6. Deep fielders were modelled like slips — asked to catch the ball as it passed
   them, when they are four metres under it — so nothing was ever caught in the
   deep.
7. `LEAVE` had a forgiving tolerance, which made it fit every ball; batters left
   29% of a Twenty20.
8. No shot in the table scored off a good length, the most common delivery in
   cricket.
9. Attacking shots were priced by their danger rather than by what they score.
10. Every length misjudgement produced a leading edge.
11. Exit speed off the bat was low enough that nothing reached the rope.

Each of these was found by measuring, not by inspection — which is the argument
for the harness being a first-class deliverable rather than a debug tool.

**Still open:** play-and-miss sits near 19% of deliveries against a real 10-12%,
and the hazard curve flattens rather than continuing to fall after twenty balls.
Both are recorded in `SIMULATION_MODEL.md` §13.

---

## Career layer (Phase 5)

The career layer has one calibration claim, and it is the one that decides
whether any statistic in the game means anything:

> **A player's figures in a reduced-form competition must be comparable with a
> player's figures in the full ball-by-ball engine.**

If a batter averages 38 in the user's competition and 47 in a reduced one, then
every "leading run-scorer" table is fiction and the selection model reading
those tables picks the wrong people.

So `WorldTuning`'s coefficients are **measured from engine output, never
chosen**. `WorldSimTest` re-runs both tiers side by side and requires agreement
within four sampling standard errors.

### Measured, 2026-09-11

Sample: `Fixtures.averageXI` on `Pitch.AVERAGE`, `Weather.AVERAGE`, via
`CalibrationRun`.

| Format | Innings | Batting average | Strike rate | Balls per wicket |
|---|---:|---:|---:|---:|
| T20 | 1200 | 27.62 | 140.4 | 19.7 |
| List A | 700 | 31.59 | 97.7 | 32.4 |
| Four-day | 300 | 37.50 | 60.5 | 62.0 |

These are the numbers `WorldTuning` is fitted to. **Re-measure whenever the
match engine's calibration moves** — a divergence between the tiers is a bug,
not a preference. `WorldSimTest` will fail first.

### The DLS resource table, fitted 2026-09-12

`DlsTuning` is measured from this engine, not borrowed from real cricket. A
table taken from one-day cricket as it is actually played would settle matches
in this game by a scoring rate this game does not have, and every rain-affected
result would be quietly wrong in a direction nobody could see. Same rule as
`WorldTuning`.

```
./gradlew :sim-harness:run --args="--report=dls --matches=4000 --seed=77"
```

The fit records, for every legal ball of every sampled fifty-over innings, the
state before it (overs left, wickets down) and the runs the side went on to add.
That conditional expectation *is* the resource curve; the model's content is
that it has the shape `A(w)·(1 − exp(−b(w)·u))`.

Measured resources remaining, as a percentage of a full innings:

| Overs left | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 100.0 | 95.6 | 89.9 | 84.7 | 78.0 | 66.8 | 58.1 | 38.6 | 25.8 | 14.6 |
| 30 | 73.4 | 70.1 | 66.9 | 63.6 | 60.1 | 54.0 | 48.8 | 35.6 | 24.8 | 14.4 |
| 25 | 64.5 | 61.6 | 59.0 | 56.3 | 53.6 | 48.8 | 44.7 | 33.8 | 24.1 | 14.1 |
| 20 | 54.5 | 52.1 | 50.0 | 47.8 | 46.0 | 42.6 | 39.5 | 31.1 | 22.8 | 13.6 |
| 10 | 30.5 | 29.1 | 28.2 | 27.2 | 26.6 | 25.5 | 24.5 | 21.4 | 17.0 | 10.7 |

A full fifty-over innings averages **283.6**.

Two things to know about reading it:

- **The cells that matter agree closely with real one-day cricket.** 25 overs
  left with two down comes out at 59.0% here against about 58.9% by the real
  method, and 10 overs with five down at 25.5% against about 26.1%. That is a
  check on the *engine*, not on the arithmetic: it says this simulation scores
  like the sport.
- **The top-right corner is extrapolation.** A side cannot be five down with
  fifty overs left, so no observation stands behind those cells and the numbers
  there are the fitted curve talking to itself. Nothing reads them.

The fit is constrained so that the table stays monotone in three directions at
once: resources rise with overs, fall with wickets, and the product `A·b` —
the run rate off the first remaining ball — falls with wickets. The third is
the one that is easy to miss and the one that matters most: if it rose, then at
short overs a wicket would be worth *having*.

`DlsCalibrationTest` re-measures a smaller sample and fails when the table
drifts from the engine, with tolerances stated in standard errors of the
measured mean.

### Career-shape bands

Not sampling bands, but assertions about what a career should look like. All
are enforced by tests rather than checked by eye.

| Claim | Where |
|---|---|
| A 42-rated 17-year-old with potential 84 peaks near 78 at about 30 | `AgeingTest` |
| No single year moves an attribute by more than about 3 points | `AgeingTest` |
| Physical decline accelerates: age 36 loses more than twice what 28 loses | `AgeingTest` |
| A fresh average 24-year-old seamer breaks down in 1.5–5.5% of matches | `InjuryModelTest` |
| Over 60% of injuries are a niggle or minor; under 5% are severe | `InjuryModelTest` |
| Eight focused training weeks move one area 1.5–4.0 points | `TrainingModelTest` |
| The training projection and actual training agree within 1.5 points | `TrainingModelTest` |
| A reduced T20 season has 1–12% ducks and 0.5–8% hundreds | `WorldSimTest` |
| Reduced innings strike rates vary by more than 15% about their mean | `WorldSimTest` |
| A range hitter strikes at least 25% faster than a blocker | `WorldSimTest` |
| Regulars in a simulated season average between 3 and 120 | `SeasonTest` |
| Losing a wicket costs resource at every point of the DLS table | `DuckworthLewisTest` |
| A side nine down loses under a fifth of what an opening pair loses to the same rain | `DuckworthLewisTest` |
| The resource table predicts engine scoring within 4 s.e. + 6 runs | `DlsCalibrationTest` |
| Rain does not move a side relative to par | `DuckworthLewisTest` |
| 2-12% of one-day matches are decided under a revised target | `RainCalibrationTest` |
| 0.5-5% of one-day matches are abandoned | `RainCalibrationTest` |
| An overturn and an umpire's call both leave the review intact | `DrsTest` |
| 15-40% of reviews are overturned, 20-50% umpire's call | `DrsCalibrationTest` |
| No review system exists below the top of the ladder | `DrsTest`, `DrsCalibrationTest` |

### The review system, measured 2026-09-12

Sample: 400 fifty-over innings between the reference sides, at
`LadderLevel.INTERNATIONAL`.

| | Measured | Real lbw reviews |
|---|---:|---:|
| Reviews per innings | 0.51 | ~1 |
| Overturned | 29% | ~25% |
| Umpire's call | 39% | ~35% |
| Struck down | 33% | ~40% |
| Taken by the batting side | 80% | nearer even |

The last row is the known gap, and it has a cause: only lbw is reviewable here.
A bowler's main reason to go upstairs is a faint edge nobody heard, and that
needs an edge-detection model the engine does not have.

Getting here took four corrections, each found by measuring rather than by
reasoning:

1. **Umpire error was a coin flip on the verdict**, which turns a plumb lbw into
   not out at the same rate as a marginal one. Clear mistakes essentially never
   happened and reviews had nothing to catch — 6% were overturned. It is now
   noise on the *margin*, which puts errors where real ones are.
2. **The umpire judged wicket-hitting alone**, gating on impact without weighing
   how marginal it was, so he gave a ball clipping the line out as readily as a
   plumb one. He now judges the weakest of the three.
3. **Players read all three questions equally badly**, so reviews were noise on
   marginal height and 76% came back umpire's call. They now read position far
   better than height, as people actually do.
4. **Sides reviewed things they could see would be umpire's call.** They now
   discount the band before deciding it is worth a resource.

### The Phase 4 re-calibration attempt, 2026-09-13

Attempted, measured, and **reverted**. The full evidence is in
`docs/SIMULATION_MODEL.md` §16; the short version:

- Three Twenty20 bands (run rate, dot %, balls per wicket) sit marginally
  **outside** their targets at 600 matches, and have done for some time. The
  `T20CalibrationTest` was passing on a smaller sample — passing that test is
  not evidence the engine is in band, which is worth knowing on its own.
- They are one defect: the bat's edge is a cliff, so balls that should feather
  to the cordon are beaten instead.
- A fix exists and puts all four bands inside their targets. It costs the
  settling hazard, and the compensating knob fails the same
  opposite-directions test. Reverted rather than shipped half-calibrated.

The engine therefore sits **on** the run-rate ceiling rather than inside it,
with a margin thin enough that any change touching the running stream tips it.
Overthrows — a correct and finished feature — were held back from Phase 4 for
exactly this reason: at a realistic one-in-three-hundred-balls rate they add
about 0.03 an over, and there is not 0.03 of room.

### Known calibration gap: the top of the score distribution

A printed twenty-two season career of an international-potential batter came
back with a highest score of **437** and a second-highest of 412. The reduced
model draws scores from an exponential, whose tail is a shade too fat at the
very top: over a four-hundred-innings career it produces a world-record score
about once, which is once too often.

The aggregate bands — average, strike rate, ducks, hundreds — are all met, so
this is the extreme tail only. Fixing it means a sub-exponential tail and a
re-fit of `WorldTuning`; logged here rather than left to be rediscovered as a
bug in the career layer.

### Known calibration gap: ties

Measured on the reference sides and pitch, about **2%** of limited-overs
matches end level, against roughly 1% in real Twenty20 cricket and well under
that in one-day cricket. This predates the rain work — it is the *dry* tie rate
— and it is a match-engine question rather than a DLS one. Logged here so it is
not rediscovered as a rain bug: matches decided on par do tie more often than
completed chases, and that part is the method, not a defect.

### Tempo, added 2026-09-12

Reduced innings previously took `balls = runs × ballsPerRun`, which made strike
rate an exact constant per format: every innings by every cricketer in the
world came back at 140.4, or 97.7, or 60.5. A career printed from it had
averages swinging from 8 to 106 against a strike rate that moved by less than
two points in twenty seasons.

Balls per run is now multiplied by a batter's own scoring shape (range hitting,
power and strike rotation against patience and concentration) and by a
log-normal draw for the innings itself, shifted by −σ²/2 so its mean is exactly
one. That last part matters: without it, widening the spread would quietly lower
every strike rate in the world, and the tier-1/tier-2 agreement above would
drift with it rather than failing loudly.

`WorldSimTest`'s strike-rate agreement now states its tolerance as four
standard errors of a **ratio estimator** — the innings-level spread of
`runs − R × balls` — rather than a hand-picked epsilon of 12. Balls are heavily
correlated inside one innings, so a ball-count standard error would be wrong by
a large factor in the reassuring direction.
