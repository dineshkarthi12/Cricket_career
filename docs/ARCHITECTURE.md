# Architecture

How Cricket Career is put together and why. Module rules and the determinism
contract are in `CLAUDE.md`; the maths is in `SIMULATION_MODEL.md`. This
document covers the structure everything else hangs off.

---

## 1. The shape of the system

```
        ┌──────────────────────────────────────────────┐
        │  :app        Compose only: layout, colour,   │
        │              navigation, animation           │
        └──────────┬───────────────────────┬───────────┘
                   │ UiState               │ Flow<value objects>
        ┌──────────▼────────────┐  ┌───────▼──────────────────────┐
        │  :presentation        │  │  :data                       │
        │  what a screen shows  │  │  Room, repositories,         │
        │  pure Kotlin/JVM      │  │  save/load, seed JSON        │
        └──────────┬────────────┘  └───────┬──────────────────────┘
                   │                       │ value objects
        ┌──────────▼───────────────────────▼───────────┐
        │  :engine     domain model, match simulation, │
        │              progression, selection, text    │
        │              pure Kotlin/JVM, deterministic  │
        └───────────────────────▲──────────────────────┘
                                │
        ┌───────────────────────┴──────────────────────┐
        │  :sim-harness   bulk simulation, calibration │
        └──────────────────────────────────────────────┘
```

The engine is the only place cricket is decided. Everything above it either
stores what the engine said or draws it.

`:presentation` sits between the two for a reason worth stating plainly: it is
the layer where "what does this screen show" is decided, and that question has
wrong answers — a chase equation that divides by the balls bowled, a worm
plotted against deliveries so it drifts right for every wide, a scorecard
reading `b b Kadam`. None of those is caught by looking at a screenshot, and
all of them are a unit test on a bare JDK. Keeping them out of `@Composable`
functions is what makes them testable at all.

It is pure Kotlin/JVM and always in the build, so `:app` is Compose and nothing
else — which also makes the part of the product that cannot be compiled without
an Android SDK as small as it can be. See docs/UI.md.

---

## 2. `:engine` package layout

```
com.cricketcareer.engine
├── rng/            SimRandom, MatchRandom, RngStreams
├── model/          Player, Attributes, Team, Venue, Pitch, Squad, Format
│   ├── player/     attributes, dynamic state (form, fatigue, confidence)
│   └── world/      countries, competitions, calendars, the ladder
├── config/         EngineTuning — every magic number, documented
├── match/
│   ├── state/      MatchState, InningsState, BatterState, BowlerState, over/ball bookkeeping
│   ├── pitch/      pitch evolution, weather, dew, ball condition
│   ├── delivery/   the six stages, one package each
│   │   ├── intent/       Stage 1
│   │   ├── execution/    Stage 2
│   │   ├── movement/     Stage 3
│   │   ├── read/         Stage 4
│   │   ├── contact/      Stage 5
│   │   └── outcome/      Stage 6 (trajectory, fielding, umpiring, running)
│   ├── field/      field settings, fielder positions, captaincy AI
│   ├── laws/       powerplays, over limits, follow-on, DLS, super over, points
│   └── event/      BallEvent and the innings/match event stream
├── commentary/     event → text (a pure function; no strings by outcome lookup)
├── stats/          scorecards, aggregates, career records, chart data extraction
├── career/         progression, ageing, training, injury, contracts
├── selection/      the selectors' model and its rationale
└── seed/           editable JSON database schema and loader (no I/O — takes a String)
```

`seed/` deliberately parses from a `String`/`Reader` rather than a file. `:data`
supplies the bytes from Android assets; the harness supplies them from a test
resource. The engine still touches no I/O.

---

## 3. The event model

The single most important structural decision: **the simulation emits a full
causal record of every ball, and everything downstream is a pure function of
that stream.**

```kotlin
data class BallEvent(
    val ballId: BallId,                  // innings, over, ball, legal-ball index
    val striker: PlayerId,
    val nonStriker: PlayerId,
    val bowler: PlayerId,

    val intent: BowlerIntent,            // stage 1: what he was trying to bowl
    val release: Release,                // stage 2: pace, seam angle, execution error
    val delivered: DeliveredBall,        // stage 3: true length, line, movement, bounce
    val perceived: PerceivedBall,        // stage 4: what the batter thought it was
    val shot: ShotSelection,             // stage 4: what he played, and why
    val contact: Contact,                // stage 5: quality + contact point
    val trajectory: Trajectory?,         // stage 6: azimuth, elevation, exit speed
    val fielding: FieldingResolution?,   // stage 6: who, how far, caught/dropped/misfield
    val umpiring: UmpiringDecision?,     // appeals, error, DRS
    val outcome: BallOutcome,            // runs, extras, dismissal
    val situation: SituationSnapshot,    // score, pressure index, field setting, ball age
)
```

This costs memory and buys almost everything else:

- **Commentary** is generated from the fields, not chosen by outcome. "Thick
  outside edge, flew at catchable height through a vacant fourth slip, four" is
  a sentence assembled from `contact.point`, `trajectory.elevation`,
  `fielding.nearestFielder == null` and `outcome.runs` — because those are
  literally what happened.
- **Wagon wheel** = `trajectory.azimuth` per scoring shot. **Pitch map** =
  `delivered.pitchPoint`. **Beehive** = line and height at the stumps. All real
  data, never synthesised for the chart.
- **Debugging** is reading the chain: intent → error → movement → misread →
  edge. When a distribution is wrong, the cause is visible.
- **Regression tests** diff event streams for a fixed seed.

### Retention

Full `BallEvent`s are held only for the match currently being watched. Once a
match is complete it is reduced to a scorecard plus a compact per-ball record
(≈16 bytes: outcome code, line/length bucket, shot, azimuth bucket) which is
enough for career charts. Everything else is recoverable by replaying the seed.

---

## 4. Save, replay and engine versions

A match is a pure function of `(seed, inputs)`, so a save can store the seed
rather than the result. But the engine will keep changing, and a Phase 5 engine
will not reproduce a Phase 3 scorecard from the same seed.

The resolution:

- **History is materialised.** Completed matches store their scorecard and
  compact ball record as data. A career's past is never re-derived and so never
  changes under the player's feet after an update.
- **Seeds are stored anyway**, next to an `engineVersion` stamp. If the version
  matches, a match can be replayed exactly — for bug reports and for the
  "watch it again" feature. If it does not, the stored scorecard is shown and
  replay is disabled.
- **The world's future is seeded, not stored.** The season's fixtures, the
  weather, the opposition's form — all derive from a career seed plus the
  fixture identity, so a save file stays small and a career cannot be
  save-scummed by reloading and re-simulating the same match.

---

## 5. Simulating a world, not just a career

The brief requires a whole cricketing world to move — rivals scoring runs,
incumbents failing, selectors reacting — and Phase 8 requires a full season to
run in seconds on a mid-range phone. Simulating every ball of every match on
earth does not fit in that budget (see §7). So fidelity is tiered:

| Tier | What | Fidelity |
|---|---|---|
| **A** | Matches the user's player is in | Full pipeline, all events retained, commentary |
| **B** | Matches involving direct selection rivals, incumbents, and the user's team when he is not picked | Full pipeline, events discarded, no commentary or chart capture |
| **C** | Everything else in the world | Reduced-form statistical model |

The rule that keeps Tier C honest: **its parameters are fitted from the output
of Tiers A/B, never hand-authored.** A harness task simulates a large sample
with the real engine, fits the reduced-form distributions (innings-score hazard
by player quality, format and conditions; bowler wicket and economy
distributions), and emits them as generated data. Any change to the engine
re-fits them. The consequence is that a Tier C batting average and a Tier A
batting average are drawn from the same world, so a player promoted from
background to foreground does not suddenly change quality.

Tier C is still per-innings, not per-season: individual scores exist, so the
statistics a selector reads are real.

Promotion is dynamic. When a Tier C player becomes a rival for the user's
place, his subsequent matches move to Tier B.

---

## 6. Selection, and why it must explain itself

The selectors' model scores each candidate for a slot as a weighted sum of
named factors — recent form, career record, format fit, conditions fit, age,
fitness, reputation/politics, the incumbent's own form, and squad balance.

It returns not just a decision but the decomposition:

```kotlin
data class SelectionRationale(
    val chosen: PlayerId,
    val runnerUp: PlayerId?,
    val factors: List<FactorContribution>,   // name, weight, candidate delta, points swung
    val decisive: List<String>,              // the factors that actually flipped it
)
```

The text ("The selectors preferred Kulkarni — his record against spin in these
conditions counted heavily") is generated from `decisive`, so the explanation
can never drift from the arithmetic. The player learns the world's logic and
can respond to it, which is the point of the career layer.

---

## 7. Performance budget

Phase 8's requirement — a full world season in seconds on a mid-range phone —
sets the budget backwards from the target.

Assume ~3–5× slower per operation on a mid-range phone than on a dev laptop,
and a target of **≤ 5 s** for an off-screen season.

| | Estimated volume | Budget |
|---|---|---|
| Tier A | ~40 matches/season × ~250–2,700 balls | user is watching; ≤ 1 ms/ball is generous |
| Tier B | ~200–400 matches/season, ~800 balls avg | ~250k balls → needs ≤ 8 µs/ball on device |
| Tier C | ~1,500–2,500 matches/season | ~50 µs/match |

What that implies for the engine, and therefore how it gets written from
Phase 2 rather than retrofitted in Phase 8:

- **No allocation in the per-ball hot path** outside Tier A. Stage outputs are
  either value classes, primitives, or reused mutable scratch objects behind an
  interface that Tier A swaps for a recording implementation.
- **Event capture is a strategy, not a flag check per field.** Tier B installs a
  no-op sink; the branch is one virtual call per ball.
- **Commentary is never generated unless it will be read.**
- **No strings in the hot path.** Enums and codes; text at the boundary.
- Measured with a JMH-style benchmark in the harness from Phase 2, so a
  regression is caught the day it lands, not in Phase 8.

### Measured, Phase 8

The benchmark this section always said should exist now does:

```
./gradlew :sim-harness:run --args="--report=bench --format=T20 --matches=600"
```

It warms the JIT, then runs three alternating rounds of each tier and reports
the best of them, because running one tier to completion and then the next
charges the second for collecting the first's garbage — the first version of
the report duly said tier B was *slower* than tier A, which is impossible and
was entirely an artefact of the measurement.

| | measured | x5 (phone) | budget | |
|---|---|---|---|---|
| Tier A — watched, events kept | 5.10 us/ball | 25.5 us | 1000 us | ok, 39x headroom |
| Tier B — rivals, events discarded | 5.04 us/ball | 25.2 us | 8 us | **3.2x over** |
| Tier C — reduced-form world | 0.28 us/match | 1.4 us | 50 us | ok, 35x headroom |

A whole season off-screen comes to **7.3 s** at the pessimistic x5, and 4.4 s at
the optimistic x3. So the headline requirement — a season in seconds — holds,
and the derived 5 s budget holds only at the optimistic end.

**What the first measurement found.** Two of this section's own rules were being
broken by code that looked like it kept them:

- **The sink strategy saved the branch and not the allocation.** A `BallEvent`
  has sixteen fields and was constructed *before* `accept` could throw it away,
  so a tier-B innings allocated one per ball and handed it to an empty method.
  A sink now declares `wantsEvents` and the simulator does not build one for a
  sink that does not want it.
- **The field's gaps were recomputed twenty times a ball.** Shot selection
  scores every stroke in the book on every delivery, and each score walked the
  whole field to find the gap it would be played into — two hundred-odd angular
  comparisons a ball to answer a question that only changes when the captain
  moves somebody, which he does once an over. `FieldRewards` computes it once
  per field. Worth 17%, and by some distance the largest single win available.

**And one that looked obvious and was not.** A profile put `EffectiveSkill.of`
at a quarter of all samples — shot selection asks for the striker's skill once
per stroke, the bowler's plan once per line and length, and every call re-folds
the same form, confidence and fatigue figures. Memoising it per delivery moved
the measured figure by 0.16 us/ball against run-to-run noise of +-0.15, so it
bought nothing and cost three array allocations a ball. Reverted. The JIT had
already hoisted it, and a profile counting *samples inside an inlined method* is
not the same thing as a cost that can be removed.

**What would close the remaining gap**, in the order the allocation profile puts
them: the per-ball `DoubleArray` pairs behind every softmax (shot selection,
plus delivery type, length and line — six or eight arrays a ball, and by far the
largest source of garbage), then the per-ball value objects each stage returns.
Both are the "reused mutable scratch behind an interface" this section already
specifies and neither was built, because the engine's correctness came first and
the budget was not measured until there was cricket worth measuring. It is a
real piece of work against a freshly calibrated engine, not a tuning pass.

---

## 8. Tuning configuration

Every constant in the engine lives in `EngineTuning`, an immutable data class
with documented fields and a `DEFAULT` instance. It is passed into the
simulation, not read from a global.

That gives three things at once:
- a single documented place to tune the game (the brief's requirement),
- the ability for the harness to **sweep** a parameter and report how each
  calibration metric responds — which turns calibration from guesswork into
  coordinate descent (see `CALIBRATION.md`),
- difficulty settings and per-era variation as data rather than code.

---

## 9. Threading

The engine runs on its caller's thread and has no concurrency of its own.

- `:sim-harness` parallelises across whole matches — one seed, one
  `MatchRandom`, one thread. Generators are never shared.
- `:app` runs simulation on a background dispatcher and exposes `Flow` state.
  A watched match is stepped ball by ball with the UI in control of pace; an
  off-screen season is one background job that reports progress.

---

## 10. The editable database

Seed data ships as JSON (kotlinx.serialization) and is user-editable, per the
brief. Structure:

```
seed/
├── countries.json      countries, regions, states
├── competitions.json   the ladder: formats, calendars, qualification rules
├── venues.json         venues with pitch archetypes and regional conditions
├── teams.json          generic city/region names only — no franchise names, no crests
├── players.json        fictional players with full attributes
└── names/              first- and surname pools per region, for the generator
```

Loading is versioned and validated with readable errors, because a user with a
text editor is an expected user. A malformed edit must say which file, which
record and which field — not throw a serialization stack trace.

No real player names, no real team crests, no real logos. City names ("Chennai",
"Mumbai") are fine; franchise names are not.
