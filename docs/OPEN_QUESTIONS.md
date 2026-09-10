# Open questions

Decisions that need the project owner. Each has a **working default** so that
nothing is blocked while they are open — but several of them change the shape of
the code, and the ones marked ⚑ are much cheaper to answer now than in Phase 5.

---

## ⚑ Q1. How much of the world gets simulated ball by ball?

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

**Default if you say nothing:** three tiers as described.

---

## ⚑ Q2. One country or many?

The brief's ladder is India-shaped: state cricket, U19/U23, a franchise T20
auction, "India A". But it also asks for away tours and World Cups, which need
the rest of the world to exist.

**Proposed:** one fully modelled home country (the Indian structure, with
generic city names) plus other nations modelled at squad level — real fixtures,
real selection, real players, but no domestic pyramid beneath them. Adding a
second full pyramid later is data, not code, if the ladder is defined in the
seed database rather than in Kotlin.

**Default:** one full pyramid, others at international level only, ladder
defined as data.

---

## ⚑ Q3. How much control does the player have during a match?

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

**Default:** posture and instructions, not per-ball shot selection.

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

## Q10. What is "average" for calibration?

Section 3's bands are for "average-quality players on an average pitch", which
needs a concrete definition or the reports are not comparable across runs.

**Default:** a full XI of attribute-50 players — batters, bowlers and a keeper
in a standard balance — on a pitch at the midpoint of every parameter, in
neutral weather. Defined once in the shared test fixtures so that the engine's
tests, the harness and the calibration log all mean the same thing by it.
