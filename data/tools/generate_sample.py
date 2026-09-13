#!/usr/bin/env python3
"""Generates the PocketFly sample connectome (data/sample/sample_1024).

The sample brain is a synthetic, structured network in the PocketFly brain
package format (see docs/connectome-format.md). It is NOT biological data:
it exists so development, CI, and low-end devices can run a real closed
loop without the full MaleCNS dataset.

Group layout (fixed indices, relied upon by tests and the app):

  0 VIS_L   sensory left      96
  1 VIS_R   sensory right     96
  2 OL_L    sensory left      32
  3 OL_R    sensory right     32
  4 CX_L    central           320
  5 CX_R    central           320
  6 INT     central           32
  7 DN_L    descending left   32
  8 DN_R    descending right  32
  9 DN_F    descending mid    32

Deterministic under a fixed seed. Outputs:
  data/sample/sample_1024.pflybrain   (zip package used by the app)
  data/sample/sample_1024/            (exploded directory used by host tests)
"""

import argparse
import hashlib
import json
import os
import random
import struct
import zipfile

SEED = 20260912
WEIGHT_SCALE = 50.0

GROUPS = [
    # (key, name, kind, side, size)
    ("VIS_L", "Visual Left", "sensory", "left", 96),
    ("VIS_R", "Visual Right", "sensory", "right", 96),
    ("OL_L", "Olfactory Left", "sensory", "left", 32),
    ("OL_R", "Olfactory Right", "sensory", "right", 32),
    ("CX_L", "Central Left Pool", "central", "mid", 320),
    ("CX_R", "Central Right Pool", "central", "mid", 320),
    ("INT", "Integrator", "central", "mid", 32),
    ("DN_L", "Descending Left", "descending", "left", 32),
    ("DN_R", "Descending Right", "descending", "right", 32),
    ("DN_F", "Descending Forward", "descending", "mid", 32),
]

TYPES = ["sensory", "interneuron", "descending"]
REGIONS = ["optic", "antennal", "central", "ventral"]


def build_ranges():
    ranges, start = {}, 0
    for key, _, _, _, size in GROUPS:
        ranges[key] = (start, start + size)
        start += size
    return ranges


def synapse_weight(rng, lo, hi):
    # Biased-toward-low synapse counts, resembling sparse strong contacts.
    return lo + int((hi - lo) * rng.random() ** 2)


class Edges:
    def __init__(self):
        self._by_src = {}

    def add(self, src, tgt, weight):
        assert 0 <= src < 1024 and 0 <= tgt < 1024, (src, tgt)
        edges = self._by_src.setdefault(src, {})
        edges[tgt] = edges.get(tgt, 0) + weight

    def to_arrays(self):
        offsets, targets, weights = [0], [], []
        for src in range(1024):
            edges = self._by_src.get(src, {})
            for tgt in sorted(edges):
                targets.append(tgt)
                weights.append(edges[tgt])
            offsets.append(len(targets))
        return offsets, targets, weights


def generate(seed=SEED):
    rng = random.Random(seed)
    ranges = build_ranges()
    edges = Edges()

    def sample(pool, k):
        return rng.sample(range(*ranges[pool]), min(k, ranges[pool][1] - ranges[pool][0]))

    # Visual -> central: ipsilateral-dominant with sparse, weak contralateral
    # contacts so a one-sided stimulus produces a clear directional signal
    # instead of saturating both descending pools.
    for vis_pool, cx_home, cx_away, dn_same in [
        ("VIS_L", "CX_L", "CX_R", "DN_L"),
        ("VIS_R", "CX_R", "CX_L", "DN_R"),
    ]:
        for v in range(*ranges[vis_pool]):
            for _ in range(24):
                edges.add(v, sample(cx_home, 1)[0], synapse_weight(rng, 3, 21))
            for _ in range(2):
                edges.add(v, sample(cx_away, 1)[0], synapse_weight(rng, 4, 12))
            if rng.random() < 0.25:  # sparse fast path to a descending neuron
                edges.add(v, sample(dn_same, 1)[0], synapse_weight(rng, 4, 12))

    # Olfactory -> central.
    for ol_pool, cx_home, cx_away in [("OL_L", "CX_L", "CX_R"), ("OL_R", "CX_R", "CX_L")]:
        for o in range(*ranges[ol_pool]):
            for _ in range(16):
                edges.add(o, sample(cx_home, 1)[0], synapse_weight(rng, 3, 18))
            for _ in range(1):
                edges.add(o, sample(cx_away, 1)[0], synapse_weight(rng, 3, 8))

    # Central recurrent loops: weak enough to sustain an echo without
    # igniting runaway activity from silence.
    for cx in ("CX_L", "CX_R"):
        other = "CX_R" if cx == "CX_L" else "CX_L"
        for c in range(*ranges[cx]):
            for _ in range(5):
                edges.add(c, sample(cx, 1)[0], synapse_weight(rng, 2, 9))
            for _ in range(1):
                edges.add(c, sample(other, 1)[0], synapse_weight(rng, 2, 6))

    # Central -> descending: strong ipsilateral drive, weak contralateral.
    for dn, cx_home, cx_away in [("DN_L", "CX_L", "CX_R"), ("DN_R", "CX_R", "CX_L")]:
        for d in range(*ranges[dn]):
            for c in sample(cx_home, 60):
                edges.add(c, d, synapse_weight(rng, 3, 21))
            for c in sample(cx_away, 3):
                edges.add(c, d, synapse_weight(rng, 2, 12))

    # Integrator loop feeding the forward descending group.
    for i in range(*ranges["INT"]):
        for _ in range(30):
            pool = rng.choice(("CX_L", "CX_R"))
            edges.add(sample(pool, 1)[0], i, synapse_weight(rng, 2, 10))
    for d in range(*ranges["DN_F"]):
        for c in sample("INT", 24):
            edges.add(c, d, synapse_weight(rng, 3, 14))
        for _ in range(10):
            pool = rng.choice(("CX_L", "CX_R"))
            edges.add(sample(pool, 1)[0], d, synapse_weight(rng, 2, 8))

    # Descending recurrent (slight persistence).
    for dn in ("DN_L", "DN_R", "DN_F"):
        for d in range(*ranges[dn]):
            for _ in range(3):
                edges.add(d, sample(dn, 1)[0], synapse_weight(rng, 2, 6))

    # Every neuron gets at least one outgoing edge so no state is inert.
    for src in range(1024):
        if src not in edges._by_src:
            edges.add(src, rng.randrange(1024), synapse_weight(rng, 2, 8))

    return edges.to_arrays()


def neuron_tables():
    group_idx, type_idx, region_idx, sides, flags = [], [], [], [], []
    ranges = build_ranges()
    for gi, (key, _, kind, side, size) in enumerate(GROUPS):
        t = {"sensory": 0, "central": 1, "descending": 2}[kind]
        region = {"VIS_L": 0, "VIS_R": 0, "OL_L": 1, "OL_R": 1}.get(
            key, 3 if kind == "descending" else 2)
        f = 0
        if kind == "sensory":
            f |= 0x01
        if kind == "descending":
            f |= 0x02
        for _ in range(size):
            group_idx.append(gi)
            type_idx.append(t)
            region_idx.append(region)
            sides.append({"left": 0, "right": 1, "mid": 2}[side])
            flags.append(f)
    assert len(group_idx) == 1024
    return group_idx, type_idx, region_idx, sides, flags


def build_package(seed=SEED):
    offsets, targets, weights = generate(seed)
    group_idx, type_idx, region_idx, sides, flags = neuron_tables()

    row_offsets = struct.pack(f"<{len(offsets)}I", *offsets)
    tgt_bin = struct.pack(f"<{len(targets)}I", *targets)
    wgt_bin = struct.pack(f"<{len(weights)}f", *[float(w) for w in weights])
    neurons_bin = b"".join(
        struct.pack("<HHHBB", g, t, r, s, f)
        for g, t, r, s, f in zip(group_idx, type_idx, region_idx, sides, flags))

    digest = hashlib.sha256()
    for blob in (row_offsets, tgt_bin, wgt_bin, neurons_bin):
        digest.update(blob)

    manifest = {
        "formatVersion": 1,
        "id": "sample-1024",
        "name": "Sample Brain",
        "mode": "sample",
        "description": (
            "Synthetic 1024-neuron sample connectome for development, CI and "
            "demos. Structured visual->central->descending pathways with "
            "ipsilateral-dominant wiring. Not biological data."),
        "attribution": "Generated by data/tools/generate_sample.py (PocketFly, MIT).",
        "neuronCount": 1024,
        "edgeCount": len(targets),
        "offsetType": "u32",
        "weightType": "f32",
        "weightScale": WEIGHT_SCALE,
        "groups": [
            {"id": i, "key": k, "name": n, "kind": kind, "side": side}
            for i, (k, n, kind, side, _size) in enumerate(GROUPS)
        ],
        "types": TYPES,
        "regions": REGIONS,
        "dynamics": {
            "decay": 0.82,
            "threshold": 1.0,
            "gain": 1.0,
            "noise": 0.02,
            "activationDecay": 0.85,
            "refractory": True,
        },
        "checksum": {"algorithm": "sha256", "value": digest.hexdigest()},
    }
    manifest_json = json.dumps(manifest, indent=2)

    files = {
        "manifest.json": manifest_json.encode("utf-8"),
        "row_offsets.bin": row_offsets,
        "targets.bin": tgt_bin,
        "weights.bin": wgt_bin,
        "neurons.bin": neurons_bin,
    }
    return manifest, files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=os.path.join(os.path.dirname(__file__), "..", ".."))
    args = parser.parse_args()
    repo = os.path.abspath(args.repo_root)
    sample_dir = os.path.join(repo, "data", "sample")

    manifest, files = build_package()

    exploded = os.path.join(sample_dir, "sample_1024")
    os.makedirs(exploded, exist_ok=True)
    for name, blob in files.items():
        with open(os.path.join(exploded, name), "wb") as f:
            f.write(blob)

    zip_path = os.path.join(sample_dir, "sample_1024.pflybrain")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, blob in files.items():
            z.writestr(name, blob)

    print(f"wrote {zip_path} ({os.path.getsize(zip_path)} bytes)")
    print(f"wrote {exploded}/")
    print(f"edges: {manifest['edgeCount']}, checksum: {manifest['checksum']['value']}")


if __name__ == "__main__":
    main()
