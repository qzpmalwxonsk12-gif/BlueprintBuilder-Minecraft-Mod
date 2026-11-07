package com.example.blueprintbuilder.client;

import java.util.Map;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Renders the current scaffold ghost as translucent block models. A ghost cell is hidden once the real
 * world block at that position matches the target block (i.e. the player placed the right block there),
 * which gives the "phantom block disappears when you build it" behaviour.
 */
public final class GhostRenderer {

    private static final float GHOST_ALPHA = 0.45f;

    private GhostRenderer() {}

    public static void render(WorldRenderContext context) {
        Map<BlockPos, BlockState> ghosts = GhostStorage.get();
        if (ghosts.isEmpty()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (world == null || matrices == null || consumers == null) {
            return;
        }

        Vec3d cam = context.camera().getPos();
        Frustum frustum = context.frustum();
        RenderLayer layer = TexturedRenderLayers.getEntityTranslucentCull();
        GhostVertexConsumerProvider ghostConsumers = new GhostVertexConsumerProvider(consumers, layer, GHOST_ALPHA);
        BlockRenderManager blockRenderer = client.getBlockRenderManager();

        for (Map.Entry<BlockPos, BlockState> entry : ghosts.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState target = entry.getValue();

            // Skip ghosts outside the view frustum — with the 30k window most of a large scaffold is
            // off-screen at any moment, and model rendering is by far the dominant cost here.
            if (frustum != null && !frustum.isVisible(new Box(pos))) {
                continue;
            }
            if (GhostTracker.isSatisfied(world.getBlockState(pos), target)) {
                continue; // already built here -> no ghost
            }

            matrices.push();
            matrices.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
            blockRenderer.renderBlockAsEntity(target, matrices, ghostConsumers,
                    LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
            matrices.pop();
        }

        // Flush our translucent layer now so the ghosts appear during this pass.
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            immediate.draw(layer);
        }
    }
}
