# Open questions

Decisions that need the project owner. Each has a **working default** so that
nothing is blocked while they are open — but several of them change the shape of
the code, and the ones marked ⚑ are much cheaper to answer now than in Phase 5.

**Q1, Q2 and Q3 were decided on 2026-09-10** (all three as proposed) and are
kept below as a record of the reasoning. **Q10 was settled in code** by Phase 1.
Q4–Q9 are still open and running on their defaults.

---

## ✅ Q1 — DECIDED. How much of the world gets simulated ball by ball?

Simulating every ball of every match on earth does not fit the Phase 8
performance budget (a full world season in seconds on a mid-range phone). See
`ARCHITECTURE.md` §5 and §7.

**Proposed:** three fidelity tiers. Full ball-by-ball for the user's matches
(Tier A) and for his direct rivals and incumbents (Tier B); a reduced-form
statistical model for the rest of the world (Tier C), whose parameters are
**fitted from the real engine's output** rather than hand-authored, so the two
tiers describe the same world.

This is the largest single architectural commitment in the project. If you want
literally every ball simulated, that is buildable — but it changes the target
platform, the save format and the Phase 8 goals, and I would want to agree that
before Phase 2 rather than discover it in Phase 5.

**Decided 2026-09-10:** three tiers as described. Binding on Phase 2
(hot-path allocation discipline, event capture as a swappable sink) and on
Phase 5 (the fitting task that generates Tier C's parameters from engine output).

---

## ✅ Q2 — DECIDED. One country or many?

The brief's ladder is India-shaped: state cricket, U19/U23, a franchise T20
auction, "India A". But it also asks for away tours and World Cups, which need
the rest of the world to exist.

**Proposed:** one fully modelled home country (the Indian structure, with
generic city names) plus other nations modelled at squad level — real fixtures,
real selection, real players, but no domestic pyramid beneath them. Adding a
second full pyramid later is data, not code, if the ladder is defined in the
seed database rather than in Kotlin.

**Decided 2026-09-10:** one full pyramid, others at international level only.
Binding on Phase 1: the ladder, its competitions and its qualification rules
live in the seed database, not in Kotlin, so a second pyramid is later a data
addition rather than a rewrite.

---

## ✅ Q3 — DECIDED. How much control does the player have during a match?

The user controls one cricketer. When batting, does he choose shots ball by
ball, or set a posture?

**Proposed:** posture, not shots — an aggression/intent setting adjustable
between balls, plus specific instructions ("attack the spinner", "see off the
new ball", "target the leg side"). Ball-by-ball shot choice would make the
player, not the cricketer, the one with the technique, and it would make a
long innings exhausting rather than tense. Cricket Captain's charm is that you
manage, and then you watch.

When the user's player is captain, he gets full tactical control of field and
bowling changes, per the brief.

**Decided 2026-09-10:** posture and instructions, not per-ball shot selection.
Binding on Stage 4: the user's aggression setting and instructions enter shot
selection as terms in `riskAppetite` and as constraints on the shot set — the
same inputs an AI batter uses, never a separate code path.

---

## Q4. Whose cricket?

The brief does not say. Men's and women's cricket differ in pace ranges,
boundary sizes and format calendars, all of which are parameters rather than
structures — but they need deciding before the fixtures and the calibration
targets are written, because Section 3's bands are men's figures.

**Default:** men's cricket. Structurally, keep pace ranges, boundary distances
and calendars as data so the other is a later addition rather than a rewrite.

---

## Q5. How long is a career, and how big is a save?

A 20-year career at ~40 matches a year with a materialised scorecard per match
across a whole world is a lot of rows. It affects the Room schema, and it
affects how much history the records/hall-of-fame features can show.

**Default:** full scorecards for every match the user played; compact
per-innings summaries for everyone else; aggregate season records for Tier C.
Target a save under ~20 MB for a full career.

---

## Q6. Difficulty — what does it change?

**Default:** difficulty adjusts the selectors' patience, the standard of
opposition the player faces, injury frequency and the harshness of form swings.
It does **not** secretly modify the user's attributes or the ball-by-ball maths.
A simulation that lies to the player about what happened is not worth building,
and it would make the statistics meaningless.

**Built as the default, Phase 7.** `Difficulty` is an enum of three settings —
Amateur, Professional, Elite — expressed entirely as a pure transform on
`CareerTuning`. Its signature is `applyTo(CareerTuning): CareerTuning`, so it
cannot reach `EngineTuning` at all; a test additionally pins the career-layer
constants it must not touch either (the reduced-form world, ageing, retirement,
contracts), because shading those would be the same lie wearing a career-layer
hat. Development rate joins the four levers above: a slower one decides whether
a player gets a second chance at a level he failed at first time.

Professional is the identity transform, and a test asserts it — every band in
`docs/CALIBRATION.md` is measured there, so the day that stops being true every
one of them is measuring a different game from the one it claims to.

There is no `if (difficulty == …)` anywhere in the career layer and there must
never be one, for the same reason Q3 forbids a parallel path for the user's
batting posture: the career runs the same code at every setting, on different
numbers.

---

## Q7. DRS and umpiring quality by level

DRS does not exist below international level in most competitions, and umpiring
quality varies enormously down the ladder.

**Default:** umpire error scales with the level (district umpires materially
worse than an elite panel); DRS is a property of the competition in the seed
database, present at international level and in the top franchise league only.

---

## Q8. Does the player ever see his hidden attributes?

The brief says `potential`, `injuryProneness`, `temperament`, `bigMatchFactor`
and `learningRate` are hidden and only inferred.

**Default:** never shown as numbers, ever — not even at retirement. Coach
feedback gives *directional* hints with an error that shrinks as the coach's
quality and the length of the relationship grow. A bad coach can be wrong about
you, which is a feature.

---

## Q9. Tech stack sign-off

The brief invited alternatives. I have no disagreement with the proposed stack —
Kotlin, multi-module Gradle, Room, Compose, Hilt, kotlinx.serialization, min SDK
26 — and Phase 0 is built on it. Two notes:

- **Compose is right for this** despite the dense spreadsheet aesthetic. Lazy
  lists with fixed-height rows handle it well, and a scorecard is a much better
  fit for a declarative tree than for XML layouts.
- **Hilt earns its place only from Phase 5**, when repositories and the career
  layer arrive. It is already wired in the (uncompiled) Android modules; if you
  would rather not carry the annotation processor, manual constructor injection
  would work for a project this size. Your call, no strong view.

**Default:** the stack as briefed.

---

## ✅ Q10 — SETTLED IN CODE. What is "average" for calibration?

Section 3's bands are for "average-quality players on an average pitch", which
needs a concrete definition or the reports are not comparable across runs.

**Settled by Phase 1:** `engine/src/testFixtures/.../Fixtures.kt` is now the
single definition, and `FixturesTest` guards it — if someone quietly makes the
fixture XI a bit better, every band in the calibration log stops meaning what it
says, and nothing else in the build would notice.

Concretely: an XI of players rated 50 at every attribute and 50 at every hidden
attribute, in a standard balance (five specialist batters, a keeper, an
all-rounder, three seamers, a spinner), on a pitch at the midpoint of every
parameter on neutral clay soil, at a symmetric mid-sized ground at sea level.

The fixture player is deliberately *flat* rather than role-shaped. Calibration
needs a control, and a control with a role's shape would fold the role
archetype's opinions into every measured band. Tests that want realistic players
use the generator instead.

Say if you want "average" to mean something else — it is one file, but changing
it later invalidates every calibration figure recorded before the change.
