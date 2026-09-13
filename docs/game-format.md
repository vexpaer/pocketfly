# Game format (`.pocketfly.json`)

Every game/experiment environment in PocketFly — built-in and user-created —
is a `GameSpec` serialized as JSON, `schemaVersion: 1`. The formal schema is
[data/schemas/pocketfly-game.schema.json](../data/schemas/pocketfly-game.schema.json);
this page is the human-readable version.

## Top level

```json
{
  "schemaVersion": 1,
  "id": "builtin-light-chase",
  "name": "Light Chase",
  "description": "...",
  "author": "PocketFly",
  "world": { "width": 100, "height": 60, "objects": [ ... ] },
  "simulation": { ... },
  "sensory": [ ... ],
  "actions": [ ... ],
  "rules": [ ... ],
  "rewards": { ... }
}
```

`id` is set by the app on save; imported files get a fresh id.

## World and objects

`world.width/height` are world units; the renderer scales to the canvas.
Objects are axis-aligned rectangles:

| type | behavior |
| --- | --- |
| `WALL` | solid; collision penalty; feeds `obstacle.*` channels |
| `FOOD` | pickup: `+rewards.food`, removed on contact, feeds `smell.*` |
| `GOAL` | episode ends with `+rewards.goal`, feeds `smell.*` |
| `LIGHT` | wandering/parametric light; feeds `visual.*`; catch = `+rewards.food` |
| `DANGER` | `rewards.danger` penalty on contact; feeds `obstacle.*` |
| `OBSTACLE` | solid; in side-scroller mode, passing scores `+rewards.goal` |
| `SPAWN` | where the fly starts (exactly one required) |

Common `params` (free-form float map):

- `vx`, `vy` — velocity (movers). With `wrap: 1`, leaving the left edge
  wraps to the right edge (scrolling pipes); `respawn: 1` additionally
  randomizes y on wrap.
- `range` — sensor range for lights/food/goals/dangers.

## Simulation

| field | meaning |
| --- | --- |
| `timestepsPerFrame` | neural steps per rendered frame (1..64) |
| `speed` | global time multiplier for physics |
| `maxSpeed`, `turnRate` | top-down body limits |
| `gravity`, `flapImpulse` | side-scroller physics |
| `mode` | `"topdown"` (steering + forward) or `"sidescroller"` (gravity + flap) |
| `dynamics` | optional per-game overrides of brain dynamics defaults |

## Sensory mappings

Each binding routes an encoder **channel** into a neural **input group**:

```json
{"channel": "visual.left", "groupKey": "VIS_L", "gain": 1.0, "offset": 0.0}
```

Channels produced by the built-in encoders (see below): `visual.left`,
`visual.right`, `smell.left`, `smell.right`, `altitude`, `obstacle.left`,
`obstacle.right`. `groupKey` must exist in the loaded brain's manifest.

Encoders are bilateral direction sensors: bearing relative to the fly's
heading, linearized and saturated at ±90° so rear targets still steer.
`altitude` is `1 - y/height` (1 at the ceiling). The `SensoryEncoder`
interface is deliberately channel-based so a compound-eye encoder can be
added later without touching game specs.

## Action mappings

Decoders convert output-group activity into body commands:

| decoder | formula | action |
| --- | --- | --- |
| `difference` | `steer = gain · (right − left)`, deadzone | `steer` |
| `continuous` | `forward = clamp01(gain · activity + offset)` | `forward` |
| `threshold` | fires when `activity ≥ threshold` | `flap` |

## Rules

Simple stimulus-conditioned overrides, evaluated after decoders:

```json
{"source": "DN_R", "source2": "DN_L", "operator": "diff",
 "value": 0.15, "action": "turn_right", "strength": 1.0}
```

Operators: `>`, `<`, `diff` (signed `source − source2`), `ratio`
(`source / source2`), `avg`. Actions: `turn_left`, `turn_right`, `forward`,
`flap`, `stop`. A rule that fires **overrides** the decoders for that tick.

## Rewards

`food`, `goal`, `collision`, `danger`, `timePenalty` (per second). In v0.1
rewards are recorded/scored only — no learning uses them yet.

## Validation & sharing

The app validates specs on save/import (unique ids, spawn present,
`timestepsPerFrame` in range, movers may start off-world). Files round-trip
through `GameJson` with `ignoreUnknownKeys = true`, so newer files degrade
gracefully and `schemaVersion` mismatches are rejected loudly.
