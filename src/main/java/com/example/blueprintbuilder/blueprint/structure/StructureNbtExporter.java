package com.example.blueprintbuilder.blueprint.structure;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.example.blueprintbuilder.BlueprintBuilderMod;
import com.example.blueprintbuilder.blueprint.BlueprintBlockStates;
import com.example.blueprintbuilder.blueprint.BlueprintCSVReader;
import com.example.blueprintbuilder.blueprint.BlueprintManager;
import com.example.blueprintbuilder.blueprint.BlueprintMetadata;
import com.example.blueprintbuilder.blueprint.version.StructureVersion;
import com.example.blueprintbuilder.blueprint.version.VersionBlockConverter;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

/**
 * Converts a Blueprint folder into a vanilla Minecraft <b>structure template</b> ({@code .nbt}) for a
 * selected {@link StructureVersion} — the exact format Structure Blocks and {@code /place template} use,
 * written with Minecraft's own NBT writer (gzipped compound with {@code size}/{@code palette}/
 * {@code blocks}/{@code entities}/{@code DataVersion}), so nothing custom needs decoding on the other end.
 *
 * <p>Reuses the existing blueprint pipeline: layers stream row-by-row through {@link BlueprintCSVReader},
 * states parse once per distinct cell string via {@link BlueprintBlockStates} (memoized — a million-cell
 * blueprint typically parses a few dozen strings), and {@link VersionBlockConverter} adapts each distinct
 * state to the target version before it enters the palette. The palette is deduplicated (one entry per
 * distinct final state), matching vanilla's own optimization.</p>
 *
 * <p>Semantics mirror the blueprint importer: an <b>empty cell</b> is written as nothing at all — the
 * template simply has no entry at that position, so placing it leaves existing blocks alone, exactly like
 * {@code /blueprint import}. Explicit {@code minecraft:air} cells are recorded and will clear blocks.
 * Invalid cells and missing layers are skipped and counted, never fatal.</p>
 *
 * <p>Output goes to the world's {@code generated/blueprintbuilder/structures/} folder — the same place
 * Structure Blocks save to — so the result is immediately loadable in this world and a plain file to copy
 * into another installation's {@code generated} folder or a datapack.</p>
 */
public final class StructureNbtExporter {

    /** What happened during an export, for user feedback. */
    public record Result(String structureId, Path file, long blocksWritten, long gapCells, long invalidCells,
                         long droppedBlocks, long substitutedBlocks, long outOfBoundsCells, int layersMissing,
                         int layersTruncated, int paletteSize, Map<String, String> substitutions) {}

    /** The output file already exists and {@code overwrite} was not requested. */
    public static final class OutputExistsException extends IOException {
        OutputExistsException(String message) {
            super(message);
        }
    }

    /**
     * Hard cap on written block entries. Each costs ~200 bytes in the NBT tree, so this (~1.6 GB worst
     * case) is what keeps a maliciously huge blueprint from running the server out of memory; real builds
     * are far below it, and a template this size is already beyond what vanilla can reasonably place.
     */
    private static final long MAX_BLOCKS = 8_388_608;

    /** Cap on memoized distinct cell strings — beyond this, cells still convert, just uncached. */
    private static final int MAX_CACHED_CELLS = 65_536;

    /** Per-distinct-cell conversion outcome; {@code paletteIdx < 0} means "write nothing". */
    private record CellOutcome(int paletteIdx, boolean substituted, boolean invalid) {
        static final CellOutcome INVALID = new CellOutcome(-1, false, true);
        static final CellOutcome DROPPED = new CellOutcome(-1, false, false);
    }

    private StructureNbtExporter() {}

    /**
     * Exports blueprint {@code name} as a structure template for {@code version}.
     *
     * @throws OutputExistsException when the output exists and {@code overwrite} is false
     */
    public static Result export(MinecraftServer server, String name, BlueprintMetadata meta,
                                StructureVersion version, boolean overwrite) throws IOException {
        String idPath = toIdPath(name);
        String structureId = BlueprintBuilderMod.MOD_ID + ":" + idPath;
        Path outDir = server.getSavePath(WorldSavePath.GENERATED)
                .resolve(BlueprintBuilderMod.MOD_ID).resolve("structures");
        Files.createDirectories(outDir);
        Path file = outDir.resolve(idPath + ".nbt");
        if (!overwrite && Files.exists(file)) {
            throw new OutputExistsException(idPath + ".nbt");
        }

        RegistryWrapper<Block> blockLookup = Registries.BLOCK.getReadOnlyWrapper();

        NbtList paletteNbt = new NbtList();
        Map<String, Integer> paletteIndex = new HashMap<>();
        NbtList blocksNbt = new NbtList();

        // One conversion per distinct cell string; every repeat is a single map lookup.
        Map<String, CellOutcome> cellCache = new HashMap<>();
        Map<String, String> substitutions = new LinkedHashMap<>(); // original id → substitute id

        long blocksWritten = 0, gapCells = 0, invalidCells = 0, droppedBlocks = 0, substitutedBlocks = 0,
                outOfBounds = 0;
        int layersMissing = 0, layersTruncated = 0;

        for (int y = 0; y < meta.height; y++) {
            Path layerFile = BlueprintManager.layerFile(name, y);
            if (!Files.isRegularFile(layerFile)) {
                layersMissing++;
                continue; // missing layer — gap slab, not fatal
            }
            try (BlueprintCSVReader reader = new BlueprintCSVReader(layerFile)) {
                String[] row;
                int z = 0;
                // Rows are read only up to the declared depth — a malformed multi-gigabyte layer file
                // cannot pin the server thread past its metadata-declared bounds.
                while (z < meta.depth && (row = reader.readRow()) != null) {
                    for (int x = 0; x < row.length; x++) {
                        String cell = row[x];
                        if (cell.isBlank()) {
                            gapCells++;
                            continue;
                        }
                        if (x >= meta.width) {
                            outOfBounds++; // malformed extra column — must not exceed the size tag
                            continue;
                        }
                        String key = cell.trim();
                        CellOutcome outcome = cellCache.get(key);
                        if (outcome == null) {
                            outcome = convertCell(key, blockLookup, version, paletteIndex, paletteNbt,
                                    substitutions);
                            if (cellCache.size() < MAX_CACHED_CELLS) {
                                cellCache.put(key, outcome); // bounded — garbage CSVs can't OOM the cache
                            }
                        }
                        if (outcome.invalid()) {
                            invalidCells++;
                            continue;
                        }
                        if (outcome.paletteIdx() < 0) {
                            droppedBlocks++; // no representation in the target version
                            continue;
                        }
                        if (outcome.substituted()) {
                            substitutedBlocks++;
                        }
                        if (blocksWritten >= MAX_BLOCKS) {
                            throw new IOException("blueprint exceeds " + MAX_BLOCKS
                                    + " blocks — too large for a structure template");
                        }
                        NbtCompound block = new NbtCompound();
                        NbtList pos = new NbtList();
                        pos.add(NbtInt.of(x));
                        pos.add(NbtInt.of(y));
                        pos.add(NbtInt.of(z));
                        block.put("pos", pos);
                        block.putInt("state", outcome.paletteIdx());
                        blocksNbt.add(block);
                        blocksWritten++;
                    }
                    z++;
                }
                if (z >= meta.depth && reader.readRow() != null) {
                    layersTruncated++; // rows beyond the declared depth exist; skipped without reading on
                }
            }
        }

        NbtCompound root = new NbtCompound();
        NbtList size = new NbtList();
        size.add(NbtInt.of(meta.width));
        size.add(NbtInt.of(meta.height));
        size.add(NbtInt.of(meta.depth));
        root.put("size", size);
        root.put("entities", new NbtList());
        root.put("blocks", blocksNbt);
        root.put("palette", paletteNbt);
        root.putInt("DataVersion", version.dataVersion());

        // Write to a temp file and move into place, so a mid-write failure (disk full, lock) can never
        // leave a truncated .nbt behind — with 'overwrite' the previous good file survives intact.
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        boolean moved = false;
        try {
            NbtIo.writeCompressed(root, tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp);
            }
        }

        return new Result(structureId, file, blocksWritten, gapCells, invalidCells, droppedBlocks,
                substitutedBlocks, outOfBounds, layersMissing, layersTruncated, paletteNbt.size(),
                substitutions);
    }

    /** Parses, version-converts and palettes one distinct cell string. */
    private static CellOutcome convertCell(String cell, RegistryWrapper<Block> blockLookup,
                                           StructureVersion version, Map<String, Integer> paletteIndex,
                                           NbtList paletteNbt, Map<String, String> substitutions) {
        BlockState state;
        try {
            state = BlueprintBlockStates.parse(blockLookup, cell);
        } catch (CommandSyntaxException e) {
            return CellOutcome.INVALID;
        }
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        VersionBlockConverter.Converted converted =
                VersionBlockConverter.convert(blockId, BlueprintBlockStates.propertyMap(state), version);
        if (converted == null) {
            substitutions.putIfAbsent(blockId, "(no equivalent — left as gap)");
            return CellOutcome.DROPPED;
        }
        if (converted.substitutedFrom() != null) {
            substitutions.putIfAbsent(converted.substitutedFrom(), converted.blockId());
        } else if (!version.isCurrent() && !blockId.startsWith("minecraft:")) {
            // Modded blocks pass through unchanged — surface that so the user can verify the target.
            substitutions.putIfAbsent(blockId, "(modded — exported as-is; verify it exists in "
                    + version.id() + ")");
        }
        int idx = paletteIndexFor(converted.blockId(), converted.properties(), paletteIndex, paletteNbt);
        return new CellOutcome(idx, converted.substitutedFrom() != null, false);
    }

    /** Deduplicated palette: one entry per distinct (name, properties) pair. */
    private static int paletteIndexFor(String blockId, Map<String, String> properties,
                                       Map<String, Integer> paletteIndex, NbtList paletteNbt) {
        String key = properties.isEmpty() ? blockId : blockId + properties; // sorted map → stable key
        return paletteIndex.computeIfAbsent(key, k -> {
            NbtCompound entry = new NbtCompound();
            entry.putString("Name", blockId);
            if (!properties.isEmpty()) {
                NbtCompound props = new NbtCompound();
                properties.forEach(props::putString);
                entry.put("Properties", props);
            }
            paletteNbt.add(entry);
            return paletteNbt.size() - 1;
        });
    }

    /**
     * Blueprint names allow things resource ids don't (uppercase, spaces). Lowercase and replace anything
     * outside {@code [a-z0-9._-]} with {@code _} so {@code blueprintbuilder:<path>} is valid in every
     * supported version (identifier rules have been stable since 1.13).
     */
    static String toIdPath(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-'
                    ? c : '_');
        }
        return sb.toString();
    }
}
