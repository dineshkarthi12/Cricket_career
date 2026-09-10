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

`zRef` is the rebound trajectory for a reference pitch — roughly 0.05 m for a
yorker, 0.75 m (just over the stumps) for a good length at 135 km/h, 1.25 m for a
ball pitching at 12 m. `B_pitch ∈ [0.72, 1.28]` spans a slow low deck to a hard
bouncy one.

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

Shot set: leave, defend front, defend back, drive (straight/cover/on),
cut, late cut, pull, hook, sweep, reverse sweep, slog sweep, flick, glance, loft,
ramp/scoop, block-and-run.

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
d = ( y_p − ŷ , x_bat − x̂ , z_bat − ẑ , timingError )

timingError ~ N(μ_t, σ_t)
σ_t = 0.055 · (1.50 − 0.90·e(timing)) · Λ_settle · (1 + 0.50·P)     seconds

contactQuality  q = exp( −½ · dᵀ W d )        ∈ (0, 1]
```

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
| `Δy > +tol` (shorter than played for) | under-edge, gloved, splice |
| all within `tol` | middled; `q` near 1, timing decides the rest |

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

**Aerial:**

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

**Along the ground:** does it beat the 27.43 m ring? Then the boundary rider's
interception point sets runs available, against the batters' running. Misfields
are Bernoulli from `groundFielding`; overthrows from `throwArm` and `P`; relay
throws from the deep.

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
- Batters cover 17.7 m at 7.3–8.6 m/s from `speed`, plus a turn cost for second
  and third runs.
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

## 12. Calibrating this thing

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

## 13. Known modelling questions

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
