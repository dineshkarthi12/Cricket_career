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
