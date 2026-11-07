package com.example.blueprintbuilder.blueprint;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Scans a world cuboid and writes a Blueprint to disk one Y layer at a time, streaming each row straight to
 * its CSV — the whole blueprint is never held in memory (only a single row's worth of block-state strings).
 */
public final class BlueprintExporter {

    public record Result(int width, int height, int depth, long blockCount) {}

    private BlueprintExporter() {}

    public static Result export(ServerWorld world, BlockPos a, BlockPos b, String name) throws IOException {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());

        int width = maxX - minX + 1;   // X
        int height = maxY - minY + 1;  // Y (one layer each)
        int depth = maxZ - minZ + 1;   // Z

        BlueprintManager.ensureLayersDir(name);

        long blockCount = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        String[] row = new String[width];
        // Regions repeat the same few block states thousands of times; serialize each distinct state
        // once (BlockState uses identity equality, so this is a cheap identity-keyed lookup).
        Map<BlockState, String> serializeCache = new HashMap<>();

        for (int y = 0; y < height; y++) {
            Path layerFile = BlueprintManager.layerFile(name, y);
            try (BlueprintCSVWriter writer = new BlueprintCSVWriter(layerFile)) {
                for (int z = 0; z < depth; z++) {
                    for (int x = 0; x < width; x++) {
                        BlockState state = world.getBlockState(pos.set(minX + x, minY + y, minZ + z));
                        row[x] = serializeCache.computeIfAbsent(state, BlueprintBlockStates::serialize);
                        if (!state.isAir()) {
                            blockCount++;
                        }
                    }
                    writer.writeRow(row);
                }
            }
        }

        BlueprintMetadata meta = new BlueprintMetadata();
        meta.name = name;
        meta.width = width;
        meta.height = height;
        meta.depth = depth;
        meta.minecraftVersion = SharedConstants.getGameVersion().getName();
        meta.exportDate = OffsetDateTime.now().toString();
        meta.blockCount = blockCount;
        meta.write(BlueprintManager.metadataFile(name));

        return new Result(width, height, depth, blockCount);
    }
}
