package com.example.blueprintbuilder.blueprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Rebuilds a Blueprint, reading one layer CSV at a time (never loading the whole blueprint) and placing each
 * cell with its stored {@link BlockState}. {@code origin} is the minimum corner: layer {@code y}, row
 * {@code z}, column {@code x} goes to {@code origin + (x, y, z)}.
 *
 * <p>Blocks are placed with {@code NOTIFY_LISTENERS | FORCE_STATE} so the exact exported state is kept (no
 * neighbour-driven recalculation of stairs/fences/etc.). Invalid/empty cells and missing layers are skipped
 * gracefully.</p>
 */
public final class BlueprintImporter {

    public record Result(long placed, long skipped, int layersRead, int layersMissing) {}

    private BlueprintImporter() {}

    public static Result importBlueprint(ServerWorld world, BlockPos origin, String name, BlueprintMetadata meta)
            throws IOException {
        RegistryWrapper<Block> blockLookup = Registries.BLOCK.getReadOnlyWrapper();
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        Map<String, BlockState> parseCache = new HashMap<>();

        long placed = 0;
        long skipped = 0;
        int layersRead = 0;
        int layersMissing = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int y = 0; y < meta.height; y++) {
            Path layerFile = BlueprintManager.layerFile(name, y);
            if (!Files.isRegularFile(layerFile)) {
                layersMissing++;
                continue; // missing layer — skip gracefully
            }
            try (BlueprintCSVReader reader = new BlueprintCSVReader(layerFile)) {
                String[] row;
                int z = 0;
                while ((row = reader.readRow()) != null) {
                    for (int x = 0; x < row.length; x++) {
                        String cell = row[x];
                        if (cell.isBlank()) {
                            skipped++;
                            continue;
                        }
                        BlockState state = parseCached(blockLookup, parseCache, cell.trim());
                        if (state == null) {
                            skipped++; // invalid block state — leave the existing block
                            continue;
                        }
                        pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                        if (!world.isOutOfHeightLimit(pos)) {
                            world.setBlockState(pos, state, flags);
                            placed++;
                        }
                    }
                    z++;
                }
                layersRead++;
            }
        }
        return new Result(placed, skipped, layersRead, layersMissing);
    }

    /**
     * Parses a block-state string through a per-import cache. Blueprints repeat the same handful of state
     * strings millions of times, and {@code BlockArgumentParser} is far too expensive to re-run per cell.
     * Returns {@code null} for an unparseable cell (cached too, so bad cells stay cheap).
     */
    private static BlockState parseCached(RegistryWrapper<Block> blockLookup, Map<String, BlockState> cache,
                                          String cell) {
        BlockState state = cache.get(cell);
        if (state == null && !cache.containsKey(cell)) {
            try {
                state = BlueprintBlockStates.parse(blockLookup, cell);
            } catch (CommandSyntaxException e) {
                state = null;
            }
            cache.put(cell, state);
        }
        return state;
    }

    /**
     * Reads the whole blueprint into an ordered placement map (layer 0 first, so a bot builds bottom-up),
     * for the scaffold / bot import modes. Air cells and invalid/missing data are skipped — those modes are
     * about guiding/placing the solid blocks. Unlike {@link #importBlueprint}, this loads the full blueprint
     * into memory (the scaffold/bot systems need every placement up front).
     */
    public static LinkedHashMap<BlockPos, BlockState> readPlacements(BlockPos origin, String name,
                                                                     BlueprintMetadata meta) throws IOException {
        RegistryWrapper<Block> blockLookup = Registries.BLOCK.getReadOnlyWrapper();
        Map<String, BlockState> parseCache = new HashMap<>();
        LinkedHashMap<BlockPos, BlockState> placements = new LinkedHashMap<>();

        for (int y = 0; y < meta.height; y++) {
            Path layerFile = BlueprintManager.layerFile(name, y);
            if (!Files.isRegularFile(layerFile)) {
                continue;
            }
            try (BlueprintCSVReader reader = new BlueprintCSVReader(layerFile)) {
                String[] row;
                int z = 0;
                while ((row = reader.readRow()) != null) {
                    for (int x = 0; x < row.length; x++) {
                        String cell = row[x];
                        if (cell.isBlank()) {
                            continue;
                        }
                        BlockState state = parseCached(blockLookup, parseCache, cell.trim());
                        if (state == null || state.isAir()) {
                            continue; // nothing to guide/place for air
                        }
                        placements.put(new BlockPos(origin.getX() + x, origin.getY() + y, origin.getZ() + z), state);
                    }
                    z++;
                }
            }
        }
        return placements;
    }
}

