package com.example.blueprintbuilder;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Tracks active {@link BotSession}s (one per player) and ticks them each server tick.
 */
public final class BotManager {

    private static final Map<UUID, BotSession> SESSIONS = new ConcurrentHashMap<>();

    private BotManager() {}

    /** Starts (or replaces) the bot session for a player. */
    public static void start(UUID playerId, BotSession session) {
        SESSIONS.put(playerId, session);
    }

    /** Cancels a player's bot session if any; returns true if one was running. */
    public static boolean cancel(UUID playerId) {
        return SESSIONS.remove(playerId) != null;
    }

    /** Drops every session (server stopping — sessions must not outlive their world). */
    public static void clearAll() {
        SESSIONS.clear();
    }

    public static boolean isRunning(UUID playerId) {
        return SESSIONS.containsKey(playerId);
    }

    /** Called every server tick to advance all running sessions. */
    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, BotSession>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            BotSession session = it.next().getValue();
            if (session.tick() == BotSession.Status.DONE) {
                it.remove();
                if (!session.player().isRemoved()) {
                    session.player().sendMessage(Text.literal(
                            "Bot finished: placed " + session.placed() + " of " + session.total() + " blocks.")
                            .formatted(Formatting.GREEN), false);
                }
            }
        }
    }
}
