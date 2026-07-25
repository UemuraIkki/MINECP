# MINECP server-side Fabric mod

`minecp` is the deterministic Minecraft-side executor for Java Edition 1.20.1.
It owns exactly three concerns: one directly constructed fake player, live-state
observation/event emission, and single-flight execution of the 12 schema skills.
It contains no planning, memory-coordinate, milestone-priority, or LLM logic.

## Requirements and build

- Java 17 or newer (the bytecode target is Java 17)
- Minecraft Java Edition 1.20.1
- Fabric Loader 0.15.x
- Fabric API 0.92.2+1.20.1

From this directory:

```sh
./gradlew build
```

On Windows PowerShell:

```powershell
.\gradlew.bat build
```

The remapped mod jar is written to `build/libs/`.

## Development server

```sh
./gradlew runServer
```

On first launch, accept Mojang's EULA in `run/eula.txt`, then run the command
again. The mod creates and registers one survival-mode fake player named
`MINECP_Agent` after server startup.

## WebSocket configuration

The first run creates `run/config/minecp.json` for a development server (or
`config/minecp.json` beside a production server):

```json
{
  "websocket_host": "127.0.0.1",
  "websocket_port": 8765,
  "fake_player_name": "MINECP_Agent"
}
```

The defaults connect to `ws://127.0.0.1:8765`. The host must be nonblank, the
port must be 1–65535, and the fake-player name must be a valid 1–16 character
Minecraft name. Disconnects abort the active skill with
`INTERRUPTED_BY_DISCONNECT`, queue that result, stop movement, and reconnect
with exponential backoff (1 to 60 seconds). A fresh observation is sent after
each reconnection.

## Automatone status

Automatone **0.11.0** is the release selected for Minecraft 1.20.1; its upstream
release notes explicitly state “Updated to 1.20.1”. The required Maven is:

```groovy
maven { url = "https://maven.ladysnake.org/releases" }
```

Dependency resolution is verified working: `build.gradle` also registers the
Quilt Maven (`quilt-loader` / QSL / `quilted-fabric-api` transitives) and the
JamiesWhiteShirt Maven (`reach-entity-attributes`), and
`./gradlew -Pwith_automatone=true build` succeeds on a networked machine.

`with_automatone` defaults to `false`, in which case the mod uses `IPathfinder`
with the straight-line-plus-jump fallback. The fallback walks directly toward
the goal and jumps over a one-block obstruction; it cannot route around walls,
cliffs, or hazards.

To run with Automatone:

1. Build with `-Pwith_automatone=true` (adds it to the dev runtime classpath
   for `runServer`).
2. For a production server, put Automatone 0.11.0 and its required
   dependencies in the server `mods/` directory.

The reflective `AutomatonePathfinder` adapter detects its API at runtime and
uses `GoalBlock`; if the API or entity component is unavailable it safely falls
back. TODO: once the dependency is reliably resolvable in CI, replace reflection
with direct typed API calls and make Automatone the default runtime dependency.

## Skill coverage

All 12 schema-registered skills have deterministic executors:

- `goto`: pathfinder movement to an explicit `BlockPos`
- `mine`: incremental radius-24 target search, approach, tool selection, break,
  deterministic drop collection (mining reach exceeds vanilla item-pickup
  range, so drops are inserted into inventory directly rather than relying on
  the fake player walking over them), repeat, and `data.mined_count`
- `craft`: deterministic recipe selection, ingredient reservation/consumption,
  2×2 versus crafting-table check, capacity check, and output insertion
- `smelt`: recipe-based raw-material allocation, empty-furnace discovery or
  furnace crafting/placement, fuel planning, vanilla processing, and collection
- `place`: validated relative placement with inventory consumption
- `attack`: entity-id or nearest-hostile resolution, approach, strongest carried
  weapon selection, cooldown attacks, kill count, and critical-health disengagement
- `eat`: deterministic first-edible inventory scan and vanilla food consumption
- `equip`: armor-slot equip or main-hand selection/swap
- `use_portal`: radius-16 portal lookup, deterministic End-frame activation,
  portal entry/dwell, and dimension-change confirmation
- `build_portal`: fixed ten-obsidian frame or deterministic water-and-lava
  bucket casting, followed by flint-and-steel ignition
- `throw_ender_eye`: real eye flight, sampled unit direction, and dropped-eye
  survival detection
- `fight_dragon`: crystals first (visible ranged shots, otherwise melee
  approach), fixed airborne dodge waypoints, then perched-dragon melee

Every accepted command terminates in a schema-shaped `skill_result`. A newer
valid command aborts the old command with `INTERRUPTED_BY_NEW_COMMAND`. No
position change for 60 continuous seconds aborts it with `TIMEOUT_STUCK`.

Health is checked every tick regardless of the active skill. `attack` and
`fight_dragon` already flee or abort at critical health themselves, aimed at
the specific threat they are tracking. Every other skill has no health
awareness of its own, and the bridge's own reaction requires an LLM
round-trip far slower than a hostile mob's attack cadence — so at or below 6
HP, `SkillManager` unconditionally cancels whatever skill is running and
installs a deterministic panic flee (run from the nearest hostile, or
straight back if none is observed) that reports `FLED_FROM_COMBAT` on its
own once clear or after a fixed timeout.

## Observation performance and schema rules

The nearby radius is exactly 16 blocks. Instead of a full scan per message, the
scanner samples at most 1,024 positions per tick and maintains a live cache,
revalidating it continuously. Hostiles and villagers use bounded entity
queries. Observations contain current live state only—never remembered base,
portal, stronghold, or death coordinates.

Message objects are constructed from explicit allowlists matching the monorepo
schemas. No top-level ad-hoc fields are emitted; skill-specific values are used
only under the schema-permitted `skill_result.data`.
