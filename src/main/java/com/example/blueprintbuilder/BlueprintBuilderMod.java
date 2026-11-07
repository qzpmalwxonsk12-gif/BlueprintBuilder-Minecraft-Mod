package com.example.blueprintbuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.blueprintbuilder.blueprint.BlueprintSelectionManager;
import com.example.blueprintbuilder.canvas.CanvasBuildQueue;
import com.example.blueprintbuilder.canvas.CanvasRegistry;
import com.example.blueprintbuilder.canvas.cache.CanvasCacheManager;
import com.example.blueprintbuilder.command.BlueprintCommands;
import com.example.blueprintbuilder.command.BuildCanvasCommand;
import com.example.blueprintbuilder.command.BuildCommand;
import com.example.blueprintbuilder.command.CanvasCacheCommand;
import com.example.blueprintbuilder.network.GhostBlueprintPayload;
import com.example.blueprintbuilder.network.GhostConsumedPayload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.server.network.ServerPlayerEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlueprintBuilderMod implements ModInitializer {
    public static final String MOD_ID = "blueprintbuilder";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** The "blueprints" folder inside the Minecraft game directory (.minecraft on a client, the
     *  server folder on a dedicated server). Populated in {@link #onInitialize()}. */
    public static Path blueprintsDir;

    /** The "canvas" folder (for /buildCanvas RGB CSVs), alongside "blueprints". */
    public static Path canvasDir;

    @Override
    public void onInitialize() {
        // 1. Create the "blueprints" and "canvas" folders in the Minecraft game directory.
        Path gameDir = FabricLoader.getInstance().getGameDir();
        blueprintsDir = createFolder(gameDir.resolve("blueprints"), "blueprints");
        canvasDir = createFolder(gameDir.resolve("canvas"), "canvas");

        // 1b. Register the Canvas Block system (block, item, block entity, creative tab entry).
        CanvasRegistry.register();

        // 2. Register the scaffold packet types (both sides run this): S2C ghosts, C2S "I built some".
        PayloadTypeRegistry.playS2C().register(GhostBlueprintPayload.ID, GhostBlueprintPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GhostConsumedPayload.ID, GhostConsumedPayload.CODEC);

        // When a client reports it has built some ghosts, stream it more (on the server thread).
        ServerPlayNetworking.registerGlobalReceiver(GhostConsumedPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            int count = payload.count();
            player.server.execute(() -> ScaffoldManager.onConsumed(player, count));
        });

        // 3. Register the /build1, /build2, /buildCanvas and /canvasCache command trees (all independent).
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            BuildCommand.register(dispatcher);
            BuildCanvasCommand.register(dispatcher);
            CanvasCacheCommand.register(dispatcher);
            BlueprintCommands.register(dispatcher);
        });

        // 3b. Canvas Cache lifecycle: load the cached projects from the world save on start, drop on stop.
        ServerLifecycleEvents.SERVER_STARTED.register(CanvasCacheManager::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(CanvasCacheManager::onServerStopping);

        // 3c. Drop all pending work when the server stops. The queues/sessions are static, so in
        // singleplayer they would otherwise survive into the next world, holding the old ServerWorld
        // alive and resuming placement into it.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            BuildQueue.clear();
            CanvasBuildQueue.clear();
            BotManager.clearAll();
            ScaffoldManager.clearAll();
        });

        // 4. Each server tick: drain the build + canvas queues (rate limits) and advance any bot builds.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            BuildQueue.onEndTick();
            CanvasBuildQueue.onEndTick();
            BotManager.tick(server);
        });

        // 5. Clean up a player's bot/scaffold state when they disconnect.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            java.util.UUID id = handler.player.getUuid();
            BotManager.cancel(id);
            ScaffoldManager.cancel(id);
            BlueprintSelectionManager.clear(id);
        });

        LOGGER.info("[Blueprint Builder] Initialized.");
    }

    private static Path createFolder(Path dir, String label) {
        try {
            Files.createDirectories(dir);
            LOGGER.info("[Blueprint Builder] {} folder ready at {}", label, dir.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("[Blueprint Builder] Could not create {} folder at {}", label, dir, e);
        }
        return dir;
    }
}
