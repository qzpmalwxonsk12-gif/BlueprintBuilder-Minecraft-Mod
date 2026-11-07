# Blueprint Builder — Handover

Status of the project as of **v3.3.0**. This is the document to read first if you're picking the project
up. For the user-facing feature docs, see [README.md](README.md).

The mod has **independent systems**: the **Blueprint Builder** (`/build1` `/build2`), the **Canvas Block
System** (`/buildCanvas` + a per-block-coloured block for RGB pixel art), and the **Canvas Cache**
long-distance renderer (`/canvasCache` — LOD pyramids + a quad renderer so huge images render from far away
without loaded chunks).

---

## 1. What this is

A **Fabric mod for Minecraft 1.21.1** (Yarn mappings, Java 21) that:
- reads `.csv` blueprints from a `blueprints/` folder and builds them in-world via `/build1` and `/build2`
  (each CSV cell is a block id; empty = skip, unrecognized = air; A1 maps to the given coordinate); and
- reads RGB `.csv` images from a `canvas/` folder and builds them with **Canvas Blocks** via `/buildCanvas`
  — a single block whose colour is stored per-placed-block, for high-fidelity pixel art.

| | |
|---|---|
| Loader / version | Fabric Loader, Minecraft **1.21.1** |
| Mappings | Yarn `1.21.1+build.3` |
| Java | **21** (required by MC 1.21.1) |
| Build tool | Gradle 8.10 + Fabric Loom 1.7 |
| Source layout | **Split**: `src/main/java` (common) + `src/client/java` (client-only) |
| Current version | `4.0.0` (in [gradle.properties](gradle.properties)) |

---

## 2. Current status

**Done & compile-verified** (compiles cleanly against real MC 1.21.1 classes; jar builds and bundles all
classes including the client source set):

- `/build1` (downward / vertical wall) and `/build2` (sideways / horizontal floor).
- Three build modes: **direct** (rate-limited placement), **scaffold** (client ghost guide), **bot**
  (puppets the player).
- Scaffold **streaming** — unlimited blueprint size, ≤30k ghosts rendered at once.
- Bot **rate** (`bot [rate]`, blocks/sec), **free-look** camera, **gliding walk** movement.
- `rate`, `cancel` subcommands; CSV file tab-completion; path-escape guard.
- **Canvas Block System**: custom block + block entity (RGB stored in NBT), `BlockColorProvider` tinting,
  `/buildCanvas` (+`/buildCanvas2`) importing RGB CSVs from `canvas/`, creative-tab entry, loot table.
- **Canvas Cache** (`canvas/cache/` + client `canvas/cache/`): Canvas Projects with on-disk gzipped LOD
  pyramids, edge-preserving downsample, persistence + reload on world start, `/canvasCache rebuild |
  rebuildCurrent | clear | info | lod`, and a single-quad-per-project long-distance renderer (frustum cull,
  distance LOD + manual override, near→far blend). The **pure-Java pipeline is unit-tested** (edge
  preservation, transparency survival, disk round-trip, 2000² in 42ms). Compiles clean (no warnings).

**NOT yet verified — needs a running game client** (could not launch MC from the dev environment):

- The scaffold **ghost rendering visuals** (does the translucent model actually draw and at the right
  place?). This is the highest-risk unverified item — see §6.
- The **bot's on-screen feel** (walk animation smoothness, free-look not jittering, rubber-banding).
- **Canvas:** that the colour provider actually tints each block to its RGB, that a colour change
  re-renders the chunk (the `updateListeners` trick in `CanvasBlockEntity`), and that colours survive
  save/reload + restart (NBT logic is correct but unverified in a running game).
- **Canvas Cache:** the in-world quad rendering (does the textured quad appear at the right place/size,
  LOD switching, near→far blend). The data pipeline (projects, LODs, edge-preserving downsample, disk
  round-trip) **is** unit-tested and passing; only the GPU rendering is unverified.

There are **no in-game smoke tests** run. The pure-Java cache/CSV logic *is* covered by standalone tests
(run during development; not committed as a gradle test task).

**v3.4.0 (Blueprint Export & Import) — implemented + compiles, jar NOT built yet.** The feature
(`blueprint/` package + `BlueprintCommands`) was compile-validated with a standalone `javac` against the
cached Minecraft/Yarn/Gson/Brigadier/Fabric-Loader jars (12 classes, no warnings on the code). The full
Gradle `build` could **not** be run because the dev sandbox's loom Minecraft cache got invalidated by an
interrupted build and re-validating it requires Mojang's CDN (`piston-data` / `libraries.minecraft.net`),
which the sandbox was intermittently blocking. **To produce `blueprintbuilder-3.4.0.jar`, just run
`gradle build` on a machine with normal Mojang access** (the code is ready; nothing else is needed).

---

## 3. How to build

> ⚠️ The repo has **no committed `gradle-wrapper.jar`**, and the machine's default `java` is **17**, which
> cannot compile 1.21.1. You must use JDK 21.

Known-good local toolchain on this machine:

- Gradle 8.10 launcher: `~/.gradle/wrapper/dists/gradle-8.10-bin/<hash>/gradle-8.10/bin/gradle`
- JDK 21: `C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot`

### Easiest: an IDE

Open the folder in IntelliJ IDEA (or VS Code with the Gradle/Java extensions), set the Gradle JVM to
**JDK 21**, and run the `build` task. Output: `build/libs/blueprintbuilder-<version>.jar`.

### Command line

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.1.12-hotspot"
gradle -p . build --console=plain --no-daemon   # or ./gradlew once you generate the wrapper
```

Validate compilation of both source sets with: `gradle compileJava compileClientJava`.

### ⚠️ The IDE lock gotcha (important)

The **VS Code Red Hat Java extension runs its own Loom** and locks the shared Loom mappings cache
(`~/.gradle/caches/fabric-loom/.../mappings.jar`). Editing `build.gradle` triggers a re-import that holds
this lock while the project is open, and a concurrent **CLI** `gradle build` then fails configuration with:

```
Failed to setup Minecraft ... Failed to setup mappings: net.fabricmc:yarn:1.21.1+build.3
```

It does **not** wait it out. Two ways around it:

1. Build **from inside the IDE** (it owns the lock — no conflict). Preferred for normal dev.
2. For CLI builds while VS Code is open, use an **isolated Gradle home** so it doesn't share the locked
   cache (first run re-downloads + re-decompiles MC, ~7 min; reuse the dir for fast rebuilds):
   ```bash
   export GRADLE_USER_HOME="/path/to/some/scratch/gradle-home"
   ```
   (During development this was pointed at the session scratchpad.)

---

## 4. Architecture / file map

### Common — `src/main/java/com/example/blueprintbuilder/`

| File | Responsibility |
|------|----------------|
| [BlueprintBuilderMod.java](src/main/java/com/example/blueprintbuilder/BlueprintBuilderMod.java) | Entry point. Creates `blueprints/` + `canvas/`, registers the canvas system, commands, the server tick (drains both queues + ticks bots), packet types, the C2S receiver, and disconnect cleanup. Holds `MOD_ID`, `blueprintsDir`, `canvasDir`. |
| [BlueprintParser.java](src/main/java/com/example/blueprintbuilder/BlueprintParser.java) | CSV → `List<List<BlockState>>`. Quote-aware splitter, BOM strip, `null` cell = skip, air for unknown. |
| [BuildQueue.java](src/main/java/com/example/blueprintbuilder/BuildQueue.java) | Global rate-limited placement queue for **direct** mode. `onEndTick()` places up to `rate` blocks/tick (1–10000). |
| [BotSession.java](src/main/java/com/example/blueprintbuilder/BotSession.java) | One player's **bot** build: glides body toward frontier (free-look via `PositionFlag.ROT`), places at blocks/sec. |
| [BotManager.java](src/main/java/com/example/blueprintbuilder/BotManager.java) | Map of `UUID → BotSession`; ticks them; messages player on completion. |
| [ScaffoldManager.java](src/main/java/com/example/blueprintbuilder/ScaffoldManager.java) | Server-side **scaffold streaming**. Holds the pending ghost queue per player; sends the first `WINDOW` (30k), streams more on `onConsumed`. |
| [command/BuildCommand.java](src/main/java/com/example/blueprintbuilder/command/BuildCommand.java) | The `/build1` & `/build2` Brigadier trees; parses grid into placements; dispatches to direct/scaffold/bot. `Orientation` & `Mode` enums live here. |
| [network/GhostBlueprintPayload.java](src/main/java/com/example/blueprintbuilder/network/GhostBlueprintPayload.java) | **S2C** packet: `boolean replace` + `Map<BlockPos,BlockState>`. States sent via `Block.getRawIdFromState`. |
| [network/GhostConsumedPayload.java](src/main/java/com/example/blueprintbuilder/network/GhostConsumedPayload.java) | **C2S** packet: `int count` of ghosts the client just built (drives streaming). |

### Canvas system — `src/main/java/com/example/blueprintbuilder/canvas/` (+ command)

| File | Responsibility |
|------|----------------|
| [CanvasBlock.java](src/main/java/com/example/blueprintbuilder/canvas/CanvasBlock.java) | The block (`BlockWithEntity`): creates the block entity, renders as `MODEL` so it can be tinted. No colour logic itself. |
| [CanvasBlockEntity.java](src/main/java/com/example/blueprintbuilder/canvas/CanvasBlockEntity.java) | **Per-block colour storage**: red/green/blue in NBT (persistence), `toUpdatePacket`/`toInitialChunkDataNbt` (client sync), `setColor()` + `readNbt` trigger a client re-render. |
| [CanvasRegistry.java](src/main/java/com/example/blueprintbuilder/canvas/CanvasRegistry.java) | Registers block, item, block-entity type; adds it to the Building Blocks tab. Holds `CANVAS_BLOCK`, `CANVAS_BLOCK_ITEM`, `CANVAS_BLOCK_ENTITY`. |
| [CsvColorReader.java](src/main/java/com/example/blueprintbuilder/canvas/CsvColorReader.java) | **Parsing only**: CSV → grid of `Rgb` (groups of 3 per cell; null = invalid/gap; clamps 0–255). |
| [CanvasBuilder.java](src/main/java/com/example/blueprintbuilder/canvas/CanvasBuilder.java) | Orchestration: read via `CsvColorReader`, map cells to positions for an `Orientation` (DOWNWARD wall / SIDEWAYS floor), enqueue into the queue. |
| [CanvasBuildQueue.java](src/main/java/com/example/blueprintbuilder/canvas/CanvasBuildQueue.java) | Rate-limited (1024/tick) placement: sets the canvas block then assigns its block-entity colour. Independent of `BuildQueue`. |
| [command/BuildCanvasCommand.java](src/main/java/com/example/blueprintbuilder/command/BuildCanvasCommand.java) | `/buildCanvas` (wall) and `/buildCanvas2` (sideways floor, rotated) — same args `<file> [x y z]`; resolves files under `canvas/`, defaults position to the player. |

### Canvas Cache — `src/main/java/com/example/blueprintbuilder/canvas/cache/` (+ command)

Common core is **pure Java (no Minecraft types)** so it's unit-testable.

| File | Responsibility |
|------|----------------|
| [CanvasPlane.java](src/main/java/com/example/blueprintbuilder/canvas/cache/CanvasPlane.java) | `WALL_XY` / `FLOOR_XZ` — how image (col,row) maps to world. |
| [CanvasLod.java](src/main/java/com/example/blueprintbuilder/canvas/cache/CanvasLod.java) | One LOD: `level, width, height, int[] argb`. |
| [CanvasProject.java](src/main/java/com/example/blueprintbuilder/canvas/cache/CanvasProject.java) | One image: id, dimension, plane, origin, size, content hash, LOD list. Deterministic id + FNV hash. |
| [CanvasLodGenerator.java](src/main/java/com/example/blueprintbuilder/canvas/cache/CanvasLodGenerator.java) | **Edge-preserving 2×2 downsample**: average only near-uniform blocks; on edges pick majority / median-luminance (existing colours, no blur); any-opaque survives. |
| [CanvasCache.java](src/main/java/com/example/blueprintbuilder/canvas/cache/CanvasCache.java) | Disk I/O only: one gzipped `.ccp` file per project (binary). |
| [CanvasRenderSettings.java](src/main/java/com/example/blueprintbuilder/canvas/cache/CanvasRenderSettings.java) | Shared LOD override (`-1`=auto), set by command, read by renderer. |
| [CanvasProjectScanner.java](src/main/java/com/example/blueprintbuilder/canvas/cache/CanvasProjectScanner.java) | (MC) Re-reads a project's region from world blocks (source of truth) for rebuilds. |
| [CanvasCacheManager.java](src/main/java/com/example/blueprintbuilder/canvas/cache/CanvasCacheManager.java) | (MC) Owns the in-memory map + disk; load on server start; `createOrUpdate` / `rebuildAll` / `rebuildNearest` / `clear` / info. The client reads its map in SP. |
| [command/CanvasCacheCommand.java](src/main/java/com/example/blueprintbuilder/command/CanvasCacheCommand.java) | `/canvasCache rebuild \| rebuildCurrent \| clear \| info \| lod <n\|auto>`. |

### Blueprint Export & Import — `src/main/java/com/example/blueprintbuilder/blueprint/` (+ command)

Standalone; only touches the existing mod via 3 additive lines in `BlueprintBuilderMod` (command register +
disconnect cleanup). Does **not** use Fabric API (only Minecraft + Gson + Brigadier + Fabric-Loader).

| File | Responsibility |
|------|----------------|
| [BlueprintSelectionManager.java](src/main/java/com/example/blueprintbuilder/blueprint/BlueprintSelectionManager.java) | Per-player pos1/pos2. |
| [BlueprintMetadata.java](src/main/java/com/example/blueprintbuilder/blueprint/BlueprintMetadata.java) | `metadata.json` (Gson; versioned/extensible). |
| [BlueprintManager.java](src/main/java/com/example/blueprintbuilder/blueprint/BlueprintManager.java) | Paths, naming/validation, listing. Each blueprint = `blueprints/<Name>/`. |
| [BlueprintCSVWriter.java](src/main/java/com/example/blueprintbuilder/blueprint/BlueprintCSVWriter.java) / [BlueprintCSVReader.java](src/main/java/com/example/blueprintbuilder/blueprint/BlueprintCSVReader.java) | Streamed, quote-aware layer CSV I/O (row by row — never the whole blueprint). |
| [BlueprintBlockStates.java](src/main/java/com/example/blueprintbuilder/blueprint/BlueprintBlockStates.java) | Full block-state ↔ string (id + all properties) via `BlockArgumentParser`. |
| [BlueprintExporter.java](src/main/java/com/example/blueprintbuilder/blueprint/BlueprintExporter.java) | Cuboid scan → one CSV per Y layer, streamed; writes metadata. |
| [BlueprintImporter.java](src/main/java/com/example/blueprintbuilder/blueprint/BlueprintImporter.java) | Read one layer at a time → place with `NOTIFY_LISTENERS\|FORCE_STATE` (exact states). |
| [command/BlueprintCommands.java](src/main/java/com/example/blueprintbuilder/command/BlueprintCommands.java) | `/blueprint pos1 \| pos2 \| export <name> [nbt [<version>] [overwrite]] \| import <name> [x y z]`. |
| [blueprint/version/StructureVersion.java](src/main/java/com/example/blueprintbuilder/blueprint/version/StructureVersion.java) | **Version selection system**: supported target MC versions (id, structure DataVersion, datapack folder name, /place availability). Newest first; reusable by future export formats. |
| [blueprint/version/VersionBlockConverter.java](src/main/java/com/example/blueprintbuilder/blueprint/version/VersionBlockConverter.java) | Per-version block/state conversion: rename tables (`short_grass`→`grass`), fallback substitutions (cherry→crimson, mangrove→dark oak, 1.21 copper/tuff set, …) applied as a cascading rule chain; properties filtered against the substitute's real state definition. |
| [blueprint/structure/StructureNbtExporter.java](src/main/java/com/example/blueprintbuilder/blueprint/structure/StructureNbtExporter.java) | Blueprint folder → vanilla structure template `.nbt` (size/palette/blocks/entities/DataVersion, gzipped via `NbtIo`), streamed through `BlueprintCSVReader` with per-distinct-cell memoization; writes to `<world>/generated/blueprintbuilder/structures/`. |

### Client — `src/client/java/com/example/blueprintbuilder/client/`

| File | Responsibility |
|------|----------------|
| [BlueprintBuilderClient.java](src/client/java/com/example/blueprintbuilder/client/BlueprintBuilderClient.java) | Client entry point. Receives S2C ghosts (replace/merge), registers the renderer (`WorldRenderEvents.AFTER_ENTITIES`) and the tick tracker; clears ghosts on disconnect. |
| [GhostStorage.java](src/client/java/com/example/blueprintbuilder/client/GhostStorage.java) | Client-thread map of currently shown ghosts. `replace` / `addAll` / `clear`. |
| [GhostTracker.java](src/client/java/com/example/blueprintbuilder/client/GhostTracker.java) | Every 3 ticks, removes built ghosts and sends `GhostConsumedPayload`. `isSatisfied()` (block-type match) is the shared "is this built?" check. |
| [GhostRenderer.java](src/client/java/com/example/blueprintbuilder/client/GhostRenderer.java) | Draws each ghost as a translucent block model via `renderBlockAsEntity` + the wrapper below. **Most likely place to need tweaks (§6).** |
| [GhostVertexConsumerProvider.java](src/client/java/com/example/blueprintbuilder/client/GhostVertexConsumerProvider.java) | Funnels all render layers into one translucent layer and clamps vertex alpha → makes the model a ~45% "ghost". |
| [canvas/CanvasColorProviders.java](src/client/java/com/example/blueprintbuilder/client/canvas/CanvasColorProviders.java) | Registers the block/item `ColorProvider`s: block tint = the block entity's RGB (white texture × colour = exact colour); item = white. |
| [canvas/cache/CanvasTextureCache.java](src/client/java/com/example/blueprintbuilder/client/canvas/cache/CanvasTextureCache.java) | Uploads a project LOD's pixels to one `NativeImageBackedTexture` (NEAREST), keyed by project+LOD; re-uploads on content-hash change; frees on disconnect. |
| [canvas/cache/CanvasRenderer.java](src/client/java/com/example/blueprintbuilder/client/canvas/cache/CanvasRenderer.java) | Draws each visible project as **one textured quad** (`AFTER_TRANSLUCENT`): frustum cull, distance LOD (+ cross-fade) or manual override, near→far blend, full-bright, double-sided. Reads `CanvasCacheManager` (SP). |

### Resources / config

Canvas assets: [blockstates/canvas_block.json](src/main/resources/assets/blueprintbuilder/blockstates/canvas_block.json),
[models/block/canvas_block.json](src/main/resources/assets/blueprintbuilder/models/block/canvas_block.json)
(white texture, `tintindex:0`, `shade:false`), [models/item/canvas_block.json](src/main/resources/assets/blueprintbuilder/models/item/canvas_block.json),
a generated white [textures/block/canvas_block.png](src/main/resources/assets/blueprintbuilder/textures/block/canvas_block.png),
[lang/en_us.json](src/main/resources/assets/blueprintbuilder/lang/en_us.json), and the block
[loot_table/blocks/canvas_block.json](src/main/resources/data/blueprintbuilder/loot_table/blocks/canvas_block.json)
(note: 1.21 uses the **singular** `loot_table/` data folder).


- [src/main/resources/fabric.mod.json](src/main/resources/fabric.mod.json) — `main` + `client` entrypoints, deps.
- [build.gradle](build.gradle) — note `loom { splitEnvironmentSourceSets() }` + the `mods { }` block.
- [gradle.properties](gradle.properties) — versions live here.
- [examples/castle.csv](examples/castle.csv) — sample blueprint (copy into `blueprints/` to try).

---

## 5. How the moving parts fit (data flow)

**Direct:** command → parse → `BuildQueue.enqueue(...)` → server tick places ≤rate/tick.

**Scaffold (streaming):** command → `ScaffoldManager.start()` sends first 30k as S2C `replace=true` →
client stores & renders → `GhostTracker` (client tick) detects placed ghosts, removes them locally, sends
C2S `GhostConsumedPayload(n)` → server `onConsumed` streams `n` more as S2C `replace=false` (merge). Repeat
until the server's pending queue empties.

**Bot:** command → `BotManager.start(uuid, BotSession)` → each server tick the session glides the player's
body (relative-rotation teleport = free look) and places at the configured blocks/sec, swinging the arm.

`cancel` clears all three (queue, bot session, scaffold session + empty-ghost S2C). Disconnect cleans up
bot + scaffold sessions.

**Canvas:** `/buildCanvas` → `CanvasBuilder.build()` reads CSV via `CsvColorReader`, maps each cell to a
position, enqueues into `CanvasBuildQueue` → server tick places the block and calls `CanvasBlockEntity.setColor()`
→ `setColor` persists (markDirty) and syncs to clients (updateListeners + `toUpdatePacket`) → on the client
`readNbt` re-renders the chunk so the [client colour provider](src/client/java/com/example/blueprintbuilder/client/canvas/CanvasColorProviders.java)
re-reads the new RGB and tints the white model to it.

**Canvas Cache:** `CanvasBuilder.build()` also assembles the image's ARGB grid and calls
`CanvasCacheManager.createOrUpdate(...)` → `CanvasLodGenerator` builds the LOD pyramid → stored in the
in-memory map + written to `<save>/canvascache/*.ccp`. On the client, `CanvasRenderer` (AFTER_TRANSLUCENT)
reads that same map (SP), and per visible project: frustum-cull → pick LOD (auto distance / manual override)
→ `CanvasTextureCache` gives a GPU texture → draw one quad at the project's plane with a near→far blend.
`/canvasCache rebuild*` re-reads world blocks via `CanvasProjectScanner`; only changed projects regenerate.

---

## 6. Known limitations & risks (read before changing rendering/bot)

1. **Ghost rendering is unverified.** Logic & API compile, but it has not been seen in-game. If ghosts
   don't appear, likely culprits in [GhostRenderer.java](src/client/java/com/example/blueprintbuilder/client/GhostRenderer.java):
   - The event: currently `WorldRenderEvents.AFTER_ENTITIES` + `immediate.draw(layer)`. If nothing shows,
     try `AFTER_TRANSLUCENT`, or verify the consumers buffer is being flushed.
   - `context.matrixStack()` is null-guarded (returns early). If it's null at runtime in this event,
     ghosts silently won't draw — switch event or use `context.positionMatrix()`.
   - There is a **harmless deprecation warning** in this file (a Yarn-deprecated-but-standard render
     helper) — not a bug.
2. **Scaffold is client-side only.** Players without the mod on their client see nothing; the command
   warns via `ServerPlayNetworking.canSend(...)`.
3. **Bot ≠ free-fly camera.** On a single player entity, the viewpoint is tied to the body, so the bot
   *carries your body* (you keep free look, not free position). True decoupling would need a separate
   fake-player entity (big, fragile — deliberately not attempted).
4. **Streamed ghost, once built then broken, won't reappear** (the server has moved past it). Only matters
   for blueprints > 30k. For ≤30k everything is sent up front.
5. **`GhostTracker` scans up to 30k `getBlockState` calls every 3 ticks** — fine for typical sizes; could
   be a perf concern at the window cap. Throttle/spatial-index if needed.
6. **Block states are default only.** `oak_log[axis=x]` etc. are not parsed (would also break the naive CSV
   comma split unless quoted).
7. Bot places top-row-first for walls (force-placed, so floating is fine) — not bottom-up.
8. **Canvas rendering is unverified in-game** (same class of risk as the ghost renderer). The colour
   provider + the `updateListeners` re-render trick in `CanvasBlockEntity` are the standard Fabric pattern,
   but if a colour change doesn't show until a reload, that trick is where to look.
9. **Canvas colours are modulated by world light** (non-emissive) — exact only in bright areas. Emissive /
   full-bright is a planned future toggle, not implemented.
10. **Canvas CSV uses the spreadsheet-cell model**: each cell holds one pixel `R,G,B` (e.g. A1 =
    `234,343,34`). Because a cell value contains commas, cells are **quote-aware split**, so the converter
    must quote them: `"234,343,34","12,200,5"`. An unquoted row (raw `234,343,34`) would be read as three
    one-number cells (all invalid). This lives entirely in [CsvColorReader.java](src/main/java/com/example/blueprintbuilder/canvas/CsvColorReader.java)
    (`splitCells` + `parseCell`).
11. **Canvas placement sends one block-entity update packet per block** (rate-limited to 1024/tick). Fine
    for localhost/singleplayer; very large images on a dedicated server generate a lot of packets.
12. Canvas blocks register **without an explicit `registryKey` in `Settings`** — fine on 1.21.1, but 1.21.2+
    enforces it, so that's the first thing to add on a version bump.
13. **Canvas Cache distant rendering is singleplayer/integrated-server only.** The client renderer reads the
    common `CanvasCacheManager` map, which only the integrated server fills. Dedicated-server clients have no
    cache → nothing draws (the server still builds/persists it). The extension point is a networking sync
    (the cache data pipeline is environment-agnostic). The *rendering visuals* are unverified in-game (same
    risk class as the ghost/scaffold renderer); the **data pipeline is unit-tested**.
14. `/canvasCache rebuild` re-reads every project's region via `getBlockState`, which **loads those chunks**
    on the server — heavy for very large projects (it's an explicit, user-invoked command).
15. Canvas Projects are keyed by `(plane, origin, dimension)`, so re-importing at the same spot updates the
    same project. Manually-placed canvas blocks (not via `/buildCanvas`) have no project until one is built
    over them; the scanner only re-reads existing projects' regions.

---

## 7. Suggested next steps / TODOs

- **Smoke test in-game**: run the `runClient` Gradle task, then (a) try the blueprint modes with
  [examples/castle.csv](examples/castle.csv) in `run/blueprints/`, and (b) `/buildCanvas example` with
  [examples/canvas/example.csv](examples/canvas/example.csv) in `run/canvas/` — confirm the 3×3 swatch shows
  correct colours, then save+reload to confirm they persist. Then build a large canvas, fly far away, and
  check the **Canvas Cache** quad renders (try `/canvasCache info`, `/canvasCache lod 4`, `/canvasCache lod
  auto`). This is the #1 priority before trusting v3.3.
- Commit a Gradle wrapper (`gradle wrapper`) so `./gradlew` works out of the box.
- **Canvas Cache → dedicated servers**: add an S2C sync (stream project metadata + the small LODs) so remote
  clients can render. The seam is `CanvasCacheManager` (server owns data) → a payload → a client store the
  renderer reads instead of the in-memory map. The data classes are already serializable.
- Consider block-state parsing (via `BlockArgumentParser`) for rotations/facings.
- Optional: a config for ghost alpha / scaffold window size / bot walk speed / cache LOD distances.
- If true free-camera bot is wanted, investigate a Carpet-style fake `ServerPlayerEntity`.
- Canvas future work (the systems were built to allow these without rearchitecting): emissive/full-bright,
  transparency (alpha — pixels are already ARGB), animated/video/GIF canvases (frame sets → new LODs per
  frame), shader integration, in-game colour brushes / editing, image scaling. Colour-only changes touch
  `CanvasBlockEntity`+`CanvasColorProviders`; cache-render changes touch `CanvasProject`/`CanvasRenderer`.

---

## 8. Version history

| Version | Added |
|---------|-------|
| 1.0.0 | `blueprints/` folder, CSV parser, `/build`, rate limit (≤10k/tick). |
| 2.0.0 | Split `/build` into `/build1` (downward wall) and `/build2` (sideways floor). |
| 3.0.0 | `scaffold` (client ghost guide) + `bot` (player puppet) modes; client source set + networking. |
| 3.1.0 | `bot [rate]` (blocks/sec) + free-look + gliding walk; scaffold streaming (unlimited size). |
| 3.2.0 | **Canvas Block System**: per-block-coloured Canvas Block (block entity + colour provider), `canvas/` folder, `/buildCanvas` (wall) + `/buildCanvas2` (sideways/rotated) RGB-CSV import; spreadsheet-cell CSV format (`"R,G,B"` per cell). Independent of the blueprint builder. |
| 3.3.0 | **Canvas Cache & long-distance renderer**: Canvas Projects, on-disk gzipped LOD pyramids (edge-preserving downsample), persistence + reload, one-quad-per-project renderer (frustum cull, distance LOD + manual override, near→far blend), `/canvasCache rebuild\|rebuildCurrent\|clear\|info\|lod`. Pure-Java pipeline unit-tested. |
| 3.3.1–3.3.2 | Canvas Cache fixes: `/canvasCache scan` (capture already-placed art), `/canvasCache debug`, renderer rewritten to the unlit `position_tex_color` quad path (no depth write), explicit texture upload. |
| 3.4.0 | **Blueprint Export & Import** (`blueprint/` package, `/blueprint pos1\|pos2\|export\|import`): cuboid scan → per-Y-layer CSVs with full block states + `metadata.json`, streamed export/import, exact reconstruction (forced state). Independent of `/build*`. Compile-validated; jar pending a Mojang-reachable build (see §2). |
| 4.1.0 | **Blueprint → Structure (.nbt) export with version selection** (`/blueprint export <name> nbt [<version>] [overwrite]`): converts a blueprint folder into a vanilla structure template (palette-optimized, gzipped, correct per-version `DataVersion`) written to `<world>/generated/blueprintbuilder/structures/` for immediate `/place template blueprintbuilder:<name>` use. New `blueprint/version/` package = the version selection system (7 supported targets, 1.18.2–1.21.1) + cascading block/state downgrade rules (renames, fallbacks, property filtering). Existing commands unchanged. |
| 4.0.0 | **Stability/performance pass — no feature or behaviour changes.** Fixes: pending queues + bot/scaffold sessions now cleared on server stop (stale-`ServerWorld` leak in SP); corrupt `.ccp` cache files can no longer crash world load (validated fields, all exceptions caught); GPU textures freed after `/canvasCache clear`; scaffold consumed-count clamped; `metadata.json` dimensions validated. Perf: block-state parse/serialize caches for blueprint import/export; Canvas Cache renderer sets GL state lazily + computes corners once; ghost renderer frustum-culls and reuses its vertex-consumer wrapper; canvas `.ccp` I/O converts pixels through a small reusable buffer; CSV parsers stream line-by-line instead of `readAllLines`. |
