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

## 9. The ladder, and moving on it

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

Two rungs are skipped when climbing: ones with no teams on them, and ones that
are not actually harder. A state's red-ball and white-ball sides sit next to
each other in the enum and are the same standard of cricket — moving a man
between them is a sideways step that costs him a format, not a promotion.

Whether he is *good enough* for a rung is §8's question, asked separately at
each one. Eligibility never makes a selection and selection never checks a map.

### A call-up

`Progression` decides what a season was worth, and it decides it by asking the
panel at the rung above — never by putting a threshold on an average. A call-up
is a place in a **squad**, so the test is:

> would the side above name him, two places clear of the man they were about to
> release?

Only the eleven who play are treated as incumbents; the rest of the squad is the
fringe, and the fringe is exactly who a player coming up from below is competing
with. Treating all eighteen as established men made the ladder inert — a
cricketer averaging 37 spent twenty years in a district league and no code said
why.

Because it is the selection model doing the judging, the same season is worth
different things in different competitions, with nothing written down to say so.

### Being released

Asymmetric with going up, deliberately:

| | |
|---|---|
| `retentionShare` | Play less than a quarter of your side's matches and you are not in the plans |
| `graceSeasons` | A man called up in April and dropped in September was never given a chance |
| `forgottenAfterSeasons` | How long being overlooked takes to cost something |

A player nobody has picked all summer is not assessed by the rung above at all.
That is the whole cost of a season spent carrying drinks, and without it being
overlooked has no consequence.

### A call-up means he plays

`callUpMargin` is zero: a side calls a player up when it intends to play him.
Anything looser and a call-up stops being a reward and becomes a sentence. Two
places outside the XI reads fine on paper and produced three seasons of eighteen
omissions and no cricket, because the XI that ranked above him in April still
ranks above him in August.

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

### A year happening to everybody else

`WorldAgeing` advances the whole database one season: every cricketer gets a
year older, a year better or worse, and some of them stop. Their places go to
people who were not there when the player started.

Without it the world is a photograph. A career ends against the men it started
against, and a place in a side never opens up through anything but the player's
own improvement.

Three things it has to get right, each of which was wrong first:

- **The world keeps its size.** One replacement per *retired player*, not per
  squad vacancy. A state association fields a red-ball side and a white-ball
  side from the same eighteen men, so one retirement empties two slots;
  refilling each separately grew the shipped world by a sixth over twenty
  seasons and quietly diluted every side it touched.
- **Replacements are the kind of cricketer that rung produces.** No age is
  forced — the generator already knows what a player at a level looks like.
  Forcing every replacement in at twenty filled the international side with
  players who had not developed yet and cost it a tenth of its standard over
  twenty seasons.
- **They played a season; it simply was not simulated.** The career clock decays
  match sharpness on every day without a match, so running the world through it
  left two thousand nine hundred professionals at the rust floor all year. The
  career layer then compared a player fresh off his own season against a squad
  that looked as though it had spent the winter in bed: call-ups were won on
  rust rather than cricket, and the promoted player was never picked once the
  new season levelled everybody up again.

Retirement is age **and** decline, and the second is what makes it a decision
rather than a birthday: a thirty-five-year-old still worth his place goes on,
and one being carried does not.

The user's own player is excluded. He is advanced day by day through the cricket
he actually played, and passing him through here would age him twice.

Measured over twenty seasons of the shipped world: population stable at 2,903,
every squad full, mean age settling near 29, and the international side holding
its standard to within 4%.

---

### Tiering

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

## 12. Retirement

Deliberately **not** `WorldAgeing`'s retirement, which is a hazard roll behind
the scenes: a name comes off a squad list and nobody asks why. That is right for
the two thousand nine hundred cricketers nobody is playing. It is wrong for the
one who is.

`Retirement` is the last screen of somebody's career and it owes him an answer,
so every term produces a **sentence**:

| Pressure | What it is |
|---|---|
| Age | The slowest, and the only one nobody escapes |
| Decline | Being unable to do what you *could* do — measured against your own peak, not against the rung |
| Idleness | Seasons without cricket. The sharpest, and what retires the player who is still good enough and cannot get in |
| Damage | Old injuries that did not mend. Not the one he has now — that heals |
| Fulfilment | Cuts both ways, and the second half is commoner: a player still chasing a first cap hangs on past the point of sense |

They **add**, and none of them ends a career on its own at a plausible age. That
is the point: cricketers stop for two or three reasons at once, and a model
where one was sufficient retires people for their birthday.

The pressures come back as shares of the whole, strongest first, so a screen can
say which of them is doing the work rather than printing five raw numbers
nobody can compare.

Two inputs live in the career layer rather than on the player, because they are
**memory**: his peak standard (a cricketer is driven out by being unable to do
what he could do, and that needs a record of what that was) and the attribute
points lost for good to past injuries (`Injury.permanentDamage` records what one
injury took; the running total is nobody's but the career's).

`forced` marks the one case where "he chose to retire" would be a lie: nobody at
any level will have him, or the calendar has run out.

**A caution learned the hard way.** A rung's `standard` is the standard of the
*cricket*, not a bar every player in it clears — an international side is 0.92
and most of it is below that. Read as a bar, the model told a forty-two-year-old
averaging fifty-three that he was no longer up to this level.

---

## 13. Difficulty

Three settings, and what each of them is allowed to change.

| | Selectors | Opposition | Injuries | Form swings | Development |
|---|---|---|---|---|---|
| Amateur | 0.70x patience | -0.08 standard | 0.70x | 0.80x | 1.25x |
| Professional | 1.00x | 0.00 | 1.00x | 1.00x | 1.00x |
| Elite | 1.35x patience | +0.08 standard | 1.35x | 1.25x | 0.82x |

"Selector patience" is the weight the panel puts on **reputation** — the term
that makes an incumbent hard to shift and a debut feel earned. It is the biggest
lever of the five, because a career is decided far more often by whether a
player is picked than by anything he does when he is.

"Form swings" reads backwards at first: a *larger* multiplier is harsher, not
kinder. Form moves further per performance, so a bad trot arrives faster and
bites deeper — and the selectors are reading that figure.

Three rules hold this together:

1. **Difficulty changes the career, never the cricket.** On Elite the ball does
   not swing further, the fielders do not catch better and the user's attributes
   are untouched. `Difficulty.applyTo` takes and returns a `CareerTuning`, so it
   cannot reach `EngineTuning` at all — and a test pins the career-layer
   constants it must not touch either: the reduced-form world model, ageing,
   retirement and contracts. Shading any of those would be the same lie wearing
   a career-layer hat.
2. **Professional is the identity.** Every band in `docs/CALIBRATION.md` is
   measured there. A test asserts `PROFESSIONAL.applyTo(DEFAULT) == DEFAULT`,
   because the day that stops being true, every calibration figure in the
   project is describing a game nobody plays.
3. **No parallel code path.** There is no `if (difficulty == ELITE)` in the
   career layer. Same reason as Q3's rule about the user's batting posture: a
   branch is a second, worse model of the thing it duplicates, and the two
   drift.

The player is told all of this on the difficulty picker, in as many words. A
player who suspects the game is shading his attributes has no reason to trust
any number it shows him afterwards, and the numbers are the product.

---

## 14. Saving a career

See `docs/ARCHITECTURE.md` §4 for why a save stores both the result and the
seed. What Phase 7 built:

- **`CareerSave`** — the career on disk: the career seed, the difficulty, the
  player, his ladder position, the date reached, and every match he has played
  as a `PlayedMatch`. `:engine` does no I/O, so `SaveCodec` turns it into a
  string and `:data` decides where the bytes go.
- **Two version numbers, moving independently.** `saveFormat` is the shape of
  the file; `EngineVersion.CURRENT` is the cricket. A calibration change bumps
  the engine stamp and leaves every save readable; adding a field bumps the save
  format and invalidates no scorecard. A match offers "watch it again" only when
  its stored engine stamp still matches, because a replay that disagrees with
  the scorecard beside it is worse than no replay at all.
- **Unknown keys are ignored; a newer save format is refused.** Ignoring a key
  you have never heard of is safe and keeps an older build from bricking a
  player's career. Ignoring that a key you *do* know now means something else is
  not, and the format number is the only thing that can tell those apart.
- **Nothing derived is stored.** Averages, the record book, the form figure —
  all recomputed from the matches, so no screen can disagree with the
  scorecards behind it.
- **`SaveIndex`** holds several careers. It is a cache of each save's summary
  and is treated as one: `reconcile` rebuilds it from the saves, which are
  always the authority. Deleting the career being played leaves *none* selected
  rather than promoting another — which career he wants next is his choice, and
  guessing it is how a game opens the wrong save.

## 15. Records

`Records.of(appearances)` is a pure function from what happened to the record
book, and it follows the scorer's conventions rather than the arithmetically
convenient ones:

- average is runs per **dismissal**, and a player never out has **no** average
  rather than an infinite one;
- a not-out highest score carries the asterisk, and 72\* beats 72;
- an innings a batter did not bat in is not an innings — a number eleven in the
  XI twelve times who padded up four is 4 innings, 12 matches;
- nought not out is not a duck;
- best bowling is most wickets, then fewest runs: 5/40 beats 5/62 beats 4/12.

The milestone list is the part a player actually reads: the day something
happened for the first time, in order, each stamped on the match that did it.
A milestone happens **once** — passing a thousand runs is an event, being past a
thousand for the next decade is not — and a career best is only announced once
there is something to beat, because "career-best 3" on debut is noise.

---

## 16. Determinism

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
