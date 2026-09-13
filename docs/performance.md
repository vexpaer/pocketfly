# Performance notes

## Budgets

The hard rule: **nothing per-neuron crosses the JNI boundary, and nothing
allocates per step.** The Kotlin↔C++ interface is batch-level (groups,
snapshots), and the step loop is allocation-free.

## Memory layout

- Graph: CSR — one `rowOffsets` array (N+1), one `targets` array (E),
  one `weights` array (E). Edge iteration is a linear scan; scatter
  propagation touches only edges out of spiking neurons.
- Neuron state: structure-of-arrays float/uint8 vectors (`potential`,
  `activation`, `drive`, `spiked`, `enabled`). Contiguous, prefetch
  friendly, trivially resettable.
- Sample brain (1,024 neurons, 16,111 edges): ~168 KB resident, measured.
- A full MaleCNS-scale brain (~140k neurons, ~20M edges): ~40 MB with f32
  weights, ~28 MB with u16 — comfortably in an Android app's budget, loaded
  once and kept for the process lifetime.

## Step cost

Host benchmark (committed test, `IntegrationBenchmark1000Steps`, x86_64,
8-core desktop class): **0.038 ms/step** for the sample brain with stimulus
driven (≈26,000 steps/s per core). On-device cost is expected to be similar
order for the sample brain; the in-app Benchmark screen (Brain → benchmark
via Experiments or Runtime Manager) reports real device numbers and can
export them.

The step is O(N + E_spiking): the integrate pass is a linear walk over N
neurons; the scatter pass walks only out-edges of spiking neurons.

## JNI discipline

- Per frame: one `setInputGroup` per bound sensory group, one `step(n)`
  batched call, a handful of `groupActivity` reads. ~10 JNI crossings per
  frame regardless of brain size.
- `publishSnapshot()` reads stats and group means in one pass — bounded by
  group count, not neuron count.
- The UI never polls the runtime; it reads immutable StateFlow snapshots
  published by the sim thread (~16 Hz history, per-frame game views).

## UI/rendering

- Game rendering is a single Canvas draw of immutable object snapshots —
  no allocation in the draw path, no recomposition of the whole screen per
  frame (view state flows into one composable).
- Charts draw polylines from ring buffers capped at 180 samples.
- Material 3 components are static per screen; only the small stat surfaces
  recompose as snapshots change.

## Power

Performance mode (Eco/Balanced/Max) caps frame rate at 24/36/60 Hz and
scales steps-per-frame 4/6/8. Long sessions on Eco keep the device cool;
the free-run dashboard and games both respect it. Backgrounding stops
sessions because their coroutines are scoped to screen lifetime
(`DisposableEffect`), not the application.

## Roadmap items for bigger brains

- Parallelize the integrate pass over row ranges (the graph write pattern
  is scatter; per-thread incoming buffers would be merged after).
- u16 weight path end-to-end (reader supports it; manifest chooses it).
- Optional activity downsampling for the inspector on 100k+ brains.
