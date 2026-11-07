package com.example.blueprintbuilder.client;

import com.example.blueprintbuilder.client.canvas.CanvasColorProviders;
import com.example.blueprintbuilder.client.canvas.cache.CanvasRenderer;
import com.example.blueprintbuilder.client.canvas.cache.CanvasTextureCache;
import com.example.blueprintbuilder.network.GhostBlueprintPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Client entrypoint: registers the Canvas Block colour providers, receives scaffold ghost packets, renders
 * the ghosts, and reports built ghosts back so the server can stream more.
 */
public final class BlueprintBuilderClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Canvas Block: tint each placed block by its stored RGB (independent of the scaffold system).
        CanvasColorProviders.register();

        // Apply ghost updates from the server (on the client thread). replace = reset, else merge.
        ClientPlayNetworking.registerGlobalReceiver(GhostBlueprintPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (payload.replace()) {
                        GhostStorage.replace(payload.blocks());
                    } else {
                        GhostStorage.addAll(payload.blocks());
                    }
                }));

        // Draw the ghosts each frame after entities are rendered.
        WorldRenderEvents.AFTER_ENTITIES.register(GhostRenderer::render);

        // Draw cached Canvas Projects for long-distance rendering (after translucent geometry).
        WorldRenderEvents.AFTER_TRANSLUCENT.register(CanvasRenderer::render);

        // Each tick, retire built ghosts and tell the server (which streams in more).
        ClientTickEvents.END_CLIENT_TICK.register(GhostTracker::tick);

        // Drop ghosts and free canvas-cache GPU textures when leaving a world/server.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            GhostStorage.clear();
            CanvasTextureCache.clearAll();
        });
    }
}
