# Simulation model

The maths of the match engine, written before the code that implements it.

Everything here is a proposal to be argued with. Numbers given are starting
values for calibration, not truths — every one of them lands in `EngineTuning`
with the comment explaining what moving it does.

The governing principle: **do not sample the outcome.** Simulate the physical
chain and let the outcome fall out of it. A cheap cricket sim asks "what
happened on this ball?" and rolls on a table. This engine asks what the bowler
tried to do, what he actually did, what the ball then did, what the batter
thought it was doing, and what happened when the bat arrived where he sent it.
Edges, drops and collapses are consequences, not entries.

---

## 1. Coordinate system and units

SI throughout. Metres, seconds, kilograms; pace converted to km/h only for
display.

**Frame.** Origin at the centre of the striker's stumps, at ground level.

- `y` — down the pitch towards the bowler, positive. The ball travels in −y.
- `x` — lateral, **positive towards the off side of the striker**.
- `z` — height above ground.

Because `x` is defined relative to the striker's off side, the entire model is
handedness-agnostic: a left-hander is simulated identically and the frame is
mirrored back to absolute ground coordinates only for field placement and the
wagon wheel. One implementation, no duplicated left-hander logic.

**Fixed geometry.**

| Quantity | Value |
|---|---|
| Stump to stump | 20.12 m |
| Popping crease ahead of stumps | 1.22 m |
| Stump width (outside to outside) | 0.2286 m |
| Stump height | 0.711 m |
| Ball diameter | 0.072 m |
| Return crease from middle stump | 1.32 m |
| White-ball off-side wide guide | 0.89 m from middle stump |
| 30-yard circle radius | 27.43 m |
| Effective release point | y ≈ 17.7 m, z ≈ 1.9–2.4 m by bowler height and action |

**Length**, `y_p`, is where the ball pitches, in metres from the striker's
stumps. Band names are pace-bowling conventions:

| Band | `y_p` (m) |
|---|---|
| Full toss | does not pitch |
| Yorker | 0.0 – 1.0 |
| Half-volley / full | 1.0 – 4.0 |
| Fullish | 4.0 – 6.0 |
| Good length | 6.0 – 8.0 |
| Back of a length | 8.0 – 10.0 |
| Short | 10.0 – 14.0 |
| Long hop | > 14.0 |

Spin uses the same axis with the bands shifted ~2 m fuller — a spinner's good
length is roughly 4.0–6.0 m — because a slower ball with more dip has to land
closer to be equally awkward. The engine holds two band tables, selected by
bowler type; it does not pretend one set of names means the same thing for both.

**Line**, `x_s`, is the lateral position as the ball passes the stumps:

| Band | `x_s` (m) |
|---|---|
| Down the leg side | < −0.35 |
| Leg stump | −0.35 … −0.12 |
| Middle | −0.12 … +0.12 |
| Off stump | +0.12 … +0.25 |
| Fourth stump | +0.25 … +0.42 |
| The channel / fifth stump | +0.42 … +0.62 |
| Wide outside off | > +0.62 |

---

## 2. Attributes, effective skill and state

Attributes are stored as `Int` 1–100. The engine normalises once at the
boundary:

```
n(a) = (a - 1) / 99            →  [0, 1]
```

A "county/first-class average" player sits at 50 (`n ≈ 0.495`); an
international regular around 72; a great around 88. Nothing in the engine reads
a raw 1–100 value.

**Effective skill** folds dynamic state into the attribute at point of use:

```
e(a) = clamp( n(a)
              + 0.080 · form
              + 0.050 · confidence
              - 0.120 · fatigue
              - 0.060 · (1 - sharpness)
              - 0.040 · niggle
            , 0.02, 0.99 )
```

with `form`, `confidence` ∈ [−1, 1] and `fatigue`, `sharpness`, `niggle` ∈ [0, 1].

The coefficients are deliberately small. Form is a real effect but a modest one;
if form swings a player from "good" to "poor", the career layer stops being
about ability and becomes about a hidden slider. The largest single term is
fatigue, because it is the one with the clearest observable effect in real
cricket — a bowler in his fourth over of a spell is measurably worse.

---

## 3. Pressure

`pressureIndex` `P ∈ [0, 1]` is computed once per ball and consumed by four
stages. It is the mechanism that produces collapses without anything in the code
being called "collapse".

```
P = logistic( 1.9 · (0.30·chaseStress + 0.28·wicketStress
                     + 0.18·dotStress + 0.14·phaseStress
                     + 0.10·occasion) - 0.95 )
```

| Term | Definition |
|---|---|
| `chaseStress` | Chasing: `clamp((RRR − parRR) / parRR, −1, 2) / 2`. Setting/first innings: scoreboard-pressure proxy from wickets and run rate against par for the pitch. |
| `wicketStress` | `(wicketsLost / 10)^1.4` blended with the recent cluster term `exp(−ballsSinceLastWicket / 24)` — two in two is far more pressure than two in fifty. |
| `dotStress` | Dot balls in the last 12 legal balls / 12. |
| `phaseStress` | Format-specific: death overs in white ball, last hour and fourth innings in a Test. |
| `occasion` | Ladder level and match importance, modulated by the batter's hidden `bigMatchFactor`: a big-match player experiences less of it, a flat-track bully more. |

Where `P` is consumed:

1. **Stage 2** — widens the bowler's execution error, scaled by `(1 − e(composure))`.
2. **Stage 4** — raises the batter's shot-selection temperature (worse choices) and shifts risk appetite.
3. **Stage 5** — widens timing error.
4. **Stage 6** — lowers catch probability.

Note it cuts both ways: pressure makes the bowler worse too. A bowler defending
six off the last over is not simply an obstacle.

---

## 4. Stage 1 — Bowler intent

The bowler picks `(lengthBand, lineBand, deliveryType, targetPace)` from the
legal candidate set for his type.

Score each candidate `c` and sample:

```
U(c) = w_s·skillFit(bowler, c)          // can he actually bowl it
     + w_w·exploit(batter, c)           // does it attack a known weakness
     + w_m·situation(c, matchState)     // contain or attack
     + w_f·fieldConsistency(c, field)   // don't bowl a bouncer with no man back
     + w_p·planAffinity(c, plan)        // bowlers bowl to a plan
     - w_r·risk(c)                      // a yorker missed is a full toss

p(c) = softmax(U / τ)
τ = 0.35 · (1 + 0.60·(1 − n(discipline))) · (1 + 0.40·n(aggression))
```

`τ = 0.35` is the predictability knob. Low `τ` makes bowlers relentless and
readable; high `τ` makes them scattergun. A disciplined bowler is *predictable*,
which is a real trade-off in cricket — the batter can set up against him.

**Plans persist.** A bowler holds a plan per opposing batter (e.g. "fourth stump
good length, two slips and a gully, one bouncer an over"). It is not resampled
every ball. It is re-drawn when a new batter arrives, roughly once per over with
probability `1/6`, and immediately when the plan is visibly failing — two
boundaries in three balls. Bowler intent is therefore autocorrelated, which is
both realistic and what makes "he's worked me out" readable to the player.

**`exploit(batter, c)`** uses the bowler's *belief* about the batter, not the
truth. Belief starts as the public record (scouting, published statistics) and
converges on reality with balls bowled at him. A debutant's weakness against the
short ball is not known on day one — it gets found out. This gives the career
layer a genuine arc: your first season is easier than your third until you
develop.

---

## 5. Stage 2 — Execution

The intended ball is a target `(y*, x*, v*, seamAngle*)`. What is actually
delivered is that target plus error.

```
(y_p, x_s) = (y*, x*) + ε        ε ~ mixture 2-D normal
```

**Mixture, not plain Gaussian.** With probability `0.965`, `ε ~ N(0, Σ)`; with
probability `0.035`, `ε ~ N(0, (2.6)²·Σ)`. A single Gaussian produces far too few
genuine long hops and rank full tosses relative to real cricket, where every
bowler has an occasional one that gets away. The heavy tail is where free
boundaries — and a good share of the wickets that follow them — come from.

**Spread.**

```
σ_y = 0.62 · A · F · Pf · D · C          (metres, length)
σ_x = 0.16 · A · F · Pf · D · C          (metres, line)

A  = 1.55 − 1.10 · e(accuracy)           // accuracy 100 → 0.45×, accuracy 1 → 1.55×
F  = 1 + 0.45 · fatigue^1.4              // non-linear: the last overs of a spell hurt most
Pf = 1 + 0.30 · P · (1 − e(composure))
D  = delivery-type difficulty
C  = 1.00 – 1.12 for footholds and pitch condition
```

Length is much less repeatable than line, hence 0.62 against 0.16 — bowlers miss
their length far more often than their line, and that asymmetry is what makes
length the more valuable skill.

Delivery-type difficulty `D`:

| Type | `D` | Type | `D` |
|---|---|---|---|
| Stock | 1.00 | Bouncer | 1.10 |
| Arm ball | 1.05 | Cutter | 1.15 |
| Googly | 1.20 | Slower ball | 1.25 |
| Yorker | 1.35 | Doosra | 1.40 |
| | | Carrom ball | 1.45 |

Length and line errors are correlated at `ρ = 0.15` along the bowler's release
angle: a right-arm-over bowler who drags down tends to drag down the leg side.
The correlation is applied as a rotation of `Σ`, with the sign set by arm and
around/over the wicket.

**Pace** `v = v* · (1 − 0.06·fatigue) + N(0, 0.02·v*)`.

**Wides are geometric, not sampled.** A ball is a wide if it passes outside the
format's wide line and the batter did not reach it or offer a shot. Nothing rolls
for "wide". This is the honest construction — but it means the wide rate is an
*emergent* consequence of `σ_x`, and if the measured rate misses the target band
(1.5–2.5% Tests, 3–5% white ball) the fix is `σ_x`, which also moves boundary and
dismissal rates. See §12.

**No balls** are the exception, because overstepping is largely independent of
what the ball does:

```
p(no ball) = 0.0045 · (1 + 1.2·(1 − n(discipline))) · (1 + 0.5·fatigue) · paceFactor
```

Height no-balls (beamers, above-waist full tosses) are derived geometrically
from the delivered trajectory, not sampled separately — they are what the far
tail of the length error looks like when it goes the full way.

---

## 6. Stage 3 — Ball movement

### 6.1 Ball condition

Three state variables evolve with overs bowled `u`, modulated by outfield
abrasiveness and the fielding side's polishing:

```
shine     s(u) = exp(−u / 22)
roughness r(u) = 1 − exp(−u / 30)
hardness  h(u) = 0.35 + 0.65 · exp(−u / 25)
```

Hardness drives carry to the keeper and slips, and bounce off the pitch. A soft
50-over-old ball simply does not carry to second slip, which is why cordons
thin out.

### 6.2 Conventional swing

Lateral in-air deviation, measured at the stumps:

```
Δx_swing = 0.55 · e(swing) · Φ_shine · Φ_atm · Φ_pace · Φ_seam · ζ

Φ_shine = (1 − exp(−u / 2.5)) · exp(−u / 18)
Φ_atm   = clamp(0.75 + 0.50·humidity + 0.35·cloudCover, 0.60, 1.60)
Φ_pace  = exp( −((v − 133) / 28)² )        v in km/h
Φ_seam  = 0.55 + 0.45 · seamPresentationQuality
ζ       = 1 + 0.25·N(0,1), truncated to [0.4, 1.6]
```

`Φ_shine` rises then falls: a brand-new ball has no shine *differential* yet, so
swing peaks around overs 4–7 and is essentially gone by 30 — matching the
brief's "declines after ~25–30 overs".

`Φ_pace` peaks at 133 km/h. Very fast bowlers swing the ball less, not more,
because the ball has less time in the air to deviate. This is why a
135 km/h swing bowler can be more dangerous with a new ball than a 148 km/h
quick.

`ζ` matters more than it looks: the amount of swing must vary ball to ball, or
the batter's model of the world becomes exact after two deliveries.

**Late swing.** Deviation accumulates along the flight as `(fraction of flight
travelled)^p`, with `p = 2.0` for conventional swing. The batter commits to a
shot at a decision point roughly 0.38 s before impact — about 55% of the way
down. Only movement *after* the decision point beats him. This is the entire
mechanism by which late swing is more dangerous than early swing, and it is one
line of maths rather than a special case.

### 6.3 Reverse swing

```
gate = logistic((u − 34)/4) · [r(u) > 0.55] · dryness · logistic((v − 128)/6)
Δx_reverse = 0.42 · e(reverseSwing) · gate · ζ        (sign opposite conventional)
p_late = 3.4
```

`p = 3.4` rather than 2.0: reverse swing happens very late, which is why it is
dangerous at lower deviation than conventional swing. The gates are soft
(logistic, not a hard cutoff) so the ball does not suddenly start reversing on
the stroke of over 35.

### 6.4 Seam movement off the pitch

```
Δx_seam ~ N(0, σ_seam)
σ_seam = 0.20 · e(seamMovement) · grassCover^0.7 · (0.5 + 0.8·moisture) · hardnessFactor
p(seam upright) = 0.45 + 0.40 · e(accuracy)      // otherwise σ_seam × 0.25
```

**The direction is random, and that is the point.** Swing is chosen; seam is
not. Neither bowler nor batter knows which way a seaming ball will go, which is
why a seaming pitch is harder to bat on than a swinging one at the same
deviation. Modelling seam as a directional choice would quietly remove the thing
that makes green tops frightening.

### 6.5 Spin

```
Δx_spin = 0.55 · e(turn) · Ψ_grip · D_type · ζ
Ψ_grip  = 0.25 + 0.75 · gripIndex(dryness, abrasion, cracks, soil)
```

| Delivery | Lateral multiplier | Sign | Notes |
|---|---|---|---|
| Off break | 1.00 | into RH bat | |
| Leg break | 1.15 | away from RH bat | more turn, less control (`D` above) |
| Top spinner | 0.20 | — | bounce, not turn |
| Googly | 0.90 | reversed | |
| Doosra | 0.75 | reversed | highest execution difficulty |
| Arm ball | 0.15 | reversed | straight on, beats the inside edge |
| Carrom ball | 0.85 | reversed | |

**Drift** — in-air lateral movement opposite the eventual turn, from the seam
axis and revolutions: `0.35 · e(drift) · Φ_atm`. **Dip** shortens the effective
length by 0.15–0.60 m, scaled by revolutions and `e(drift)`. Dip is the spinner's
real weapon: it feeds directly into Stage 4/5 as a length mismatch, which is
exactly how a batter ends up stumped having come down the pitch to a ball that
was never there.

**Which deceptions a bowler even has** is decided by his action, not by which
way his stock ball turns: a googly belongs to a wrist-spinner and a doosra or
carrom ball to a finger-spinner. Turn direction does not decide it — a left-arm
wrist-spinner turns the ball *into* a right-hander and still bowls a googly.

**Wrong'un detection** is a Bernoulli resolved in Stage 4, not here:

```
p(detect) = logistic( 3.2 · (e(spinPlay) + experienceVsThisBowler − deception(type)) )
```

On failure the batter's *perceived* turn direction is flipped. A googly then
produces an inside edge or an LBW as a direct geometric consequence.

### 6.6 Bounce

Height at the stumps for a ball pitching at `y_p`:

```
z_s = zRef(y_p, v, bowlerReleaseHeight) · B_pitch + N(0, σ_vb)
σ_vb = 0.02 + 0.16 · cracks · deterioration
```

`zRef` is the rebound trajectory for a reference pitch: roughly 0.06 m for a
yorker, 0.79 m (just over the stumps) for a good length, rising steeply through
the back-of-a-length band to 1.41 m at 12 m, peaking around 1.71 m at 15 m — the
throat ball — and then **falling away again**, because a genuine long hop has
already come down by the time it reaches the batter. That last part is why a
long hop is a long hop and not a bouncer, and a monotonic curve produces neither
a bouncer over the shoulder nor a wide over the head.

`B_pitch ∈ [0.72, 1.28]` spans a slow low deck to a hard bouncy one.

`σ_vb` is the day-five number. At `cracks = 0.8, deterioration = 0.9` it is
0.14 m of standard deviation on bounce height — enough that a good-length ball
can shoot along the ground or take off, and enough to make batting last on a
worn pitch genuinely different rather than just slightly worse.

---

## 7. Stage 4 — Batter read and shot selection

### 7.1 Perception

The batter does not see the ball that was bowled; he sees an estimate.

```
ŷ = y_p + η_y,  x̂ = x_s + η_x,  ẑ = z_s + η_z

σ_perception = 0.32 · (1.60 − 0.90·e(technique))
                    · (1.50 − 0.50·e(concentration))
                    · Λ_settle · Λ_pace · Λ_deception · Λ_light

Λ_settle    = 1 + 0.75 · exp(−ballsFaced / 8)
Λ_pace      = 1 + 0.90 · max(0, (v − v_comfort) / 40),  v_comfort = 120 + 30·e(pacePlay)
Λ_deception = 1 + deception(type) · (1 − experienceVsThisBowler)
Λ_light     = 1 + 0.35 · (1 − lightQuality)
```

Post-decision-point movement is under-perceived by design: the batter's estimate
includes only the deviation that had occurred by his decision point, plus a
partial extrapolation scaled by `e(technique)`.

**`Λ_settle` is doing a great deal of work.** At `ballsFaced = 0` it is 1.75; by
15 balls it is 1.12; by 40 it is ~1.0. That single decaying term produces:

- the brief's "far more vulnerable in his first 10–15 balls",
- a dismissal hazard that *falls* as the innings goes on,
- and therefore the innings-score distribution Section 3 demands — many
  single-figure scores, a fat 20–40 band, a long thin tail to 150+ — without
  ever sampling from a score distribution.

The shape of the innings distribution is not a target the engine aims at. It is
what a falling hazard produces.

### 7.2 Shot selection

Softmax over the legal shot set given the **perceived** ball:

```
U(shot) = geometricFit(shot | ŷ, x̂, ẑ)
        + w_a · attributeFit(shot, batter)
        + w_r · riskAppetite · reward(shot | fieldSetting)
        − w_d · difficulty(shot | ŷ, x̂, ẑ)

τ_shot = 0.30 · (1 + 0.50·(1 − e(composure))) · (1 + 0.40·P)

riskAppetite = base(role, userAggressionSetting)
             + 0.90 · requiredRatePressure
             − 0.50 · (1 − settledness)
             − 0.40 · newBatterCaution
             + bigMatchFactor terms
```

`reward(shot | fieldSetting)` reads the **actual current field**. Lofting over a
vacant deep midwicket scores more than lofting to a man there; a gap at third
man makes the steer worth playing. This is what makes the AI captain's field
settings matter to the batter rather than being decoration.

Shot set: leave, forward and back-foot defensive, push into the off side, drive
(straight/cover/on), back-foot punch, work off the hip, hit down the ground,
cut, late cut, pull, hook, flick, glance, sweep, slog sweep, reverse sweep, loft
over the off and leg sides, ramp.

**The good-length band needs its own answers.** The first shot table had every
attacking stroke wanting either a half-volley or a long hop, so a good length —
the most common delivery in cricket — left a batter with nothing but a block or
a leave. Batters left 29% of a Twenty20. The back-foot punch, the work off the
hip and the hit down the ground exist to cover the band that bowlers actually
bowl in.

**The upside is priced by what the shot would score, not by how dangerous it
is.** Multiplying the reward by `shot.risk` made a wild stroke attractive for
being wild; it is `powerFactor` that says what a shot is worth. A defensive
stroke suits a far wider range of deliveries than a loft does and so wins on
fit every time — the reward term is what has to pay for the attacking shot, and
it has to pay enough or nobody ever plays one.

**A leave is scored on the same scale as a stroke.** Giving it its own formula,
or a forgiving tolerance, makes it fit every ball and beat a defensive shot to
almost any delivery. In this model a leave has a *tight* tolerance — it suits
exactly one kind of ball, one going past off stump — and it is charged for the
delivery it uses up, which is why nobody leaves in a Twenty20 and a red-ball
opener leaves all day.

### 7.3 The key move

The batter selects his shot from `(ŷ, x̂, ẑ)` — the ball he *thinks* is coming.
Stage 5 then evaluates that shot against `(y_p, x_s, z_s)` — the ball that
actually arrived. **Every edge, every play-and-miss and every mistimed pull in
this engine is the gap between those two.** There is no "edge probability"
anywhere in the codebase.

---

## 8. Stage 5 — Contact

Each shot has an ideal interception point and a tolerance ellipsoid. Form the
mismatch between where the bat is going and where the ball is:

```
batPoint = shotIdeal + adjust · (perceived − shotIdeal),   adjust = 0.55 + 0.40·e(technique)
batPoint.x clamped to [−0.46, +0.86] m,  batPoint.z clamped to [0, 1.75] m

d = ( y_p + v·timingError − batLength , x − batLine , z − batHeight )

timingError ~ N(0, σ_t)
σ_t = 0.008 · (1.50 − 0.90·e(timing)) · Λ_settle · (1 + 0.50·P)     seconds

contactQuality  q = exp( −½ · dᵀ W d )        ∈ (0, 1]
```

Two things here are load-bearing and were both wrong in the first
implementation:

**The timing constant is in seconds and gets multiplied by the ball's speed.**
8 ms at 140 km/h is 0.31 m, about half a shot's tolerance. The original 55 ms
put the bat more than two metres from the ball and nobody middled anything. It
also means timing matters more against pace than against spin without a line of
code saying so.

**The bat's reach is capped, and asymmetrically.** A batter can stretch a long
way outside off and barely at all outside leg. Without the clamp the bat
followed the ball wherever it went — batters middled deliveries a metre wide of
off stump, and wides, bowled and lbw all but disappeared from the game at once.

`W` is the inverse tolerance matrix for that shot, scaled by `e(technique)`,
`e(footwork)` (front-foot or back-foot component depending on the shot), and the
shot's intrinsic difficulty. A reverse sweep has a much tighter `W` than a
forward defence, which is why it goes wrong more often.

**Contact point is read off the geometry**, not rolled:

| Condition | Contact point |
|---|---|
| `d` outside the bat's reach envelope | beaten — played and missed |
| `Δx > +tol` (ball further away than played for) | outside edge; thickness ∝ `Δx − tol` |
| `Δx < −tol` (ball came back into him) | inside edge → pad, stumps, or fine leg |
| `Δz > +tol` (bounced more than expected) | top edge, splice, or glove |
| `Δz < −tol` (kept low or skidded) | bottom edge, or through the gate |
| `Δy < −tol` (fuller than played for) | leading edge, or yorked |
| `Δy > +tol` (shorter than played for) | splice if it is bouncing, otherwise a mistimed shot off the middle |
| all within `tol` | middled; `q` near 1, timing decides the rest |

**A leading edge needs a horizontal bat.** It is a face-turning fault: playing
*across* the line of a ball that arrived fuller than expected. Getting the
length wrong to a straight-batted drive mistimes the shot — which the contact
quality already records — it does not turn the face. Mapping every length error
to a leading edge put them at 11% of all deliveries, roughly ten times reality,
and filled the slips and the covers with looping catches.

Then, on a miss:

- projected path within the stumps → **bowled**;
- struck the pad first → **LBW candidate** (§9.4);
- otherwise through to the keeper, and a possible **stumping** (§9.6) or byes.

This table is the whole of the brief's Stage 5 list — beaten, feather, thick
edge, inside edge, top edge, leading edge, splice, gloves, middled — derived
rather than enumerated.

---

## 9. Stage 6 — Trajectory, fielding and dismissal

### 9.1 Off the bat

```
θ = θ_shot(shot) + Δθ_contact(contactPoint, thickness) + N(0, σ_θ(q))
φ = φ_shot(shot) + Δφ_contact + (1 − q) · noise
v_e = (0.35 + 0.65·q) · ( κ_bat · e(power) · e(timing) + κ_pace · v_in ) · shotEfficiency
```

`Δθ_contact` is why edges go where edges go: a thin edge deflects a few degrees
and carries to the keeper, a thick one deflects 20–35° and is gully's problem.
`σ_θ` widens as `q` falls, so a mistimed shot is also a less predictable one.

Carry uses projectile motion with quadratic drag — ball mass 0.156 kg,
`C_d ≈ 0.50`, cross-section 0.0041 m². A six needs 68–75 m of carry depending on
the ground.

### 9.2 The ground

Boundaries are an **ellipse**, with separate straight and square distances, not
a circle. Real grounds are not circular, and the difference decides whether the
percentage boundary option is over long-on or over square leg.

### 9.3 Fielding

A field setting is a list of positions `(x, y)` with a reach radius and a
reaction time. For each ball in play the engine computes time-to-intercept
against ball-time-to-that-point.

**The bowler is a fielder.** He is not in the field setting, because nobody
places him, but he is unquestionably out there. Leaving him out meant nothing
stopped a ball pushed back down the pitch and every soft defensive shot became a
single. He fields with a `mobility` of 0.32 — finishing his action off balance
and going the wrong way, he stops what comes to him and takes the occasional
return catch, but he does not cut off a drive travelling at 30 m/s.

**A catch in the cordon and a catch in the deep are different questions.**

- The **cordon** takes the ball *on its way through*, at chest height, with no
  time to judge it: an along-the-flight-path question.
- The **outfield** takes it *coming down*, having run to where it will land: a
  can-he-get-there-in-time question.

Modelling the deep like the cordon catches nothing at all — a fielder 62 m out
is four metres under a ball that is still ten metres up as it passes him.

```
difficulty = w1·(distanceToTravel / timeAvailable)
           + w2·(v_e / 25)
           + w3·overShoulder + w4·heightPenalty + w5·diving + w6·sunOrLights

p(catch) = logistic( α · e(catching or reflexes) − β · difficulty − γ · P )
```

Close catchers (slip, gully, short leg) use `reflexes`; the outfield uses
`catching`. Dropped catches exist, are reported as drops, and are attributed.

**"Genuine chance" needs defining or the drop-rate target is meaningless.** The
definition used: any aerial ball entering a fielder's reach envelope with
`p(catch) ≥ 0.15`. Calibration reports both the raw figure and the figure
restricted to `p ≥ 0.40` ("regulation"), because the two are what commentators
mean at different moments and only one of them can match a single band.

**Along the ground**, two distinct things happen and conflating them wrecks the
boundary rate:

- **Interception.** A fielder stops the ball where it passes him, if he can get
  across to its line before it does. His sideways speed for this is 3.0 m/s —
  a step and a dive across the line of a ball already travelling, not a sprint.
  At full sprint speed an infielder cut off everything within nine metres of
  himself and the boundary rate sat near zero. **A fielder cannot intercept a
  ball that flew over his head**: only once it has pitched is it his to stop,
  which is the entire point of hitting over the infield.
- **The chase.** If nobody cuts it off, the nearest man runs it down, and how
  long that takes decides one, two or three.

Misfields are Bernoulli from `groundFielding`; overthrows from `throwArm` and
`P`; relay throws from the deep.

### 9.4 LBW

1. Ball struck the pad, and did not hit the bat first (both known from Stage 5).
2. Pitched outside leg → not out. Geometric: `x` at `y_p` is `< −0.1143 − r_ball`.
3. Impact in line, **or** outside off with no shot offered (shot ∈ {leave, no stroke}).
4. Project the remaining path from impact, continuing the swing/turn already in
   progress, and test against the stumps.
5. Umpire decides: `p(out) = logistic(α · clarityMargin) ± umpireError`, where
   `umpireError` scales with the level of the ladder. A district umpire is
   materially worse than an ICC elite panel umpire, and the career layer should
   feel that.
6. **DRS**, where the competition has it: ball-tracking is ground truth;
   umpire's call applies to marginal impact and marginal hitting; three reds is
   out, one red leaves the on-field decision standing. Reviews are a tracked
   per-innings budget, and the AI captain spends them with a policy.

### 9.5 Run outs

- The call: striker calls in front of square, non-striker behind. Call quality
  from `runningBetweenWickets` and `composure`.
- Hesitation is Bernoulli; on hesitation the running start is lost (~0.35 s).
- Batters cover 17.7 m at 6.6–8.6 m/s from `speed`, plus a turn cost for second
  and third runs.
- **A run being arithmetically available is not a reason to take it.** Batters
  want a comfortable margin (0.74 s) before setting off without thinking; below
  that they take it only sometimes, and below zero only rarely. Treating every
  possible single as a single turned every push to a close fielder into a run
  and left the game with too few dots *and* too few boundaries at the same time.
- Fielder: time to the ball + pick-up (from `groundFielding`) + throw flight
  (from `throwArm`) + direct-hit probability, or collection and break at the
  stumps.
- Margins are compared directly. The batters' judgement of a risky second or
  third compares their *expected* margin against risk appetite — so bad runners
  are run out more, and so are batters under pressure.

### 9.6 The rest

**Stumped:** ball beat the bat, batter out of his crease (from footwork and shot
type — a slog sweep leaves you there), keeper collection from `standingUp` and
`glovework`. **Hit wicket** from the short ball and cramped shots.
**Byes and leg byes** when the ball beats bat and keeper, runs from how far it
went past. **Obstructing the field** and other rarities: modelled at their real
(tiny) rates so the record books have them.

---

## 10. The pitch

A first-class object that evolves within a match:

```
Pitch(
  hardness, grassCover, moisture, cracks, abrasion,
  pace, bounce, evenness, turn, gripSeam, deterioration,
  soilType
)
```

**Soil type sets the character of the evolution**, not just the starting values:

| Soil | Character |
|---|---|
| Red soil | Bounce and carry; holds together; cracks late but wide |
| Black soil | Slow, low, grips for spin from early; less carry |
| Sandy | Crumbles fast; dramatic day-4/5 deterioration |
| Clay | Binds hard; even bounce; slow to break up |

**Within a match:**

- **Day 1 morning** — moisture and grass are at their highest. Seam and swing
  are at their most dangerous; the toss matters most here.
- **Days 2–3** — moisture gone, grass flattened, cracks not yet open. The best
  time to bat, and the reason "bat first" is usually right.
- **Days 4–5** — abrasion and cracks rise, `σ_vb` climbs, `Ψ_grip` climbs. Spin
  and variable bounce take over. Fourth-innings chases are hard because of
  arithmetic that already exists, not a fourth-innings penalty.

**Weather:** rain and covers change moisture (covered rain does much less to a
pitch than uncovered), humidity and cloud feed `Φ_atm`, light quality feeds
`Λ_light` and the offer-for-bad-light rules.

**Dew** in day-night games is a state variable rising through the second
innings. It makes the ball wet: grip falls (spin is neutered), the ball skids on,
and the fielding side's error rates rise. It is one of the largest single swings
in the game and belongs in the toss decision.

**Venue archetypes:** green seamer, flat road, rank turner, bouncy hard deck,
slow low deck — each a distribution over the pitch parameters, with regionally
appropriate frequencies, so home advantage emerges from conditions rather than
from a "home bonus".

---

## 11. Fatigue and spells

Bowler fatigue accumulates within a spell and within a day, and recovers between
them. A bowler's fourth over of a spell is worse than his first — `F` in §5 and
the pace term in §5 both bite. Spell length is the AI captain's decision, and a
captain who bowls his quick into the ground pays for it later in the innings.

Batter fatigue matters in long innings and in heat, and feeds `Λ_settle`'s
counterpart at the far end: concentration lapses after long occupation, which is
why batters get out in the over before a break.

---

## 12. Rain, and Duckworth–Lewis

A limited-overs match can be interrupted, shortened or abandoned.

`RainModel` draws the whole forecast **before a ball is bowled**, from the
conditions stream, and always takes the same number of draws whether it rains
once, twice or not at all. Both halves of that matter. Drawn ball by ball, the
weather would depend on how many numbers the batting had used, so changing the
shot model would change the forecast; a variable draw count would make one
shower reshuffle the rest of the innings.

Rain reads the same cloud and humidity the swing model reads — the day the ball
hoops is the day the covers come on — and squared, because rain needs both
together.

`DuckworthLewis` prices what is lost. The naive fix, scaling the target by
overs, is wrong in a way every cricket follower can feel: a side chasing 250 in
50 asked for 125 in 25 has been handed the game, because it can bat its whole
innings at six an over with ten wickets in hand. **Wickets are a resource too.**

```
Z(u, w) = asymptote[w] × (1 − exp(−decay[w] × u))
```

The table is **measured from this engine**, never borrowed — see
`docs/CALIBRATION.md`. It is monotone in three directions at once: resources
rise with overs, fall with wickets, and `A·b` — the run rate off the first
remaining ball — falls with wickets. The third is easy to miss and matters
most: if it rose, then at short overs a wicket would be worth *having*.

The target is revised at every stoppage, and that is what makes the abandoned
case fall out of the ordinary result logic rather than needing one of its own: a
stoppage that takes a chase to nought overs remaining leaves the side with
resources it never used, and the revised target for those resources **is** par
plus one.

The one claim about a stoppage that holds without qualification is the fairness
theorem: **rain never moves a side relative to par.** A stoppage takes away
future overs, not past ones, so the resource a side has used is untouched. Two
claims that do *not* hold, both of which looked obvious and were tested into the
ground:

- A shortened chase is not played faster. The target scales with resources, so
  the required rate barely moves.
- Cutting a chase short does not always ask more per over. A side almost home is
  asked for *less*, which is why a captain well ahead of the rate wants the
  covers on.

---

## 13. The Decision Review System

Two things are modelled, and keeping them apart is the whole design:

1. **What the ball did.** `BallTracking`, measured in Stage 6: the three lbw
   questions as *signed margins* rather than yes or no. "Pitched in line" and
   "pitched in line by two millimetres" are different facts, and only the second
   explains umpire's call. One unit is one tolerance width.
2. **What the players thought it did.** A batter halfway down the wicket and a
   bowler in his follow-through are both guessing, and they guess worse than the
   umpire, who is directly behind it.

An lbw is only as clear as its least clear question, so `outMargin` is the
minimum of the three.

### Who errs about what

Both the umpire and the players read **position** well and **height** badly,
and that asymmetry is doing most of the work:

- A batter *knows* where he was hit and whether it pitched outside leg — he felt
  it. Nobody knows whether it was going on to hit, which is the question the
  technology was invented for. Reading all three equally badly made reviews
  almost entirely noise on marginal wicket-hitting: three in four came back
  umpire's call.
- The umpire misjudges the **margin**, not the verdict. A flat error probability
  — which this was — turns a plumb lbw into not out at the same rate it turns a
  marginal one, so clear mistakes essentially never happen and a review system
  has nothing to catch.

### Whether anybody goes upstairs

A side reviews when it believes the umpire is wrong by more than the
umpire's-call band *plus* a confidence threshold. Clearing the band matters:
a decision that comes back umpire's call has cost a review's worth of time and
changed nothing, which is why captains say "it's going to be umpire's call,
don't" out loud on the stump mic.

An overturn and an umpire's call both leave the review intact. Only agreement
with the umpire costs one.

Reviews draw from their own stream, so adding the whole system could not move a
single ball of any baseline that existed before it.

**Where it exists** is a property of the rung, not the format. Below
`DrsTuning.minimumLevelStandard` there is one umpire, no cameras and no appeal
— which is one of the things that makes climbing the ladder mean something.

**Known limitation.** Only lbw is reviewable. Caught-behind reviews need an
edge-detection model the engine does not have yet, and the absence shows in the
split: about four reviews in five are taken by the batting side here, against
something nearer even in real cricket, because a bowler's main reason to go
upstairs is a faint edge nobody heard.

---

## 14. Calibrating this thing

The parameters above interact. Widening `σ_x` raises wides *and* boundaries *and*
dismissals. Hitting Section 3's bands by hand-tweaking is not tractable, so
calibration is structured deliberately:

**Tier 1 — physical constants.** Pitch geometry, ball mass, drag, running speeds.
Not tunable. Wrong values here are bugs.

**Tier 2 — model parameters.** Everything in §§4–10. Tuned rarely, with a
cricketing argument, not to chase a number.

**Tier 3 — calibration knobs.** A deliberately small set of global scalars, each
chosen to move one target family roughly monotonically:

| Knob | Primarily moves | Side effects |
|---|---|---|
| `executionSpreadScale` | dot %, boundary %, wicket rate | wides |
| `perceptionErrorScale` | wicket rate, innings-length distribution | edges vs bowled mix |
| `contactToleranceScale` | boundary %, strike rate | dismissal mix |
| `catchDifficultyScale` | catch/drop rate | caught share of dismissals |
| `aggressionBias` (per format) | run rate, dot % | wicket rate |
| `lbwStrictnessScale` | LBW share | bowled share |

Calibration is coordinate descent over Tier 3 against the target bands, with the
harness reporting the **local gradient** of each metric with respect to each knob
(`--report=sensitivity`). That turns the job from whack-a-mole into a
short, repeatable procedure — and it makes it obvious when a target can only be
hit by breaking another, which is the signal that a Tier 2 model is wrong rather
than a knob being mis-set.

Every run is recorded in `docs/CALIBRATION.md`.

---

## 15. Known modelling questions

Open items where the model above makes a choice that should be challenged.

1. **Humidity and swing.** The physical evidence for humidity increasing swing
   is contested. `Φ_atm` includes it because players and viewers believe it, and
   a cricket game that ignores overhead conditions feels wrong. Flagged as a
   game-feel decision with its own knob rather than a physical claim.
2. **The decision point at 0.38 s.** Real elite batters pick up cues from the
   bowler's hand and hips well before release. The model compresses all of that
   into perception error. A richer model would give good players earlier
   information rather than more accurate information.
3. **"Genuine chance"** (§9.3) is a definition, not a measurement, and the drop
   rate target is only meaningful relative to it.
4. **Correlated failure.** Real collapses are partly social — batters watch each
   other fail. `P` captures some of this through the wicket-cluster term, but
   whether that is enough will only be visible in the distribution of team
   totals, not in per-ball metrics.
5. **Bowler belief convergence** (§4) has no empirical anchor. It is chosen to
   make the career arc work; it needs a sanity check that a good player does not
   become permanently "solved".
6. ~~Play-and-miss sits around 19% of deliveries~~ — **fixed in Phase 4.** The
   bat's edge was modelled as a cliff. See §16.
7. **The hazard curve falls over the first twenty balls and then flattens and
   rises slightly.** The early fall is the settling model working. The late rise
   is a set batter accelerating, which is right for Twenty20 but needs checking
   against the longer formats in Phase 3, where it should keep falling.

---

## 16. The bat's edge

The largest known defect in the engine, diagnosed and then fixed. Kept here in
full because the diagnosis is more useful than the patch.

### The defect

**A bat has an edge; a tolerance envelope does not.** A ball inside the
envelope made contact and a ball a millimetre outside it passed through thin
air. So the deliveries that should have been feathering to the cordon were
beaten instead — which is a forced dot, a ball nobody can catch, and a batter
who survives to score later.

One defect, seen from four sides (600 Twenty20 matches):

| Metric | Before | Band | After |
|---|---:|---|---:|
| Run rate (per over) | 8.83 | 8.0 – 8.8 | 8.60 |
| Dot ball % | 36.5 | 30 – 36 | 36.5 |
| Balls per wicket | 19.3 | 16 – 19 | 17.6 |
| Caught % of dismissals | 55.6 | 56 – 62 | 58.2 |
| Play-and-miss % | ~19 | ~10 – 12 (real) | lower |

### Why no knob could fix it

`perceptionErrorScale` is the primary wicket-rate control:

| Scale | Run rate | Dot % | Balls/wkt |
|---:|---:|---:|---:|
| 0.96 | 8.83 | 36.5 | 19.3 |
| 1.00 | 8.59 | 37.4 | 19.3 |
| 1.03 | 8.32 | 38.6 | 18.2 |

Balls per wicket wants it **up**; dot % wants it **down**. CLAUDE.md §5 names
exactly this as the signal that a Tier 2 model is wrong rather than the
calibration.

### The fix, and the three things it needed

Feathering the edge alone is not enough, and the first attempt made things
*worse* — the wickets it should have produced leaked away as runs. A feather
has to behave like a **deflection**, and three separate parts of Stage 6 were
treating it like a stroke:

1. **Direction.** Edge thickness was monotonic in how far the bat missed by.
   But beyond the envelope the ball is catching the *very outer* edge — the
   thinnest contact there is — so a feather is a near-straight deflection to
   the keeper, not the squarest one of the lot. Read the wrong way round, every
   feather flew to gully. Measured: the median feather went 30° off straight;
   now 9°.
2. **Carry.** Given a defensive stroke's own elevation, two feathers in five
   never got off the ground and the median of the rest carried 10.7 m — they
   died in front of a keeper standing at 14 m. Now 98% are airborne and the
   median carries past the cordon.
3. **Pace.** Scored by contact quality like every other contact, a feather was
   the slowest ball on the field. A deflection keeps most of the pace it
   arrived with: the bat puts almost nothing in and takes almost nothing out.

Together: **23 catching chances became 236**, and the fours a feather used to
run away for went to zero.

### The knock-on, and why it was worth it

The dismissal hazard stopped falling as a batter settles. A new batter's extra
mis-reads used to produce *beaten* balls, which can be bowled or lbw; feathered,
they become edges the cordon sometimes puts down. That effect is real and the
model now has to carry it explicitly: `settleWeight` rose from 0.45 to 0.58.

There had been no room to raise it before — it took dot % out of band — and the
feather made the room. That is the shape of a real fix: it does not just move a
number, it *creates slack* somewhere else.

`DlsTuning` and `WorldTuning` were both re-fitted afterwards, because both are
measured from engine output and the engine moved.

### What is still out

Not everything, and none of it caused by this:

- **List A balls per wicket, 30.4 against a band of 35–40.** Was 32.8 before the
  fix. Pre-existing and slightly worse.
- **Four-day run rate, 3.79 against 3.0–3.5.** Was 3.83. Pre-existing, unmoved.
- **Run outs, 8.2% of Twenty20 dismissals against 4–6%.** Was 8.8%. Pre-existing
  and slightly better.

The first two say the same thing the T20 numbers used to: the longer formats
have not had a calibration pass of their own. Phase 2's brief was "T20 only,
hit T20 calibration", and that is what has been held.

---

## 17. Running between the wickets: the call, not the stopwatch

Phase 4 left run outs at 8.2% of Twenty20 dismissals and 8.6% of List A ones
against a band of 4–6%, and the Twenty20 dot rate one and a half points above
its band. Both had the same cause, and it was in the same place: the batters
knew something they cannot possibly know.

### What was wrong

The running model computes a **margin** — the seconds between the batter
reaching the far crease and the ball reaching the stumps — and the old code let
the batter read it exactly. He set off whenever the true margin was better than
−0.13 s, and a fixed probability then decided whether the fielding side
converted.

That is not a run out. It is a batter looking at a stopwatch, seeing that he is
a tenth of a second short, and going anyway. Three things followed from it:

1. **Every run out was a dice roll over a run the batter could see he would
   lose.** The batter's skill at *running between the wickets* only moved how
   deep into the red he would go, never how well he read it, so the attribute
   with "judgement" in its name had nothing to judge.
2. **Run outs could only be traded against singles.** `riskyRunMarginSeconds`
   was the one knob that moved either, and it moved both the same way: sweeping
   it from 0.12 to 0.50 took the Twenty20 dot rate from 40.9% to 32.5% and the
   run-out share from 6.2% to 27.1%. Two bands, opposite moves of one knob —
   the CLAUDE.md §5 signal that the model, not the number, is wrong.
3. **The man at the other end did not exist.** Running is the only thing in
   cricket two batters decide at once, and one of them was not consulted.

### What it does now

The batter judges the margin and acts on the judgement; the fielding side acts
on the truth.

```
judged        = margin + N(0, σ(judgement))
partnerJudged = margin + N(0, σ(partnerJudgement))
```

σ runs from 0.13 s for the best runner in the game to 0.38 s for the worst
(`runJudgementSigmaBest` / `Worst`). The striker calls on `judged`; the
non-striker can **send him back** if his own read is clearly red. That second
read is a veto on an obvious loss, not a second opinion on a close one — which
matters, because the minimum of two unbiased reads is biased low, and making
both batters judge every single from scratch made the pair systematically
pessimistic and put four points on the dot rate in all three formats at once.

A run out is now what it is in cricket: **the call was wrong.** He went because
he thought it was there. The worse his judgement, the wider his error, the more
often he is short — and the attribute finally does the thing it is named after.

Two supporting corrections came out of the same work:

- **The conversion scale is physical.** How far behind the throw the batter is
  used to be measured in units of his own *willingness*, which made a good
  runner more likely to be out for the same true margin, because his willingness
  figure was smaller. It is now `runOutCertaintySeconds`, a constant: 0.8 s
  behind and the throw gets you every time, a tenth behind and you mostly dive
  in. Widening it from 0.16 s to 0.80 s is what finally separated the dot rate
  from the run-out rate — the two had been the same measurement.
- **Turning is slower than running.** `turnCostSeconds` 1.00 → 1.35. Twos are
  the only thing this touches, so it trims the run rate without moving the dot
  rate at all.
- **A format's caution moves the threshold, not only the odds.** Expressing it
  purely as a bonus to the tight-single gate let it saturate at both ends: a
  Twenty20 batter sat pinned against the 0.97 ceiling and a multi-day batter
  against the 0.03 floor, so moving the Twenty20 figure anywhere between 0.31
  and 1.22 changed *nothing measurable*, and a four-day innings had no lever of
  its own at all. The bonus now also shifts what counts as a comfortable run
  (`comfortFormatWeight`), and runs above that threshold are not gated — which
  is the honest statement of the difference. A Test batter turning down a single
  he would take in a one-day game is refusing a run he counts as **tight**, and
  what counts as tight is exactly what changes between the formats.

### What it bought

| | Before | After | Band |
|---|---|---|---|
| T20 dot ball % | 36.5 | 35.3 | 30–36 |
| T20 run out % of dismissals | 8.2 | 5.0 | 4–6 |
| T20 run rate | 8.83 | 8.73 | 8.0–8.8 |
| T20 stumped % | 0.4 | 1.1 | 1–3 |
| List A run out % | 8.6 | 6.3 | 4–6 |
| Four-day run rate | 3.86 | 3.47 | 3.0–3.5 |
| Four-day run out % | 1.9 | 0.9 | — |

Twenty20 now meets **every** band in the harness, and the four-day run rate is
in band for the first time. The Twenty20 dot rate had been out since the first
calibration in Phase 3; the four-day run rate had been out for just as long, and
its cause turns out to have been the saturated lever above rather than the
"twos are still slightly too easy" the Phase 3 log guessed at.

The four-day figure is deliberately low and is not a miss: the brief states
dismissal shares "all formats combined", and Test cricket really does have
almost no run outs. What the model now produces — 5.5%, 6.0%, 0.9% by format —
is the shape of the real game rather than one number copied into three.

---

## 18. Nobody could aim at a wide

The white-ball wide rate would not move with the format. A fifty-over innings
made 2.4% wides against a band of 3–5%, a Twenty20 made 3.2%, and the gap was
immovable through every knob that touches line: the two formats share a wide
guideline, a bowler population and a line-error model, so nothing in the tuning
could separate them.

The cause was that **`WIDE_OUTSIDE_OFF` was not in the list of lines a bowler
can aim at.** Stage 1 picked from five bands — leg stump to the channel — so
every wide in the game was an execution error, a ball that missed its target
badly enough to cross the tramline. Two pieces of code downstream of that choice
were therefore unreachable: the wide-yorker term, whose own comment claims
"a good share of T20 wides actually come from" it, and the plan's preferred
line whenever the plan named that band.

That is not how white-ball cricket makes wides. A death bowler aims outside the
tramline on purpose and accepts the call as the price of not being hit; so does
any bowler to a batter coming at him. The line is now in the candidate set, with
a base utility of −2.2 that keeps it rare, plus the death-overs term that was
already written and a new one (`wideToAggressorWeight`) for a batter attacking:

```
utility(WIDE_OUTSIDE_OFF) = -2.2
                          + 3.6 x deathBowling   (death overs, white ball)
                          + 2.3 x batterAggression  (white ball)
```

`DOWN_LEG` stays out, and stays out deliberately: a leg-side wide is always an
accident, never a plan.

Measured: T20 wides 3.2% → 4.3%, List A 2.4% → 3.1%, both inside the 3–5% band.
Four-day cricket is untouched — its wide guideline is judged on whether the
batter could have played at it, and a red-ball bowler does not aim there.
