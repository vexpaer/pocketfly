#!/usr/bin/env python3
"""Converts a connectome export (CSV tables) into a PocketFly .pflybrain package.

PocketFly does not parse research-native formats on device. Instead, this
offline tool turns flat CSV exports of a connectome into the documented
package format (docs/connectome-format.md).

Expected inputs (column names are configurable via --config):

  edges CSV    one row per connection: source, target, weight (synapse count)
  neurons CSV  one row per neuron: id, name, type, side, region, neurotransmitter

The config JSON classifies neurons into PocketFly groups:

{
  "edge_columns":  {"source": "bodyId_pre", "target": "bodyId_post", "weight": "weight"},
  "neuron_columns": {"id": "bodyId", "type": "type", "side": "side", "region": " somaSide"},
  "groups": [
    {"key": "VIS_L", "name": "Visual Left", "kind": "sensory",
     "match": {"type": ["optical lobe ..."]}, "side": "left"},
    {"key": "DN_L", "name": "Descending Left", "kind": "descending",
     "match": {"type": ["DN"]}, "side": "left"}
  ],
  "default_group": {"key": "CX", "name": "Central Brain", "kind": "central"},
  "attribution": "Converted from <dataset>, <license>. NOT redistributed data.",
  "weight_scale": 50.0
}

`match` maps are column->list of substrings; a neuron joins the first group
whose predicate it satisfies (side filter applied if present). Neurons
matching no group land in `default_group`.

Usage:
  python3 convert_malecns.py --edges edges.csv --neurons neurons.csv \
      --config config.json --output my_brain.pflybrain

The output package's mode is taken from config ("mode", default "full").
Remembers: the produced package inherits the SOURCE DATASET license. Do not
redistribute without checking the original terms.
"""

import argparse
import csv
import hashlib
import json
import struct
import sys
import zipfile


def load_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def column(row, col):
    v = row.get(col, "")
    return (v or "").strip()


def neuron_matches(row, cols, spec):
    m = spec.get("match", {})
    for col_key, needles in m.items():
        col = cols.get(col_key, col_key)
        value = column(row, col).lower()
        if not any(n.lower() in value for n in needles):
            return False
    want_side = spec.get("side")
    if want_side:
        side_col = cols.get("side", "side")
        if column(row, side_col).lower() != want_side.lower():
            return False
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--edges", required=True)
    ap.add_argument("--neurons", required=True)
    ap.add_argument("--config", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--max-neurons", type=int, default=0, help="keep only the first N neurons (0 = all)")
    args = ap.parse_args()

    cfg = json.load(open(args.config, encoding="utf-8"))
    ecols = cfg["edge_columns"]
    ncols = cfg["neuron_columns"]

    neurons_raw = load_csv(args.neurons)
    edges_raw = load_csv(args.edges)
    print(f"loaded {len(neurons_raw)} neurons, {len(edges_raw)} connections")

    # Assign neurons to groups in file order; build id -> index.
    groups_cfg = cfg["groups"]
    default_group = cfg["default_group"]
    group_defs = [
        {"id": i, "key": g["key"], "name": g["name"], "kind": g["kind"],
         "side": g.get("side", "mid")}
        for i, g in enumerate([*groups_cfg, default_group])
    ]
    key_to_id = {g["key"]: g["id"] for g in group_defs}

    id_to_index = {}
    group_idx, type_idx, region_idx, sides, flags = [], [], [], [], []
    type_names, region_names = [], []
    type_to_idx, region_to_idx = {}, {}

    kept = 0
    for row in neurons_raw:
        if args.max_neurons and kept >= args.max_neurons:
            break
        nid = column(row, ncols["id"])
        if nid in id_to_index:
            continue
        group_key = default_group["key"]
        for g in groups_cfg:
            if neuron_matches(row, ncols, g):
                group_key = g["key"]
                break
        tname = column(row, ncols.get("type", "type")) or "unspecified"
        rname = column(row, ncols.get("region", "region")) or "unknown"
        side = column(row, ncols.get("side", "side")).lower()
        side_code = {"left": 0, "right": 1, "l": 0, "r": 1}.get(side, 2)
        t = type_to_idx.setdefault(tname, len(type_names))
        if t == len(type_names):
            type_names.append(tname)
        r = region_to_idx.setdefault(rname, len(region_names))
        if r == len(region_names):
            region_names.append(rname)
        gdef = next(g for g in group_defs if g["key"] == group_key)
        flag = 0
        if gdef["kind"] == "sensory":
            flag |= 0x01
        if gdef["kind"] == "descending":
            flag |= 0x02

        id_to_index[nid] = kept
        group_idx.append(key_to_id[group_key])
        type_idx.append(t)
        region_idx.append(r)
        sides.append(side_code)
        flags.append(flag)
        kept += 1
    n = kept
    print(f"kept {n} neurons in {len(group_defs)} groups")

    # Edges between kept neurons, deduplicated per (src, tgt) with summed weights.
    by_src = {}
    dropped = 0
    for row in edges_raw:
        src = column(row, ecols["source"])
        tgt = column(row, ecols["target"])
        w = int(float(column(row, ecols["weight"]) or 1))
        if src not in id_to_index or tgt not in id_to_index:
            dropped += 1
            continue
        by_src.setdefault(id_to_index[src], {})[id_to_index[tgt]] = (
            by_src.setdefault(id_to_index[src], {}).get(id_to_index[tgt], 0) + w
        )
    if dropped:
        print(f"dropped {dropped} edges touching pruned neurons")

    offsets = [0]
    targets, weights = [], []
    for src in range(n):
        edges = by_src.get(src, {})
        for tgt in sorted(edges):
            targets.append(tgt)
            weights.append(max(1, min(65535, edges[tgt])))
        offsets.append(len(targets))
    e = len(targets)
    print(f"packed {e} edges")

    row_offsets = struct.pack(f"<{len(offsets)}I", *offsets)
    tgt_bin = struct.pack(f"<{e}I", *targets)
    wgt_bin = struct.pack(f"<{e}f", *[float(w) for w in weights])
    neurons_bin = b"".join(
        struct.pack("<HHHBB", g, t, r, s, f)
        for g, t, r, s, f in zip(group_idx, type_idx, region_idx, sides, flags)
    )

    digest = hashlib.sha256()
    for blob in (row_offsets, tgt_bin, wgt_bin, neurons_bin):
        digest.update(blob)

    manifest = {
        "formatVersion": 1,
        "id": cfg.get("id", "converted-brain"),
        "name": cfg.get("name", "Converted Brain"),
        "mode": cfg.get("mode", "full"),
        "description": cfg.get("description", "Converted from an external connectome dataset."),
        "attribution": cfg.get("attribution", ""),
        "neuronCount": n,
        "edgeCount": e,
        "offsetType": "u32",
        "weightType": "f32",
        "weightScale": float(cfg.get("weight_scale", 1.0)),
        "groups": group_defs,
        "types": type_names,
        "regions": region_names,
        "dynamics": cfg.get("dynamics", {
            "decay": 0.82, "threshold": 1.0, "gain": 1.0, "noise": 0.02,
            "activationDecay": 0.85, "refractory": True,
        }),
        "checksum": {"algorithm": "sha256", "value": digest.hexdigest()},
    }

    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("row_offsets.bin", row_offsets)
        z.writestr("targets.bin", tgt_bin)
        z.writestr("weights.bin", wgt_bin)
        z.writestr("neurons.bin", neurons_bin)
    print(f"wrote {args.output}")


if __name__ == "__main__":
    sys.exit(main())
