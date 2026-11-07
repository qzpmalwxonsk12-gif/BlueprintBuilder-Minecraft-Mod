package com.example.blueprintbuilder;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.blueprintbuilder.network.GhostBlueprintPayload;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Server-side streaming of scaffold ghosts so blueprints can be arbitrarily large while the client only
 * ever renders up to {@link #WINDOW} blocks at once.
 *
 * <p>The first {@code WINDOW} ghosts are sent immediately. The remainder are kept here as a pending queue;
 * whenever the client reports it has built some ghosts (and therefore removed them), we send that many more
 * from the queue.</p>
 */
public final class ScaffoldManager {

    /** Maximum number of ghosts rendered on the client at one time (a performance bound, not a hard cap). */
    public static final int WINDOW = 30_000;

    private static final Map<UUID, Deque<Map.Entry<BlockPos, BlockState>>> PENDING = new ConcurrentHashMap<>();

    private ScaffoldManager() {}

    /** Begins (or replaces) a scaffold for the player and sends the first window of ghosts. */
    public static void start(ServerPlayerEntity player, LinkedHashMap<BlockPos, BlockState> all) {
        Deque<Map.Entry<BlockPos, BlockState>> pending = new ArrayDeque<>(all.entrySet());
        Map<BlockPos, BlockState> firstBatch = pollBatch(pending, WINDOW);

        if (pending.isEmpty()) {
            PENDING.remove(player.getUuid());
        } else {
            PENDING.put(player.getUuid(), pending);
        }
        ServerPlayNetworking.send(player, new GhostBlueprintPayload(true, firstBatch));
    }

    /** The client built {@code count} ghosts; stream that many more (if any remain). */
    public static void onConsumed(ServerPlayerEntity player, int count) {
        if (count <= 0) {
            return;
        }
        Deque<Map.Entry<BlockPos, BlockState>> pending = PENDING.get(player.getUuid());
        if (pending == null) {
            return;
        }
        // A well-behaved client never reports more than WINDOW at once; clamp so a bogus count can't
        // drain the whole queue into a single oversized packet.
        Map<BlockPos, BlockState> more = pollBatch(pending, Math.min(count, WINDOW));
        if (!more.isEmpty()) {
            ServerPlayNetworking.send(player, new GhostBlueprintPayload(false, more));
        }
        if (pending.isEmpty()) {
            PENDING.remove(player.getUuid()); // nothing left to stream; client finishes the visible batch
        }
    }

    /** Stops streaming for a player. Returns true if a scaffold was active. */
    public static boolean cancel(UUID playerId) {
        return PENDING.remove(playerId) != null;
    }

    /** Drops every pending scaffold (server stopping). */
    public static void clearAll() {
        PENDING.clear();
    }

    private static Map<BlockPos, BlockState> pollBatch(Deque<Map.Entry<BlockPos, BlockState>> pending, int max) {
        LinkedHashMap<BlockPos, BlockState> batch = new LinkedHashMap<>();
        for (int i = 0; i < max && !pending.isEmpty(); i++) {
            Map.Entry<BlockPos, BlockState> entry = pending.poll();
            batch.put(entry.getKey(), entry.getValue());
        }
        return batch;
    }
}
