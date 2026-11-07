package com.example.blueprintbuilder.client;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * Client-side holder for the scaffold ghosts currently shown. All access is on the client (render/tick)
 * thread, so a plain map is fine.
 *
 * <p>Ghosts are added when the server sends them and removed by {@link GhostTracker} once built, which is
 * what keeps the rendered set within the streaming window.</p>
 */
public final class GhostStorage {

    private static final Map<BlockPos, BlockState> GHOSTS = new LinkedHashMap<>();

    private GhostStorage() {}

    /** Replace the whole set (initial send / cancel). */
    public static void replace(Map<BlockPos, BlockState> blocks) {
        GHOSTS.clear();
        GHOSTS.putAll(blocks);
    }

    /** Merge in more ghosts (streamed in as the player builds). */
    public static void addAll(Map<BlockPos, BlockState> blocks) {
        GHOSTS.putAll(blocks);
    }

    /** The live map — callers iterate it directly (same thread, no concurrent access). */
    public static Map<BlockPos, BlockState> get() {
        return GHOSTS;
    }

    public static void clear() {
        GHOSTS.clear();
    }
}
