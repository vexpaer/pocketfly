# Simulation model

## What the runtime computes

PocketFly v0.1 uses a simplified leaky integrate-and-fire (LIF) model over
the connectome graph:

```
V[i, t+1] = decay · V[i, t] + Σ_j ( spike[j, t] · w[j, i] ) · gain + drive[i] + noise[i]
```

- If `V[i] ≥ threshold`, neuron `i` spikes and `V` resets
  (`resetFraction · V`, default 0).
- A neuron that spiked on the previous step is **refractory** for one step
  (membrane clamped) — this caps firing rate at 50% and tames runaway
  recurrent excitation.
- `drive[i]` is the persistent external input (sensory drive) set by the
  game's encoder each frame.
- `noise` is zero-mean Gaussian, seeded deterministically per runtime.
- Each edge's weight is `(synapse count) / weightScale` from the manifest.

**Readout**: "activity" is an exponential moving average of spiking,
`a[i] ← a[i]·activationDecay + spike`, in `0..~6.7` for sustained firing.
Group activity is the mean over the group's enabled neurons. Action decoders
consume group activities, which is why behavior is graded rather than
all-or-nothing.

## What it is NOT

These are computational parameters chosen for stable, observable, graded
closed-loop behavior on a phone. They are **not** fitted to Drosophila
electrophysiology: no ion channels, no real time constants (one "step" is
not one millisecond), no inhibitory neurotransmitter classes (weights are
excitatory counts), no dendritic computation, no neuromodulation.
[scientific-limitations.md](scientific-limitations.md) has the full honesty
list.

## Why this model

- One multiply-accumulate per edge per step → the whole 16k-edge sample
  simulates at >20,000 steps/s per core on modest hardware.
- Parameter changes (threshold/decay/gain/noise) are meaningful to a
  non-expert and produce visible, explainable behavioral changes.
- The architecture (CSR + SoA + scatter-based propagation) does not change
  when a better model is dropped in; dynamics live in one function.

## Propagation timing

Spikes are scattered from the **previous** step's spike vector, so signals
take at least one step to cross a synapse. A stimulus to a visual neuron
therefore reaches descending groups after a few steps (visual → central →
descending ≈ 2-4 steps), which is what makes the pause/step mode
informative.

## Ablation

Ablation is a mask: disabled neurons never spike, never receive, and are
excluded from group means. Group ablation toggles the whole group. State is
fully reversible via "Reset all ablations"; `reset()` (episode reset) does
NOT clear ablations — that's deliberate so control/ablated comparisons are
cheap.

## Performance mode

The simulation timestep count per frame (4/6/8 in Eco/Balanced/Max) and the
frame rate cap (24/36/60 Hz) come from Settings. Neural time is therefore
decoupled from wall time by design; "steps/s" reported in the UI is the
runtime's own measure, not a wall-clock claim.

## Determinism

With `noise = 0` the runtime is fully deterministic (fixed RNG seed for
noise when enabled). The same stimulus sequence produces the same
activity — this is what makes the experiment protocols (bias, sweep)
meaningful and testable in CI.
