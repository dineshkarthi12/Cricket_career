# The UI

Phase 6. What the player actually looks at, and where the code that decides it
lives.

Written before the code it describes, per CLAUDE.md §9.

---

## 1. The constraint that shaped this

`:app` cannot be compiled in the development container: there is no Android
SDK, and the network policy blocks `dl.google.com`, so neither the SDK nor the
Android Gradle Plugin nor any Compose artifact can be fetched. That is a fact
about the environment, not a design opinion, and it is not going to change by
wishing.

Two ways to respond. Write the whole UI as Compose and ship several thousand
lines that have never been compiled, or **make the part that needs an Android
SDK as small as it can possibly be**.

This document takes the second. A new module:

```
:app  ──────►  :presentation  ──────►  :engine  ◄────── :sim-harness
(Compose,      (pure JVM,              (pure JVM)       (JVM CLI)
 no logic)      what a screen shows)
   │
   └────────►  :data  ─────────────────────┘
               (Room)
```

`:presentation` is pure Kotlin/JVM and is **always in the build**, like
`:engine` and `:sim-harness`. It holds every decision about what a screen
*shows*: the chase equation, the scorecard rows and their how-out notation, the
worm and manhattan series, the grouping of commentary into overs, the ordering
of an attribute list.

`:app` then holds Compose and nothing else — layout, colour, navigation,
animation. If something in `:app` can be wrong about cricket, it is in the
wrong module.

### Why this is not a workaround

It is the rule CLAUDE.md already states, enforced by a module boundary instead
of by good intentions:

> **`:app` contains no cricket logic.** If a rule about cricket is being
> decided in `:app`, it is in the wrong place.

A "chase needs 72 from 34 balls" line is a rule about cricket. Computing it
inside a `@Composable` puts it somewhere no test will ever reach. The bugs this
layer is for are exactly the ones a screenshot does not catch:

- a scorecard reading `b b Kadam` instead of `b Kadam`,
- a required rate that divides by the balls bowled rather than the balls left,
- a worm plotted against deliveries instead of legal balls, so it drifts right
  by one per wide,
- an over strip that shows seven pips and no wide.

Every one of those is a unit test on a bare JDK, and none of them needs an
emulator.

---

## 2. What `:presentation` may and may not do

**May:** read engine value objects and `BallEvent`s; compute derived numbers;
format text; decide ordering and grouping; decide what is emphasised.

**May not:** simulate anything; hold Android types; read a clock; do I/O; know
that `:data` exists.

**One documented exception**, `presentation/demo/DemoMatch.kt`: it runs the
match simulator, because something has to hand the UI a match before `:data`
can load one and the only other candidate is `:app` — the one module that must
never decide anything about cricket. Of two wrong homes it is the less wrong,
it is the single place in the module that touches the simulator, and it goes
when `:data` arrives in Phase 7. Where a value came from is not a presentation concern — a
match centre renders a `MatchState` identically whether it came from a live
simulation or a save file.

It also may not re-derive anything the engine already computed. If the engine
knows the required rate, presentation formats it; it does not recompute it and
risk two answers to one question.

---

## 3. State, not widgets

Each screen has one immutable state object, built by one pure function from
engine values. A composable takes that object and draws it. There is no path by
which a screen reads engine state directly.

```kotlin
data class MatchCentreState(...)           // what the screen shows
fun matchCentre(match: MatchState, ...): MatchCentreState   // pure
```

That shape is what makes the layer testable: the test asserts on a data class,
not on a rendered tree.

---

## 4. Screens

Taken from the screen direction board, in the order a career meets them.

| Screen | State | Notes |
|---|---|---|
| Home | `HomeState` | Player card, season figures, quick actions |
| My Player | `PlayerProfileState` | Bio, attribute bars grouped, potential — hidden attributes never appear |
| Calendar | `CalendarState` | Fixtures with format chips |
| Squad | `SquadState` | Rows with role and rating; the user's row marked |
| Pitch report | `PitchReportState` | The engine's own `Pitch` fields, named as a report |
| Conditions | `ConditionsState` | `Weather` fields, rain, DLS revised target |
| Match centre | `MatchCentreState` | Score, chase equation, this over, commentary feed |
| Scorecard | `ScorecardState` | Batting and bowling rows, extras, total |
| Analysis | `ChartState` | Worm, manhattan, pitch map |
| Review | `ReviewState` | Pitching / impact / wickets, from the delivery geometry |
| Training | `TrainingState` | Week allocation, projection |
| Career | `CareerTableState` | Season rows from `SeasonRecord` |
| Inbox, Transfers, Legacy | — | Phase 7 |

---

### The four screens added with Phases 3–5

| Screen | State | What it has to explain |
|---|---|---|
| Review | `ReviewState` | All three lbw questions, always, in the order a big screen shows them, with the one that made it umpire's call picked out. Showing only the leg that failed leaves a viewer who has seen it on television wondering about the other two. |
| Rain | `RainState` | Why the target is a number that is not the opposition's score plus one. "Seven overs lost, target revised to 292" is the whole story, and a scoreboard without it looks broken. |
| Training | `TrainingState` | What a plan is worth over a *block* of weeks, and where the ceiling is. One week moves an attribute by a fifth of a point, which on a screen looks like nothing happening. |
| Selection | `SelectionState` | Why he is or is not in the side, in the panel's own weighted terms. A player is never told "not selected" and left to guess. |

Two things these share, and both are load-bearing:

- **They re-decide nothing.** The selection screen shows the same
  `Selection.score` the panel ran and the training screen calls the same
  `TrainingModel.project` the model runs. A screen with its own copy of the
  arithmetic eventually disagrees with the save file, and the disagreement is
  invisible.
- **They show the comparison, not the raw number.** A batter's suitability
  carries his ability in it, so showing it against a flat 0.5 labelled every
  good player as suited to every surface — the conditions line became a second,
  quieter ability line. It is measured against the other candidates for that
  match instead.

---

## 5. The batting screen, and what replaces it

Decided in `docs/OPEN_QUESTIONS.md` Q3 and confirmed by the project owner: the
user sets **posture and instructions, never shots**. There is no timing meter
and no shot buttons.

What he gets instead is a match centre he can drive — play, next ball, next
wicket, run to the end — over a ball-by-ball record that is the real thing
rather than an animation of it. The instructions screen feeds Stage 4 as terms
in the same shot-selection maths an AI batter uses, never a parallel code path.

---

## 6. Hidden attributes

`HiddenAttributes` — potential, injury proneness, temperament, big-match
factor, learning rate — must never reach a screen. `:presentation` has no
function that returns any of them, and `HiddenAttributes.toString` is
deliberately opaque so a stray interpolation cannot leak one.

What the player gets instead is what a coach would tell him: a description, not
a number.

---

## 7. Compose conventions in `:app`

- One file per screen, named for the screen.
- A composable takes a state object and lambdas for events. No ViewModel in a
  composable signature below the screen root.
- No cricket vocabulary in a modifier chain. If a composable is deciding
  whether something is a wicket, that decision belongs one module down.
- Theme values live in one place; no colour literal below the theme.
- Every screen gets a `@Preview` driven by a state object built the same way a
  test builds one, so a preview and a test show the same thing. These land with
  the first successful compile — an unverified preview is worth very little,
  and writing one now would only be a second thing to fix.

## 8. What the build carries, and when

Phase 0 wired Hilt, KSP and Room into `:app` and `:data` as declared intent.
The first sync on a machine with an Android SDK showed the bill for that:
Hilt's Gradle plugin fails on `com.android.build.gradle.api.BaseVariant`, a
class newer AGP has removed, and it was failing on behalf of a dependency graph
that did not exist — `:data` has no source files at all, and `:app` had not one
`@Inject` in it.

So they are out until there is code for them to act on:

| Comes back | With |
|---|---|
| Room + KSP | Phase 7, alongside the first entity, DAO and repository |
| Hilt | Phase 7, alongside the repositories worth injecting |

A plugin earns its place when something depends on it. Carrying one in advance
buys nothing and costs a version-compatibility problem on every toolchain
upgrade — which is exactly what it cost here.

---

## 9. Status

`:presentation` is built and tested on a bare JDK: 56 tests covering the
scorecard, the match centre, the charts, the profile and the career table.

`:app` holds the theme, the shared components, and the match centre and
scorecard screens. It **has never been compiled** — there is no Android SDK
here and `dl.google.com` is blocked, so neither the SDK nor the Android Gradle
Plugin nor Compose itself can be fetched. Treat every file under `app/` as
declared intent.

The first task in an environment with an SDK:

```bash
./gradlew :app:assembleDebug -Pcricket.includeAndroid=true
```

and then fix whatever that turns up. The screens are written against a state
layer that is already tested, so what it turns up should be import paths and
Compose API signatures rather than anything about cricket.


---

## Records, and choosing a career

Two more screens, both pure `:presentation`.

**Records** (`recordsState`). Batting and bowling tables by format, a table by
rung of the ladder, and the milestone list newest-first. Everything here is a
*formatting* decision and every one of them is a thing that can be wrong:

- **Overs are six to the over.** 43 balls is `7.1`, and the dot is a separator,
  not a decimal point. Writing it as a division is the classic scorecard bug and
  there is a test that fails on it.
- **A player with no average shows a dash**, not a zero and not an infinity.
- **The asterisk survives all the way to the screen.** `140*` is a different
  innings from `140`.
- **A career total is grouped**: `1,204`, because it is read rather than parsed.
- **A format he has never played does not appear**, and formats keep the order
  the world lists them in — a table that reorders itself as a career widens is
  disconcerting to read.

**Career select** (`careerSelectState`). The list of careers, most recently
played first, and the difficulty picker. Two things it gets right that are easy
to get wrong: singulars ("1 season", "1 match") on the first screen a player
ever sees, and a career with no matches yet saying "yet to play" rather than
dividing by zero.

The difficulty picker carries one sentence that is not decoration — that nothing
about the cricket itself changes, the ball does not swing further and the
player's attributes are not touched. A test asserts every difficulty's
description contains it. A player who suspects the game is shading his
attributes has no reason to trust any number it shows him afterwards, and the
numbers are the product.
