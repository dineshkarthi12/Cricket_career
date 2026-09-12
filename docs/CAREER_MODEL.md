# The career model

Phase 5. This is the layer between the match engine and the player: what
happens to a cricketer across a season and across twenty years of them.

The match engine answers "what happened in this match". The career layer
answers "who was picked, what shape was he in, what did it cost him, and what
did it change". It owns no cricket: it never decides a delivery, it only decides
the conditions a delivery is bowled under and reads the result afterwards.

Written before the code it describes, per CLAUDE.md §9.

---

## 1. What the career layer must not do

Three rules, all of which follow from the non-negotiables:

1. **It never touches the ball.** Nothing in `career/` may change an outcome
   that `match/` has produced. Form and fatigue enter the match *before* the
   first ball, through `EffectiveSkill`, and then the match is on its own.
2. **The user's cricketer has no special code path.** Everything here runs
   identically for the 12,000 other players in the world. The moment selection
   or ageing treats him differently, his statistics stop being comparable with
   anyone else's and the whole simulation stops meaning anything.
3. **It is a pure function of `(seed, state, inputs)`,** exactly like a match.
   A career replays from its seed. This is what makes a twenty-year bug report
   reproducible.

---

## 2. The career clock

A career advances in **days**, not matches. Fatigue recovery, injury rehab,
training weeks and ageing all key off elapsed days, so a congested franchise
season and a quiet county summer diverge naturally rather than because someone
wrote a rule about it.

```
CareerClock(date: LocalDate)
  advance(days) -> applies daily processes to every player in the world
```

`LocalDate` is used as a value only — never `LocalDate.now()`, which
`DeterminismConventionsTest` bans. The starting date arrives as an input.

**Daily processes**, in a fixed order so the result does not depend on map
iteration:

| Order | Process | Applies to |
|---|---|---|
| 1 | Injury rehab countdown | injured players |
| 2 | Fatigue recovery | everyone |
| 3 | Sharpness drift | everyone |
| 4 | Form and confidence decay toward neutral | everyone |
| 5 | Birthday: ageing step | players whose birthday it is |

Ageing is applied **on the player's birthday**, once, rather than smeared across
365 days. It is cheaper, it is easier to test, and a career summary can say "he
lost a yard of pace the year he turned 33" and mean it literally.

---

## 3. Ageing and development

A player has a **development curve** with three phases, fitted to the hidden
`potential`, `learningRate` and the attribute group.

```
growth(age) = peak(group) - age      while age < peak
decline(age) = age - peak(group)     while age > peak
```

Peak ages differ by attribute group, which is the whole point of modelling this
rather than using one curve:

| Group | Peak | Notes |
|---|---|---|
| Physical (`PACE`, `SPEED`, `STAMINA`, `FITNESS`) | 26 | The first thing to go, and the one a career can feel going |
| Technical (`TECHNIQUE`, `FOOTWORK`, `ACCURACY`, …) | 30 | Slow to build, slow to lose |
| Mental (`COMPOSURE`, `CONCENTRATION`, `PATIENCE`, `LEADERSHIP`) | 34 | Still rising when the legs have gone |

So a fast bowler declines from 27 while his captaincy is still improving at 35.
That divergence is the reason an ageing model exists at all.

**Growth per year** is the product of four terms:

```
Δ = base × headroom × learning × exposure
```

- `headroom` = `(potential - current) / span`, clamped at 0. Growth slows to
  nothing as an attribute approaches potential, and potential is soft: a player
  who plays a season above his level can exceed it by a few points.
- `learning` scales with hidden `learningRate`.
- `exposure` is minutes played *above* the player's own standard, from the
  ladder level of the matches he actually played. A year of second-XI cricket
  develops nobody.
- `base` falls with age and goes negative past the group's peak.

**Decline** is the same equation with a negative base, and it is *not*
symmetrical: decline is slower than growth at first and then accelerates, which
is why a 36-year-old batter falls off a cliff rather than fading linearly.

**Permanent injury damage** is applied here, as a one-off subtraction on the
physical group when a `SEVERE` injury resolves.

---

## 4. Form, confidence and sharpness

Three separate numbers because they behave differently, and collapsing them
into one "form" slider is the thing that makes sports sims feel fake.

| | Moves on | Decays | Feeds |
|---|---|---|---|
| **Form** | Match performance vs the player's own expectation | Slowly, ~3% a day | Effective skill, selection |
| **Confidence** | Same, but bigger swings | Quickly, ~8% a day | Intent, shot selection |
| **Sharpness** | Minutes in the middle | Falls while not playing | Effective skill, execution error |

**Form update.** After an innings, the player's performance is scored against
what a player of his attributes would be expected to do *in those conditions and
at that level*:

```
surprise = (actual - expected) / expectedStdDev
Δform = k × tanh(surprise / 2) × temperamentScale
```

`tanh` because one triple-century should not buy a decade of form, and dividing
by the expected standard deviation is what makes 30 in a low-scoring Test worth
more than 30 in a T20. `temperamentScale` comes from hidden `temperament`: a
low-temperament player swings further in both directions.

The **expected** value is measured from engine output, not hand-authored — the
same discipline as `docs/CALIBRATION.md`. See `CareerCalibration`.

---

## 5. Fatigue

Fatigue accumulates per match by **workload**, not by match count:

```
load = oversBowled × bowlingWeight(style) + ballsFaced × battingWeight
     + fieldingMinutes × fieldingWeight
```

Weights are per format and per role, so a Test seamer's 28-over day costs far
more than a T20 opener's 40 balls. Travel and back-to-back fixtures add a fixed
per-match component that does not scale with workload.

Recovery is exponential in rest days, scaled by `FITNESS` and `STAMINA`. A fit
player recovers a congested week; an unfit one carries it into the next match
and bowls at 88% of his pace.

---

## 6. Injury

One roll per **match** and one per **training week**, from the `career.injury`
stream. The hazard is:

```
p = base(format, role) × loadFactor × fatigueFactor × pronenessFactor × ageFactor
```

`fatigueFactor` is the important one: playing tired is how a niggle becomes a
hamstring tear, and it is the mechanism that punishes over-scheduling without
needing a rule that says "do not over-schedule".

Severity is drawn conditional on an injury having happened, and the distribution
shifts toward the serious end with fatigue and age. `SEVERE` injuries take
permanent attribute damage, applied at rehab end (§3).

---

## 7. Training

A week is **100 points** split across focus areas. Each area maps to a set of
attributes and a gain multiplier.

```
Δattribute = weekPoints × learningRate × headroom × coachingQuality × (1 - fatigue)
```

`(1 - fatigue)` is deliberate: training hard while exhausted produces almost no
gain and a much higher injury roll. The optimal strategy is therefore *not*
"maximum intensity always", which is the thing that would otherwise reduce this
screen to a single button.

Rest is a valid allocation and reduces fatigue directly.

---

## 8. Selection

A selector scores every available player for a given match and picks a balanced
XI. The score is:

```
score = standard × w1 + recentForm × w2 + suitability × w3
      + reputation × w4 - risk × w5
```

- `standard` is the player's attributes weighted for the format.
- `suitability` is conditions-specific: a second spinner on a turning pitch, an
  extra seamer under cloud.
- `reputation` gives incumbents an advantage, so a player is not dropped for one
  failure — and so a young player must be clearly better, not marginally, to
  displace one.
- `risk` covers fitness doubts and a lack of match sharpness.

Selectors have **personalities** (`loyalty`, `boldness`, `formWeighting`) drawn
per team, so two national selectors treat the same young player differently.
This is what makes a career feel like it is happening *to* the player.

Balance constraints are hard: an XI needs a keeper, a minimum of four bowling
options, and a top six. The scorer proposes, the balance check disposes.

---

## 9. The ladder

Which sides a player is *eligible* for is geography, and it is read out of the
seed database rather than written in Kotlin — `Ladder` knows nothing about
zones or franchise leagues, only about three widening circles:

| Rung | Who may pick him |
|---|---|
| College, district, age-group, state | His own region. You play for where you are from. |
| Zonal | The zone his region feeds, recorded on the region in the seed file |
| Franchise, national A, international | Anyone in his country — a selector is not bound to one state |

Empty rungs are skipped, so a database with no age-group cricket in it promotes
a district player straight to his state side rather than stalling him against a
level with no teams on it. A world with a different pyramid gives a different
ladder without a line changing in the engine (Q2).

Whether he is *good enough* for a rung is §8's question, asked separately at
each one. Eligibility never makes a selection and selection never checks a map.

---

## 10. Fixtures

A season is not a number of matches, it is a list of them, and where they fall
is what a career feels like. `FixtureList` builds that list from the seed
database — see `docs/SEED_DATABASE.md` for the structures and the international
rotation.

What matters here is what it hands the rest of this document:

- **A date.** Fatigue and injury are charged per day, so a congested run of
  matches costs more than the same matches spread out. Overlapping
  competitions are pushed apart rather than stacked, which turns a crowded
  calendar into a longer, tighter season instead of an impossible one.
- **A level.** Growth comes from playing above your standard (§3), so which
  rung a fixture is on decides what it is worth.
- **An opposition standard**, from the best eleven of the opposing squad — a
  side with two stars and sixteen journeymen fields the two stars.
- **A pitch**, drawn from the host ground's archetype weights. A player who
  spends a career on rank turners develops differently from one who does not,
  and no code anywhere grants a home side anything.

---

## 11. The world simulation

Three tiers, as decided in `docs/OPEN_QUESTIONS.md` Q1:

| Tier | What | How |
|---|---|---|
| 1 | The user's matches, and his direct rivals for a place | Full ball-by-ball |
| 2 | The rest of his competition | Reduced-form innings model |
| 3 | Other countries' domestic cricket | Aggregate season model |

The tier-2 and tier-3 parameters are **fitted from tier-1 output**, never
hand-authored. `sim-harness` runs the fit and writes the coefficients; a
divergence between tiers is a calibration bug, not a design choice. This is the
only way the statistics of a player in another country stay comparable with the
user's own.

---

## 12. Determinism

Career streams are derived from the **career seed** the same way match streams
are derived from a match seed:

```
CareerRandom(seed).stream(CareerStreams.INJURY)
```

Match seeds are themselves derived: `deriveSeed(careerSeed, "match:$matchId")`,
so replaying a career replays every match in it, and simulating a match on its
own from the same seed gives the same result.

The stream split is what lets the injury model be retuned without moving a
single selection decision.

| Stream | Owns |
|---|---|
| `career.ageing` | Year-on-year attribute noise |
| `career.form` | Form and confidence noise |
| `career.injury` | Injury occurrence, type and severity |
| `career.training` | Training outcome noise |
| `career.selection` | Selector judgement noise |
| `career.contracts` | Offers, auction bidding |
| `career.world` | Tier-2 and tier-3 results |
| `career.generation` | Players entering the world each season |
