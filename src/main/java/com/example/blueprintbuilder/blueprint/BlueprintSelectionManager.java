package com.example.blueprintbuilder.blueprint;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.math.BlockPos;

/**
 * Per-player cuboid selection (pos1 / pos2) for the Blueprint system. In-memory and independent of the
 * builder's selections.
 */
public final class BlueprintSelectionManager {

    private static final class Selection {
        BlockPos pos1;
        BlockPos pos2;
    }

    private static final Map<UUID, Selection> SELECTIONS = new ConcurrentHashMap<>();

    private BlueprintSelectionManager() {}

    public static void setPos1(UUID player, BlockPos pos) {
        SELECTIONS.computeIfAbsent(player, k -> new Selection()).pos1 = pos;
    }

    public static void setPos2(UUID player, BlockPos pos) {
        SELECTIONS.computeIfAbsent(player, k -> new Selection()).pos2 = pos;
    }

    public static BlockPos pos1(UUID player) {
        Selection s = SELECTIONS.get(player);
        return s == null ? null : s.pos1;
    }

    public static BlockPos pos2(UUID player) {
        Selection s = SELECTIONS.get(player);
        return s == null ? null : s.pos2;
    }

    public static void clear(UUID player) {
        SELECTIONS.remove(player);
    }
}
