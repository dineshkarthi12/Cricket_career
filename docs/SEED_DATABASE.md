# The seed database

The world the career happens in: countries, the domestic pyramid beneath them,
the grounds, the teams and the competitions they play in.

It is **data, not Kotlin**. That was decided in `docs/OPEN_QUESTIONS.md` Q2 and
it is the difference between a game with one world and a game whose world a
player can rewrite.

Written before the code it describes, per CLAUDE.md §9.

---

## 1. What it is for

Three things, in order of how much they matter:

1. **The ladder has to be real.** "College cricket to international cricket"
   means nothing unless the rungs exist as places with names, grounds and
   fixtures. A `LadderLevel` enum says a district league is weaker than a state
   side; the seed database says *which* district, playing *whom*, *where*.
2. **The player can edit it.** The brief is explicit. Every name, every team,
   every competition is in a JSON file he can open.
3. **It is the only source of proper nouns.** Nothing in `:engine` hard-codes a
   place or a person. If a name appears on screen, it came from here.

---

## 2. Where it lives, and who may touch it

```
seed/
  world.json      334 KB   countries, venues, teams, competitions
                           pretty-printed and genuinely hand-editable
  players.json    2.9 MB   the roster, compact
```

Two files, not five, and the split is along the line that matters: **what a
person edits** versus **what a person regenerates**. Pretty-printing all 2,903
cricketers came to 6 MB, and the point of pretty-printing is that somebody
reads the file — nobody reads three thousand cricketers. The structure is 334
KB and opens in any editor.

Regenerate with:

```
./gradlew :sim-harness:run --args="--report=seed --seed=20260912"
```

Deterministic in the seed, so a world can be rebuilt exactly or nudged by hand
and kept.

| Module | May |
|---|---|
| `:engine` | Define the schema, parse a **string**, validate it |
| `:data` | Read the files, ship them as assets, hand `:engine` the strings |
| `:sim-harness` | Generate and re-generate them |
| `:app` | Nothing. It never sees this layer |

`:engine` does no I/O — that is a non-negotiable — so it parses text it is
handed and never opens a file. The split is small but it is the difference
between a pure engine and one that needs a filesystem to be tested.

---

## 3. The shape

### Countries
A country is a name, a bowling mix and the regions beneath it. The bowling mix
is why a subcontinental country produces spinners and a bouncy one produces
pace, without anyone writing a rule per country.

### Regions, districts and zones
The pyramid, for **one** country in full (Q2): districts feed regions, regions
feed zones, zones feed the national side.

Zones are a real rung rather than a decoration, and `LadderLevel.ZONAL` was
added for them. It is the first level at which a player is picked *against* the
best of four or five states rather than for his own, which is why a good state
season and a zonal cap are different things — and it is usually where the
national selectors are actually watching.

Other countries exist at international level only —
their domestic cricket is tier 3 in `docs/CAREER_MODEL.md` §11 and nobody will
ever read a ball of it.

A region carries a `playerShare`: how much of the country's talent comes from
there. It is why some states are harder to break into than others, and it is
one number rather than a rule.

It also carries a `zone`: the id of the zonal side it feeds, blank where there
is none. That link is structural and belongs in the file rather than being
derived from this year's zonal squad — a squad is fifteen players drawn from
four or five states, so a state with nobody in it would be left unmapped, and a
state whose best young player has nowhere to be picked is a career that stops
for a reason nothing in the project could explain. `Ladder` in the career layer
is the only thing that reads it, and it is the whole of what that object knows
about zones.

### Venues
A ground is a boundary shape, a soil type, a set of pitch archetype weights, an
altitude and a dew tendency. Every one of those is already an engine input —
this file is where the values come from rather than a new vocabulary.

### Teams
A team is a name, a level, a home venue and a squad. **Cities and regions only**
— no franchise names, no crests, no logos (non-negotiable #6), and the loader
refuses a database that breaks it rather than trusting whoever edited the file.

### Competitions
The thing that turns teams into a season: a format, a level, the teams in it,
and when it runs. A competition is where fixtures come from, and fixtures are
what `Season` in the career layer consumes.

```jsonc
{
  "id": "IND-STATE-T20",
  "name": "State T20 Cup",
  "country": "IND",
  "level": "STATE_WHITE_BALL",
  "format": "T20",
  "structure": "GROUPS_THEN_KNOCKOUT",
  "teams": ["IND-MH", "IND-TN", "..."],
  "startMonth": 10,
  "weeks": 7
}
```

#### From a competition to a fixture list

`FixtureList` turns a competition into the matches one team actually plays.
This is the join between the world and the career: a competition says *who is
in a tournament*, a `Fixture` is *a match on a date at a ground*, and `Season`
consumes fixtures.

| Structure | What a team plays |
|---|---|
| `SINGLE_ROUND_ROBIN` | Everyone else, once, alternating home and away |
| `DOUBLE_ROUND_ROBIN` | Everyone else twice, once at each ground |
| `GROUPS_THEN_KNOCKOUT` | Its own group only — two balanced halves of the team list |
| `KNOCKOUT` | One guaranteed match; the rest is earned, not pencilled in |
| `BILATERAL_SERIES` | Three matches against one side, all at the host's grounds |

A `BILATERAL_SERIES` with more than two teams is a **calendar** of series
rather than one fixed pairing — which is what an international season is.
Each year every side plays three series, drawn by the round-robin circle
method with the season as the offset, so opponents rotate, everyone gets the
same amount of cricket, and a full cycle plays everyone. Who hosts is decided
by position in the team list and flipped each time the rotation comes round,
so no side hosts the same tour forever. Both nations generate the identical
fixture from their own end.

Two rules hold across a whole season, not just within one competition:

- **Nobody is in two places at once.** Fixtures that overlap — a franchise
  league landing on top of a first-class season, or a Test that is still in
  progress — are pushed apart, even past a competition's nominal window. A real
  board does the same, and the fatigue and injury models charge the player for
  the result.
- **The pitch comes from the host's ground**, drawn from that venue's own
  archetype weights. This is the only home advantage in the project; there is
  no home bonus term anywhere.

### Players
Generated rather than hand-written — four thousand cricketers is not something
anyone types — but written out as a real file so it can be edited like any
other. Regenerating is deterministic in a seed, so a world can be rebuilt
exactly or nudged by hand and kept.

---

## 4. Validation

A database is checked on load and **refused** if it is wrong, rather than
limping on with a dangling reference that surfaces as a crash three seasons
into someone's career.

| Rule | Why |
|---|---|
| Every id unique within its collection | Two teams with one id is a silent data loss |
| Every reference resolves — team → venue, competition → team, player → team | The class of bug that appears in season three |
| No competition is empty, and none has an odd team count its structure cannot handle | A league that cannot produce fixtures |
| Every team's level matches its competition's | A district side in a national tournament |
| **No name matches a real cricketer or a real franchise** | Non-negotiable #6, and a file anyone can edit is exactly where a breach would enter |

The last one is enforced by `seed/RejectedNames.kt`, which exists because the
rule previously lived as an *absence* — certain names had simply been deleted
from `generator/NamePools.kt`. That is a rule nobody can check and one careless
edit can undo, and the seed database is a file the player is invited to edit,
so it is exactly where a breach walks back in.

It is a guard, not a legal review, and it says so:

- **Franchise words are blocked outright.** A closed set, every one a trademark.
  "Chennai" passes; "Chennai Super Kings" does not — the exact line the brief
  draws.
- **Surnames are not.** Sharma, Khan, Patel and Singh belong to millions of
  people. A person is rejected only on a full-name match, which catches someone
  pasting a famous cricketer straight in and nothing subtler.

A user who edits his own copy to put a real player in it still can. That is his
business on his own device; what this stops is such a name shipping in the
repository, which is ours.

---

## 5. Editing it

The file is the contract. A player who wants his own name, his own club, his
own tournament opens the JSON and changes it. Anything that loads is legal.

What the loader will not do is silently repair a broken file. It names the
first thing that is wrong and stops, because a database that half-loaded is
worse than one that did not.
