# CLAUDE.md — Cricket Career

Working agreement for anyone (human or agent) writing code in this repo.
Read this before touching anything. If a rule here conflicts with what looks
convenient, the rule wins or the rule gets changed in a commit of its own.

---

## 1. What this is

An **offline Android career simulator** for a single cricketer. The player
creates one player and lives one career — college cricket to international
cricket. Selection, contracts and results are things that happen *to* him.

The reference point is *Cricket Captain*: text and numbers, statistical depth,
no 3D. The difference is scope — one player, not one team.

**The match engine is the product.** Everything else is a window onto it.

---

## 2. Non-negotiables

These are not preferences. A change that breaks one of them is a bug.

| # | Rule | Enforced by |
|---|---|---|
| 1 | `:engine` has zero Android dependencies | Module is `kotlin("jvm")`; `DeterminismConventionsTest` |
| 2 | Same seed + same inputs ⇒ byte-identical result, forever | `SimRandomTest`, `MatchRandomTest`, replay tests |
| 3 | No unseeded randomness, no clock reads, no hash-order iteration in `:engine` | `DeterminismConventionsTest` |
| 4 | Every output is calibrated against measured bands, not vibes | JUnit calibration suites, `docs/CALIBRATION.md` |
| 5 | Ball-by-ball simulation. Never an over-by-over shortcut | Code review; `docs/SIMULATION_MODEL.md` |
| 6 | No licensed content — fictional players, generic city team names | Seed data review |
| 7 | No `INTERNET` permission in the manifest, ever | `app/src/main/AndroidManifest.xml` |
| 8 | Tests are written **with** the code, not after it | Code review |

---

## 3. Module boundaries

```
:app  ──────►  :data  ──────►  :engine  ◄────── :sim-harness
(Compose)      (Room)          (pure JVM)       (JVM CLI)
```

Arrows are the **only** permitted dependency directions.

### `:engine` — pure Kotlin/JVM
The domain model, the six-stage delivery pipeline, pitch evolution, the career
progression maths, the selection model, and commentary *generation* (event →
text is a pure function, so it belongs here).

Hard constraints:
- No Android, no I/O, no logging framework, no clock, no global mutable state.
- Everything it needs arrives as a parameter. Everything it produces leaves as
  an immutable value.
- Must run 10,000 matches in a JVM loop without leaking anything.

Its only dependency is `kotlinx-serialization-json`, for the editable seed
database format. Adding a second dependency needs a reason in the commit
message.

### `:data` — persistence
Room entities, DAOs, repositories, save/load, and the mapping between
`:engine` value objects and stored rows. Ships the seed JSON as an asset.

`:engine` must never learn this module exists.

### `:app` — UI
Compose, ViewModels, navigation. **Contains no cricket logic.** If a rule about
cricket is being decided in `:app`, it is in the wrong place. The UI reads
engine events and renders them; it does not interpret them.

### `:sim-harness` — the calibration instrument
A first-class deliverable, not a debug tool. Runs bulk simulations on the JVM
and prints the reports that Section 3 of the brief judges the engine by.

```
./gradlew :sim-harness:run --args="--format=TEST --matches=5000 --report=calibration"
```

### Shared test fixtures
`:engine` uses the `java-test-fixtures` plugin. Average players, average
pitches and standard field settings live in `engine/src/testFixtures` so that
`:engine`'s tests, `:sim-harness` and `:data` all calibrate against the *same*
"average" — a divergence there would make every comparison meaningless.
Fixtures can never leak into a release artifact.

---

## 4. The determinism contract

This is the rule most likely to be broken by accident, so it gets its own
section.

**A match is a pure function of `(seed, inputs)`.** That gives us:
- bug reports that reproduce from a single number,
- regression tests over 10,000-match samples,
- save files that store a seed instead of a scorecard.

### One stream per stage

`MatchRandom(seed)` fans a match seed out into independent named streams
(`RngStreams.EXECUTION`, `RngStreams.FIELDING`, …). **Every stage draws only
from its own stream.**

This exists so that changing the swing model leaves the fielding, running and
umpiring streams bit-identical. Without it, any change to any stage invalidates
every regression baseline in the project, and calibration becomes guesswork.

Practical rules:
- Get a stream once and hold it. Do not re-derive per ball.
- Never pass a bare `SimRandom` "for convenience" into a stage that already has
  its own — that couples the two.
- Adding a draw to a stage is fine. Adding a draw to *someone else's* stage is
  a breaking change.

### Banned in `:engine`
`kotlin.random.Random` / `Random.Default` / `java.util.Random` /
`ThreadLocalRandom` / `Math.random`, `System.currentTimeMillis()` /
`nanoTime()` / `Instant.now()` / any `*.now()`, `HashMap` / `HashSet`
(unspecified iteration order), `println` and friends, `Thread` / `GlobalScope`
/ `Dispatchers.*`, anything `android.*`.

`DeterminismConventionsTest` fails the build on all of these. If a use is
genuinely justified, put `determinism-ok: <reason>` on the same line — the
point is that it has to be a decision rather than an accident.

### Parallelism
Whole matches run concurrently, each with its own seed and its own
`MatchRandom`. Generators are never shared across threads. The harness owns
threading; the engine runs on its caller's thread.

---

## 5. Coding conventions

**Magic numbers.** Every tunable constant in the engine lives in a named,
documented field in the tuning config — never inline at a call site. Each one
carries a comment saying *why* that value and what moving it does. If you
cannot say why, you do not yet know the number.

```kotlin
/**
 * Base standard deviation of a bowler's length error, in metres, for a bowler
 * with accuracy 50 bowling a stock ball, unfatigued, no pressure.
 * Raising this widens the spread of lengths, which lifts both boundary rate
 * and wicket rate. Calibrated against Test dot-ball % (target 68-75%).
 */
val lengthErrorSigmaBase: Double = 0.62
```

**Comment the cricket, not the Kotlin.** The type signature already says what
the types are.

```kotlin
// Outside edge carries to second slip only if the deviation put the ball
// beyond the bat's face AND the elevation kept it above knee height at
// the cordon - anything flatter dies in front of the fielder.
```

**Small, separable stages.** It must be possible to replace the swing model
without touching the fielding model. Each stage takes an input value and
returns an output value; no stage reaches into another's internals.

**Immutability by default.** `data class` + `val`. Mutable state is confined to
the innings/match accumulators and the RNG.

**Doubles inside, Ints outside.** Attributes are `Int` 1–100 in the model and
in save files. The engine normalises to `Double` at the boundary and works in
normalised units throughout.

**When unsure how a real cricket situation resolves, ask.** Do not guess a
cricket rule. Getting the follow-on or a wide-line judgement wrong is a bug
that will not show up in a distribution test.

**Commit after each working sub-step**, with a message that says what changed
in the simulation, not just what changed in the files.

---

## 6. Testing

Three layers, all required:

1. **Unit tests** — a stage in isolation, with a fixed seed. Fast.
2. **Property/invariant tests** — things that must hold for every simulated
   match: runs scored equal runs conceded, ten wickets ends an innings, no
   batter faces a ball after being dismissed, the over count reconciles, extras
   reconcile. Run these over a few thousand seeds.
3. **Calibration tests** — large samples measured against the tolerance bands in
   `docs/CALIBRATION.md`. These are slow; tag them so `check` runs a reduced
   sample and CI nightly runs the full one.

Statistical tests must state their tolerance as a multiple of the sampling
standard error, not a hand-picked epsilon, so that changing the sample size
does not silently change the strictness.

---

## 7. Build

```bash
./gradlew check                      # everything that runs on a bare JDK
./gradlew :engine:test
./gradlew :sim-harness:run --args="--format=T20 --matches=5000 --report=calibration"
```

### Android modules and the SDK

`settings.gradle.kts` includes `:data` and `:app` **only when an Android SDK is
present** (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in
`local.properties`). Force it either way with `-Pcricket.includeAndroid=true|false`.

Why: without this, a machine with no SDK cannot configure the build at all, and
`:engine:test` — the thing 99% of the work needs — becomes unrunnable. CI has
two jobs to match: a JVM job that always runs, and an Android job that installs
an SDK and opts in.

**`:data` and `:app` have never been compiled.** They were scaffolded in an
environment with no Android SDK. Treat their build files as declared intent to
be verified the first time the project is opened with an SDK present.

---

## 8. Phase plan

Work one phase at a time. At the end of each: stop, show the work, run the
tests, wait for approval.

| Phase | Contents | Status |
|---|---|---|
| 0 | Repo skeleton, Gradle multi-module, CI, determinism core, docs | Done |
| 1 | Core data model, serialization, fictional player generator | Done |
| 2 | Match engine v1: full six-stage pipeline, T20 only, hit T20 calibration | **Done — awaiting review** |
| 3 | List A and multi-day: pitch evolution, new ball, declarations, follow-on, DLS, weather | Not started |
| 4 | Fielding, catching, run outs, DRS in full detail; re-calibrate | Not started |
| 5 | Career layer: ladder, selection AI, training, form, fatigue, injury, ageing, contracts, world season sim | Not started |
| 6 | Compose UI: match view, scorecard, charts, career hub, stats, inbox, training | Not started |
| 7 | Save/load, multiple careers, difficulty, editable database, records, retirement | Not started |
| 8 | Performance, battery, APK size, accessibility, polish | Not started |

---

## 9. Where the design lives

| Document | Contents |
|---|---|
| `docs/ARCHITECTURE.md` | Module graph, data flow, event model, save/replay, world-simulation tiering, performance budget |
| `docs/SIMULATION_MODEL.md` | The match engine maths: coordinate system, all six stages, pitch model, pressure |
| `docs/CALIBRATION.md` | Target bands, measurement method, and a log of every calibration run |
| `docs/OPEN_QUESTIONS.md` | Decisions that need the project owner, each with a working default |

### Decisions already taken

- **World simulation is three-tiered** (Q1). Full ball-by-ball for the user's
  matches and his direct rivals; a reduced-form model for the rest of the world
  whose parameters are fitted from engine output, never hand-authored.
- **One full domestic pyramid** (Q2), other nations at international level only.
  The ladder is seed data, not Kotlin.
- **The user sets posture and instructions, not shots** (Q3). His settings enter
  Stage 4 as terms in the same shot-selection maths an AI batter uses — never a
  parallel code path.

Design docs are written **before** the code they describe, and updated in the
same commit as any change that contradicts them.
