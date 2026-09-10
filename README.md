# Cricket Career

An offline Android career simulator for a single cricketer — college cricket to
international cricket. Text and numbers, statistical depth, ball-by-ball
simulation. No 3D, no network, no licensed content.

Think *Cricket Captain*, but you manage one player rather than a team. Selection,
contracts and results are things that happen *to* him.

## Status

**Phase 0 — repo skeleton, build, CI and design documents.** There is no match
engine yet.

## Build

```bash
./gradlew check                                  # engine + harness, bare JDK
./gradlew :sim-harness:run --args="--format=T20 --matches=1000 --report=calibration"
```

`:data` and `:app` are Android modules and join the build only when an Android
SDK is present. Force it with `-Pcricket.includeAndroid=true|false`. See
`CLAUDE.md` §7.

## Layout

| Module | What |
|---|---|
| `:engine` | Pure Kotlin/JVM. The simulation. No Android, deterministic from a seed. |
| `:data` | Room, repositories, save/load, seed database. |
| `:app` | Compose UI. Contains no cricket logic. |
| `:sim-harness` | JVM CLI for bulk simulation and calibration reports. |

## Documents

| | |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Working agreement: rules, module boundaries, conventions, phase plan |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Structure, event model, save/replay, world simulation, performance budget |
| [`docs/SIMULATION_MODEL.md`](docs/SIMULATION_MODEL.md) | The match engine maths |
| [`docs/CALIBRATION.md`](docs/CALIBRATION.md) | Target bands, method, run log |
| [`docs/OPEN_QUESTIONS.md`](docs/OPEN_QUESTIONS.md) | Decisions needing the project owner |

## Licensing note

All players, teams and venues are fictional. Team names are cities and regions,
never franchise names. No real player names, crests or logos are used or
accepted into the seed database.
