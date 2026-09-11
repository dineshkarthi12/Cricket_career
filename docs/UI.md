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
that `:data` exists. Where a value came from is not a presentation concern — a
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
- Every screen has a `@Preview` driven by a state object from
  `:presentation`'s test fixtures, so a preview and a test show the same thing.

## 8. Status

`:presentation` is built and tested. `:app` is written against it but
**has never been compiled** — see the note in `app/build.gradle.kts`. The first
task in an environment with an Android SDK is to configure the build and fix
whatever that turns up.
