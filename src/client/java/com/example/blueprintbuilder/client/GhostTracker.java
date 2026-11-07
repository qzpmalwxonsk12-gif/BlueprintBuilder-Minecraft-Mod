package com.example.blueprintbuilder.client;

import java.util.Iterator;
import java.util.Map;

import com.example.blueprintbuilder.network.GhostConsumedPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Each client tick (throttled), checks which ghosts have been built and removes them, then tells the server
 * how many were built so it can stream in more. This is what makes a placed phantom disappear and keeps the
 * sliding window full for large blueprints.
 */
public final class GhostTracker {

    /** Only scan every few ticks; a built ghost vanishing within ~0.2s is plenty responsive. */
    private static final int SCAN_INTERVAL_TICKS = 3;

    private static int tickCounter = 0;

    private GhostTracker() {}

    public static void tick(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        Map<BlockPos, BlockState> ghosts = GhostStorage.get();
        if (ghosts.isEmpty()) {
            return;
        }

        int consumed = 0;
        Iterator<Map.Entry<BlockPos, BlockState>> it = ghosts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BlockState> entry = it.next();
            if (isSatisfied(world.getBlockState(entry.getKey()), entry.getValue())) {
                it.remove();
                consumed++;
            }
        }

        if (consumed > 0 && ClientPlayNetworking.canSend(GhostConsumedPayload.ID)) {
            ClientPlayNetworking.send(new GhostConsumedPayload(consumed));
        }
    }

    /** A ghost is "built" when the real block matches the target (by block type; air matches air). */
    public static boolean isSatisfied(BlockState current, BlockState target) {
        if (target.isAir()) {
            return current.isAir();
        }
        return current.getBlock() == target.getBlock();
    }
}
