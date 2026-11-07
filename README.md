# Blueprint Builder

A Fabric mod for **Minecraft 1.21.1** that builds structures in the world from `.csv` blueprint
files. Each cell of the CSV names a block; the mod places those blocks block-for-block, throttled to a
configurable number of blocks per tick.

It can also show the blueprint as a translucent **scaffold** ghost guide that vanishes as you build it
yourself, or have a **bot** mode puppet your player to build it automatically.

---

## What it does

1. On startup it creates a **`blueprints`** folder inside your Minecraft game directory
   (`.minecraft/blueprints` on the client, or `<server folder>/blueprints` on a dedicated server).
2. You drop `.csv` files in there (e.g. `castle.csv`).
3. In game you run `/build1 castle.csv <x> <y> <z>` (or `/build2 …`) and the mod places every block in
   the file — or add a `scaffold` / `bot` suffix to preview or auto-build it instead.

### How a CSV maps to the world

The CSV is a plain grid. The **top-left cell (A1)** is placed exactly at the `<x> <y> <z>` you give the
command. There are two orientations — pick one with the command you use:

| Command  | Orientation             | Columns A → B → C … | Rows 1 → 2 → 3 … | Fixed axis |
|----------|-------------------------|---------------------|------------------|------------|
| `/build1` | **Downward / vertical wall** (original) | East, `+X` | Down, `-Y` | `Z` |
| `/build2` | **Sideways / horizontal floor** (rotated) | East, `+X` | South, `+Z` | `Y` |

So `/build1` stands the structure up facing you (like the spreadsheet on screen), while `/build2` lays
the same grid flat on the ground like a top-down map.

### Per-cell rules

| Cell contents                                   | Result                                        |
|-------------------------------------------------|-----------------------------------------------|
| A valid block id, e.g. `minecraft:oak_log`      | That block (default state) is placed.         |
| A bare name, e.g. `oak_log`                      | Treated as `minecraft:oak_log`.               |
| `minecraft:air` / `air`                          | Air (clears whatever was there).              |
| Anything present but **not a real block**        | Air — "just have air in its place."           |
| **Empty cell**                                   | Skipped — the existing block is left alone.    |

Block ids only (default state). Block-state variants like `oak_log[axis=x]` are **not** parsed; the
default state is used.

### Build modes (optional suffix)

Add a word after the coordinates to change *how* the blueprint is built. Works on both `/build1` and
`/build2`.

| Suffix | What happens |
|--------|--------------|
| *(none)* | **Direct** — the blocks are placed for you, rate-limited (the original behaviour). |
| `scaffold` | **Ghost guide** — nothing is placed. A translucent "phantom" copy of the build is shown. Each phantom block disappears the moment you place the correct block there yourself, so it's a live build guide. Blueprints can be **any size**: up to 30 000 ghosts are shown at once and the rest stream in as you build (see below). *Requires this mod installed on your client* (it's drawn client-side; other players won't see your ghost). Clear it with `cancel`. |
| `bot [rate]` | **Auto-builder** — your player walks the structure, swinging and placing each block, so it looks like you're building it yourself. Optional `rate` is **blocks per second** (default 8, max 1000), e.g. `bot 20`. You can **look around freely** while it runs (only your body is moved, not your camera). Your movement keys are overridden until it finishes; stop it with `cancel`. |

> **Scaffold streaming:** 30 000 isn't a hard Minecraft limit — it's a render-performance window. The
> server holds the full blueprint and, each time your client reports blocks you've placed, sends that many
> fresh ghosts. So a million-block blueprint still works; you just always see ≤ 30 000 ghosts at a time,
> centred on what you've built so far. (Trade-off: a streamed ghost that you build and then break won't
> re-appear.)

> **Bot note:** this is an approximation, not full walking pathfinding — your body glides to a spot a
> couple of blocks from each target. It reads as walking block-to-block; it won't open doors or climb
> around obstacles, and at high rates blocks place faster than the avatar can walk (it strolls through the
> growing structure). True *free-flying* camera isn't possible for your own player without it being a
> separate entity — you keep free **look**, but your body is carried along.

---

## Commands

> Requires permission level 2 (an operator, or single-player with cheats enabled).

Both `/build1` and `/build2` take the same arguments; they differ only in orientation (see the table
above). `rate` and `cancel` are shared — they act on the one global queue, so you can set the rate from
either command.

| Command | Description |
|---------|-------------|
| `/build1 <file.csv> <x> <y> <z> [scaffold\|bot [rate]]` | Build a blueprint **downward** (vertical wall — the original). `<x> <y> <z>` is the A1 / top-left block. Supports `~` relative coordinates. Coordinates are **space-separated** (standard Minecraft syntax — no commas). The optional suffix picks a [build mode](#build-modes-optional-suffix); `bot` takes an optional blocks/second `rate`. |
| `/build2 <file.csv> <x> <y> <z> [scaffold\|bot [rate]]` | Same, but **sideways** (lays the grid flat on the ground; rows run +Z). |
| `/build1 rate <amount>` *(or `/build2 rate`)* | Set how many blocks are placed per tick. Range **1–10000** (10000 = the upper limit). |
| `/build1 rate` | Show the current rate and how many blocks are still queued. |
| `/build1 cancel` | Stop everything: clears the placement queue, stops your bot, and clears your scaffold ghost. |

The `<file.csv>` argument auto-completes from the files in your `blueprints` folder, and `.csv` is added
automatically if you omit it.

### Examples

```
/build1 castle.csv 100 70 -40           # vertical wall, A1 at (100, 70, -40)
/build2 castle.csv 100 70 -40           # same blueprint laid flat on the ground
/build1 castle ~ ~ ~                     # build at your feet (the .csv is optional)
/build1 castle.csv 100 70 -40 scaffold   # show a translucent ghost guide instead of building
/build1 castle.csv 100 70 -40 bot        # auto-build at the default 8 blocks/second
/build1 castle.csv 100 70 -40 bot 20     # auto-build faster, at 20 blocks/second
/build1 rate 2000                        # place 2000 blocks/tick (40,000/sec)
/build1 rate                             # check current rate + queue
/build1 cancel                           # stop building / clear ghost / stop the bot
```

A ready-to-use `examples/castle.csv` is included — copy it into your `blueprints` folder to try it.

> **Rate vs. lag:** the maximum is 10000 blocks/tick, but that places up to 200,000 blocks/second and
> will stall the server on big builds. A few hundred to a couple thousand per tick is usually smooth.

---

## Canvas Block System (pixel art)

A separate system for reproducing images with full RGB colour, independent of the blueprint builder above.

It adds a single custom block — the **Canvas Block** — where *every placed block stores and displays its
own colour*. Two canvas blocks side by side can be completely different colours. Colours are stored on the
block (in its block entity) and persist across world save/reload, chunk unload/reload and game restarts.
There is **no palette matching** to wool/concrete/terracotta — each block shows exactly its assigned RGB.

### The `canvas/` folder & CSV format

On first launch a **`canvas`** folder is created next to `blueprints` in your game directory. Put RGB CSV
files there (exported by any external image→CSV converter).

- Each **line** is one row of the image, and each **cell** is one pixel holding `R,G,B` (0–255 each,
  decimal — **no hex**, no colour conversion). Think of it like a spreadsheet where cell A1 = `234,343,34`.
- Because each cell's value contains commas, cells are **quoted** (exactly how a spreadsheet exports them):
- Example ([examples/canvas/example.csv](examples/canvas/example.csv) — a 3×3 swatch):

  ```
  "255,0,0","0,255,0","0,0,255"
  "255,255,255","128,128,128","0,0,0"
  "255,255,0","0,255,255","255,0,255"
  ```

Graceful handling: blank lines are skipped, values are clamped to 0–255, an empty cell is a gap (no block
placed, row stays aligned), a cell that isn't exactly three integers is skipped and reported, and
missing/empty/badly-formatted files report a clear error instead of crashing.

### Commands

Two orientations, mirroring `/build1` vs `/build2`:

| Command | Description |
|---------|-------------|
| `/buildCanvas <file.csv> [x y z]` | **Vertical wall** — the image stands up (columns +X, rows downward −Y). |
| `/buildCanvas2 <file.csv> [x y z]` | **Sideways floor** — the rotated version; the image lies flat (columns +X, rows south +Z). |

With no coordinates the top-left pixel is placed at your current position. The Canvas Block is also
available in the creative **Building Blocks** tab (placed by hand it's white until coloured by a build
command).

> **Colour accuracy:** the block is tinted to its exact RGB, but Minecraft still applies world lighting, so
> colours look truest in a brightly-lit area. (Full-bright/emissive rendering is a planned future option.)

---

## Canvas Cache (long-distance renderer)

Lets **very large** Canvas images (1000×1000, 2000×2000 and bigger) stay visible from far away — well past
chunk render distance — **without keeping chunks loaded** and without Distant Horizons. Exclusive to Canvas
Blocks; it doesn't touch normal blocks or the blueprint system.

**How it works.** When you `/buildCanvas`, the image is also stored as a **Canvas Project**: its RGB grid
plus a pyramid of **LOD levels** (LOD0 100% → LOD4 6.25%), saved to disk in the world save folder. A
dedicated renderer then draws each project as a *single textured quad* straight from the cached pixels —
so a 2000×2000 image costs about the same to draw as a tiny one (it scales with screen size, not world
size), is frustum-culled, and needs no loaded chunks.

- **Near** the image renders as real blocks; at the edge of render distance the cache **blends in**; far
  away **only the cache** draws it.
- **Edge-preserving downsampling** (not blurry averaging): uniform areas average, but edges keep real
  colours (majority / median), and thin features survive — so a huge image stays sharp and recognizable
  from far away instead of becoming a colour blob.
- The cache **persists** across save / reload / restart and **reloads automatically** when the world loads.
  It's only regenerated when a project actually changes.

### LOD control

| Command | Effect |
|---------|--------|
| `/canvasCache lod <0–4>` | **Force** a fixed LOD level — always used regardless of distance. |
| `/canvasCache lod auto` | Re-enable automatic distance-based LOD (with smooth cross-fade between levels). |

### Cache commands

| Command | Effect |
|---------|--------|
| `/canvasCache rebuild` | Rebuild **every** Canvas Project by re-reading the real blocks. |
| `/canvasCache rebuildCurrent` | Rebuild only the **nearest** project. |
| `/canvasCache clear` | Delete all cached render data (memory + disk). |
| `/canvasCache info` | Show cached project count, total cache size, LOD levels and total canvas blocks. |

> **Scope:** distant rendering currently works in **singleplayer / integrated server** (the client reads the
> same in-memory cache the server builds). On a dedicated server the cache is still generated and persisted
> server-side, but streaming it to remote clients for rendering is a future networking step. The cache data
> pipeline (projects, LODs, persistence, edge-preserving downsample) is unit-tested; the in-world rendering
> visuals need an in-game check.

---

## Blueprint Export & Import

A standalone way to **export any existing world build** into a reusable, shareable Blueprint and rebuild it
exactly later. Completely independent of the `/build*` and Canvas commands.

### Commands

| Command | Effect |
|---------|--------|
| `/blueprint pos1` | Store your current block position as corner 1 (per-player). |
| `/blueprint pos2` | Store your current block position as corner 2. |
| `/blueprint export <Name>` | Scan the cuboid between pos1/pos2 and write it to `blueprints/<Name>/`. |
| `/blueprint import <Name>` | Rebuild it with its min corner at **your** position. |
| `/blueprint import <Name> <x> <y> <z>` | Rebuild it with its min corner at the given coordinates. |
| `/blueprint import <Name> [x y z] scaffold` | Show it as a translucent **ghost guide** instead of placing it (same as the `/build … scaffold` mode — phantoms vanish as you build them). |
| `/blueprint import <Name> [x y z] bot [rate]` | **Puppet your player** to build it block by block (same as `/build … bot`). Optional `rate` = blocks/second (default 8). |
| `/blueprint export <Name> nbt [<version>] [overwrite]` | Convert an **existing** blueprint into a vanilla **structure template** (`.nbt`) for the chosen Minecraft version (tab-completes; defaults to 1.21.1). See below. |
| `/blueprint cancel` | Stop a blueprint bot and clear its scaffold ghost. |

### Structure (.nbt) export with version selection

`/blueprint export Castle nbt 1.21.1` converts `blueprints/Castle/` into a real vanilla structure template —
the exact format Structure Blocks and datapacks use (palette-deduplicated, gzipped, correct `DataVersion`
stamp; nothing custom). The file is written to the world's `generated/blueprintbuilder/structures/` folder,
so in the current world you can immediately run:

```
/place template blueprintbuilder:castle
```

(or load it with a Structure Block in LOAD mode). Supported target versions: **1.21.1, 1.21, 1.20.6,
1.20.4, 1.20.1, 1.19.4, 1.18.2**. For older targets, blocks that don't exist in that version are
automatically converted — pure renames are mapped back (e.g. `short_grass` → `grass` before 1.20.3) and
newer blocks get a similar older stand-in (e.g. `cherry_planks` → `crimson_planks` before 1.20), with
invalid properties stripped so the file always loads cleanly; everything substituted or dropped is listed
in the command output. Empty blueprint cells stay empty (placing the structure leaves existing blocks
alone, like `/blueprint import`), explicit `air` cells clear blocks, and waterlogging/orientation
properties are preserved. If the output file already exists, add `overwrite` to replace it.

### Format (self-contained folder per blueprint)

```
blueprints/Castle/
    metadata.json          name, width, height, depth, Minecraft version, export date, block count
    layers/
        layer000.csv        one CSV per Y level (ground up), X×Z grid
        layer001.csv
        ...
```

Every cell holds the **full block state** — e.g. `minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]` —
so rotated logs, stairs, slabs, trapdoors, fences and other directional blocks reconstruct **exactly**
(blocks are placed with forced state, so they don't re-calculate connections on import).

Export streams each layer straight to disk and import reads one layer at a time, so very large builds work
without loading the whole blueprint into memory. Missing folders/metadata/layers, corrupted CSVs, invalid
block states, empty selections and name conflicts are all handled with clear messages (never a crash). The
`metadata.json` is versioned/extensible for future additions (block entities, entities, compression,
single-file packages, thumbnails).

> Note: blueprint folders (this feature) and the builder's flat `blueprints/*.csv` files (the `/build1`
> system) coexist in the same `blueprints/` folder without interfering — each lists only its own kind.

---

## Building the mod

**Prerequisites**

- **JDK 21** (Minecraft 1.21.1 requires Java 21 — Java 17 will *not* compile it).
  Get Temurin 21 from <https://adoptium.net/>.
- Internet access (Gradle downloads Minecraft, Fabric, and mappings on first build).

This project has no committed Gradle wrapper jar, so use one of these:

**Option A — IntelliJ IDEA (easiest)**
1. `File ▸ Open…` and select this folder.
2. Let it import the Gradle project, then make sure the Gradle JVM is set to **JDK 21**
   (`Settings ▸ Build Tools ▸ Gradle ▸ Gradle JVM`).
3. Run the Gradle task **`build`**. The jar lands in `build/libs/`.

**Option B — command line**
1. Install Gradle 8.10+ (<https://gradle.org/install/>) and JDK 21.
2. (Optional, one time) generate the wrapper: `gradle wrapper`
3. Build: `gradle build`   (or `./gradlew build` once the wrapper exists)

The output jar is `build/libs/blueprintbuilder-3.4.1.jar` (ignore the `-sources` jar).

---

## Installing the mod

1. Install **Fabric Loader** for Minecraft 1.21.1 (<https://fabricmc.net/use/installer/>).
2. Put these in your `.minecraft/mods` folder:
   - `blueprintbuilder-3.4.1.jar` (this mod)
   - **Fabric API** for 1.21.1 (<https://modrinth.com/mod/fabric-api>)
3. Launch the **fabric-loader 1.21.1** profile. The `blueprints` folder appears in `.minecraft` on first
   launch.

---

## Version notes

The dependency versions in `gradle.properties` (`yarn_mappings`, `loader_version`, `fabric_version`)
are pinned to known-good 1.21.1 values. If Gradle reports a version isn't found, grab the current
1.21.1 numbers from <https://fabricmc.net/develop/> and update that file.
