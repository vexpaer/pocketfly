# Connectome package format (`.pflybrain`)

A `.pflybrain` file is a ZIP archive containing the manifest plus four
binary payload files. It is the unit of install/import for PocketFly
runtimes: the bundled Sample brain, a future Lite brain, and a converted
MaleCNS brain all use the same format.

## Contents

| Entry | Size | Description |
| --- | --- | --- |
| `manifest.json` | variable | Metadata, groups, dynamics defaults, checksum |
| `row_offsets.bin` | `(N+1) × 4` (u32) or `(N+1) × 8` (u64) | CSR row offsets |
| `targets.bin` | `E × 4` | CSR target neuron indices (uint32 LE) |
| `weights.bin` | `E × 4` (f32) or `E × 2` (u16) | Edge weights (synapse counts) |
| `neurons.bin` | `N × 8` | Per-neuron metadata, SoA layout |

`N` = neuron count, `E` = edge count. All integers are **little-endian**.

## Neuron ids

Neuron ids are implicit: `0..N-1` in the order they appear in the payload
arrays. Nothing is stored per-neuron except the metadata below.

## neurons.bin layout

For each neuron, 8 bytes concatenated in neuron order:

| Offset | Type | Field |
| --- | --- | --- |
| 0 | uint16 | group index (into `manifest.groups`) |
| 2 | uint16 | type index (into `manifest.types`) |
| 4 | uint16 | region index (into `manifest.regions`) |
| 6 | uint8 | side: 0 left, 1 right, 2 mid |
| 7 | uint8 | flags: bit0 = sensory/input, bit1 = output/descending |

## Manifest

```json
{
  "formatVersion": 1,
  "id": "sample-1024",
  "name": "Sample Brain",
  "mode": "sample",
  "description": "...",
  "attribution": "...",
  "neuronCount": 1024,
  "edgeCount": 16111,
  "offsetType": "u32",
  "weightType": "f32",
  "weightScale": 50.0,
  "groups": [
    {"id": 0, "key": "VIS_L", "name": "Visual Left", "kind": "sensory", "side": "left"}
  ],
  "types": ["sensory", "interneuron", "descending"],
  "regions": ["optic", "antennal", "central", "ventral"],
  "dynamics": {
    "decay": 0.82, "threshold": 1.0, "gain": 1.0, "noise": 0.02,
    "activationDecay": 0.85, "refractory": true
  },
  "checksum": {"algorithm": "sha256", "value": "..."}
}
```

- `formatVersion` must be `1` (checked on install).
- `mode` is one of `sample | lite | full`; the UI labels the active runtime.
- `groups[].key` is what games and experiments reference (e.g. `VIS_L`);
  the built-in games require the keys `VIS_L`, `VIS_R`, `DN_L`, `DN_R`,
  `DN_F` to function.
- `weightScale`: edge weights on disk are synapse counts; the runtime
  divides by this at load so dynamics operate on `w/scale`.
- `checksum` is the SHA-256 over the four payload files concatenated in the
  order `row_offsets.bin, targets.bin, weights.bin, neurons.bin`. Verified
  (and enforced) on install.

## Install-time validation

The Kotlin `BrainPackage` installer:

1. Parses and checks `formatVersion`.
2. Checks each payload file size against `neuronCount`/`edgeCount`.
3. Verifies the SHA-256 checksum.
4. Extracts to a staging directory, then atomically swaps it into place.

The native reader independently re-validates sizes, offset monotonicity,
and **every target index bounds check** — a corrupt package can never cause
an out-of-bounds memory access, it just fails to load with a readable error.

## Converting the MaleCNS connectome

PocketFly deliberately does not parse raw research data formats on device.
Use `data/tools/convert_malecns.py`:

```bash
python3 data/tools/convert_malecns.py \
    --edges edges.csv --neurons neurons.csv \
    --config config.json --output my_brain.pflybrain
```

The expected inputs are flat tables (CSV): one row per connection
(`source, target, weight`) and one row per neuron
(`id, name, type, side, region, neurotransmitter`). The `--config` JSON maps
your export's column names and classifies neurons into PocketFly groups
(sensory / central / descending). Because every dataset export differs, the
mapping lives in config, not code.

**Licensing**: the converted package inherits the source dataset license.
The converter writes whatever attribution you provide into the manifest; do
not redistribute dataset-derived packages without checking the original
terms. PocketFly's MIT license covers the code and the synthetic sample
brain only.

## Design notes

- CSR + structure-of-arrays keeps the graph contiguous and cache friendly;
  there are no per-neuron objects and no per-step heap allocations.
- u16 weights halve payload and RAM for large brains; the reader upconverts.
- u64 offsets exist for graphs beyond ~4 billion edges (MaleCNS does not
  need them; the field is for future-proofing).
- Neuron *names* are intentionally not stored in the binary: they'd dominate
  payload size for 100k+ neurons. The manifest can carry them later as an
  optional side table without breaking the format.
