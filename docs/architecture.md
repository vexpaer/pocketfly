# Architecture

PocketFly is three Gradle modules plus a data pipeline:

```
app (Compose UI)  ──uses──▶  core (pure Kotlin engine & schema)
        │
        └──owns──▶  simulator (JNI wrapper)
                          │
                          ▼
                    libpocketfly.so  (C++17 neural runtime)
```

## Threading model

There is exactly one neural runtime instance and exactly one simulation
thread (`pocketfly-sim`), owned by `RuntimeRepository` in `:app`.

- **Game play**: a `GameSession` coroutine on the sim thread ticks
  `GameEngine` at the frame rate of the current performance mode (24/36/60
  Hz). Each tick: encode world → drive input groups → `step(n)` on the
  native runtime → decode output groups → apply physics → publish an
  immutable `GameSession.View`.
- **Brain dashboard**: a free-run loop steps the runtime continuously and
  publishes `RuntimeRepository.Snapshot` plus a small ring-buffer `History`
  (throttled to ~16 Hz) for the timeline chart.
- **UI**: reads only immutable snapshots (StateFlow). It never touches the
  runtime directly except through `RuntimeRepository.onSim {}`, which hops to
  the sim thread.
- Game sessions and the free-run loop are mutually exclusive by convention;
  screens stop the other mode in `DisposableEffect`.

This keeps the native runtime single-threaded and lock-free, at the cost of
one thread. JNI call frequency is bounded (per group, per frame) — never per
neuron.

## Module responsibilities

### `:simulator` — native runtime

- `cpp/core/brain_format.{h,cpp}`: reader for the PocketFly brain package
  binary payload (CSR graph + per-neuron SoA metadata). Validates sizes,
  monotonic row offsets and target bounds; supports u32/u64 offsets and
  f32/u16 weights; applies the manifest `weightScale` at load.
- `cpp/core/runtime.{h,cpp}`: simplified LIF dynamics over the CSR graph.
  See [simulation.md](simulation.md).
- `cpp/jni/pocketfly_jni.cpp`: thin JNI bridge; one `jlong` handle per
  Kotlin facade instance.
- `src/main/java/.../sim/`: `PocketFlyRuntime` (facade), `BrainManifest`,
  `BrainPackage` (zip install, checksum, staging).
- `cpp/tests/`: host test suite (no JNI, plain g++; see
  `scripts/run-native-tests.sh`).

### `:core` — pure Kotlin

No Android dependencies, fully unit-testable:

- `game/`: `GameSpec` (schemaVersion 1) and JSON codec for
  `.pocketfly.json`.
- `engine/`: `GameEngine` (physics, collisions, pickups, rewards),
  `SensoryPipeline` (world → named channels), `ActionDecoders`
  (group activity → body command), `WorldObject`.
- `games/BuiltInGames.kt`: the three built-in games as data.
- `NeuralBridge`: the interface between engine and any neural runtime
  (real one in `:app`, deterministic relay fake in tests).

### `:app` — Compose UI

- `data/`: `RuntimeRepository` (runtime ownership, snapshots, ablation,
  import), `GameSession`, `GamesRepository` (custom games, import/export
  via SAF), `ExperimentsRepository` (scripted protocols, CSV/JSON results),
  `SettingsRepository` (DataStore).
- `ui/`: Material 3, dark-first theme, navigation hub + bottom bar, and the
  screens (Home, Play, Game, Brain, Create/Editor, Experiments, Runtime
  Manager, Settings, About).

## Data pipeline

```
research dataset (original license)
        │  data/tools/convert_malecns.py
        ▼
.pflybrain (zip: manifest.json + 4 binary payload files)
        │  import / bundled asset
        ▼
app files dir (extracted, checksum-verified)
        │  JNI
        ▼
C++ CSR runtime
```

The bundled `sample_1024.pflybrain` is produced by
`data/tools/generate_sample.py` with a fixed seed and committed to the repo
so CI and first-run devices have a real, working brain without downloading
anything. The app installs it from assets on first launch.

## Why not Unity / why Compose Canvas?

Small APK, no third-party game runtime, trivially CI-buildable, and the
games are 2D vector worlds. If a future encoder (compound eye, motion)
needs heavy compute it belongs in the C++ runtime anyway.

## Deviations from the original project sketch

- The `native/` folder lives inside `:simulator/src/main/cpp` so the Android
  build and the host test build share one source of truth; the spec allowed
  adjusting module layout to Gradle best practice.
- v0.1 ships three games instead of five: Light Chase, Flap and Obstacle
  Avoidance cover the top-down steering, single-output, and avoidance
  loops; Food Search and Maze reuse the same world primitives and are
  straightforward custom games (templates in the editor).
