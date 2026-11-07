package com.example.blueprintbuilder.client.canvas.cache;

import com.example.blueprintbuilder.canvas.cache.CanvasCacheManager;
import com.example.blueprintbuilder.canvas.cache.CanvasPlane;
import com.example.blueprintbuilder.canvas.cache.CanvasProject;
import com.example.blueprintbuilder.canvas.cache.CanvasRenderSettings;
import com.mojang.blaze3d.systems.RenderSystem;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import org.joml.Matrix4f;

/**
 * Draws cached Canvas Projects as flat, unlit textured quads — one quad + one texture per visible project —
 * so a 2000×2000 image costs the same as a small one and stays visible far past chunk render distance
 * without loaded chunks. Uses the {@code position_tex_color} shader directly (not the block/entity renderer),
 * frustum-culls, picks a LOD by distance (or a manual override), and blends from real blocks (near) to cache
 * only (far). Does not write to the depth buffer, so it never punches holes in the world.
 */
public final class CanvasRenderer {

    /** Distance (blocks) at which LOD0 gives way to LOD1; each step doubles the distance. */
    private static final double LOD0_MAX_DISTANCE = 96.0;

    /** Quad texture coordinates, matching the corner order from {@link #corners}. */
    private static final float[][] UV = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

    private CanvasRenderer() {}

    public static void render(WorldRenderContext context) {
        if (CanvasCacheManager.isEmpty()) {
            // No projects (e.g. after /canvasCache clear): also free any GPU textures still registered
            // for projects that no longer exist. No-op when the texture cache is already empty.
            CanvasTextureCache.clearAll();
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        Matrix4f matrix = context.matrixStack() != null
                ? context.matrixStack().peek().getPositionMatrix()
                : context.positionMatrix();
        if (matrix == null) {
            return;
        }

        boolean debug = CanvasRenderSettings.isDebugAlwaysRender();
        String dimension = world.getRegistryKey().getValue().toString();
        Vec3d camPos = context.camera().getPos();
        Frustum frustum = context.frustum();
        int viewDistanceBlocks = client.options.getViewDistance().getValue() * 16;

        // GL state is set lazily, just before the first quad actually drawn, so frames where every
        // project is culled (near, off-screen, other dimension) touch no render state at all.
        boolean stateSet = false;
        try {
            for (CanvasProject project : CanvasCacheManager.projects()) {
                if (!project.dimensionId().equals(dimension)) {
                    continue;
                }
                double[][] c = corners(project);
                Box box = worldBox(c);
                if (!debug && frustum != null && !frustum.isVisible(box)) {
                    continue;
                }
                double dist = distanceToBox(box, camPos);
                float blend = debug ? 1.0f : blendAlpha(dist, viewDistanceBlocks);
                if (blend <= 0.01f) {
                    continue; // near: real Canvas Blocks render; cache hidden
                }
                if (!stateSet) {
                    stateSet = true;
                    // --- unlit, blended, non-depth-writing textured quads ---
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.disableCull();          // double-sided
                    RenderSystem.depthMask(false);       // never occlude the world / punch holes
                    if (debug) {
                        RenderSystem.disableDepthTest();  // debug: always on top so you can confirm it draws
                    } else {
                        RenderSystem.enableDepthTest();   // normal: terrain in front still occludes it
                    }
                    RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
                }
                drawQuad(matrix, project, chooseLod(project, dist), c, camPos, blend);
            }
        } finally {
            if (stateSet) {
                // restore defaults so we don't corrupt later rendering
                RenderSystem.depthMask(true);
                RenderSystem.enableCull();
                RenderSystem.enableDepthTest();
                RenderSystem.disableBlend();
            }
        }
    }

    private static void drawQuad(Matrix4f matrix, CanvasProject project, int lod, double[][] c, Vec3d camPos,
                                 float alpha) {
        Identifier texture = CanvasTextureCache.getOrCreate(project, lod);
        RenderSystem.setShaderTexture(0, texture);

        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));

        BufferBuilder bb = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        for (int i = 0; i < 4; i++) {
            bb.vertex(matrix,
                    (float) (c[i][0] - camPos.x),
                    (float) (c[i][1] - camPos.y),
                    (float) (c[i][2] - camPos.z))
              .texture(UV[i][0], UV[i][1])
              .color(255, 255, 255, a);
        }
        BuiltBuffer built = bb.endNullable();
        if (built != null) {
            BufferRenderer.drawWithGlobalProgram(built);
        }
    }

    // ----------------------------------------------------------------- geometry

    private static double[][] corners(CanvasProject project) {
        double x0 = project.originX();
        double x1 = project.originX() + project.width();
        return switch (project.plane()) {
            case WALL_XY -> {
                double yTop = project.originY() + 1.0;
                double yBot = project.originY() + 1.0 - project.height();
                double z = project.originZ() + 0.5;
                yield new double[][] {{x0, yTop, z}, {x1, yTop, z}, {x1, yBot, z}, {x0, yBot, z}};
            }
            case FLOOR_XZ -> {
                double z0 = project.originZ();
                double z1 = project.originZ() + project.height();
                double y = project.originY() + 1.0;
                yield new double[][] {{x0, y, z0}, {x1, y, z0}, {x1, y, z1}, {x0, y, z1}};
            }
        };
    }

    private static Box worldBox(double[][] c) {
        double minX = Math.min(c[0][0], c[2][0]), maxX = Math.max(c[0][0], c[2][0]);
        double minY = Math.min(c[0][1], c[2][1]), maxY = Math.max(c[0][1], c[2][1]);
        double minZ = Math.min(c[0][2], c[2][2]), maxZ = Math.max(c[0][2], c[2][2]);
        return new Box(minX, minY, minZ, maxX, maxY, maxZ).expand(0.5);
    }

    // ----------------------------------------------------------------- distance / LOD / blend

    private static double distanceToBox(Box box, Vec3d p) {
        double dx = Math.max(Math.max(box.minX - p.x, 0.0), p.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - p.y, 0.0), p.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - p.z, 0.0), p.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int chooseLod(CanvasProject project, double dist) {
        int max = project.lodCount() - 1;
        if (!CanvasRenderSettings.isAuto()) {
            return Math.min(CanvasRenderSettings.forcedLod(), max);
        }
        if (dist <= LOD0_MAX_DISTANCE) {
            return 0;
        }
        int lod = (int) Math.round(Math.log(dist / LOD0_MAX_DISTANCE) / Math.log(2.0));
        return Math.max(0, Math.min(max, lod));
    }

    /** 0 near (real blocks shown), ramps to 1 across the medium band, 1 far (cache only). */
    private static float blendAlpha(double dist, int viewDistanceBlocks) {
        double nearEnd = viewDistanceBlocks * 0.75;
        double farStart = Math.max(nearEnd + 16.0, viewDistanceBlocks);
        if (dist <= nearEnd) {
            return 0.0f;
        }
        if (dist >= farStart) {
            return 1.0f;
        }
        double t = (dist - nearEnd) / (farStart - nearEnd);
        return (float) (t * t * (3.0 - 2.0 * t));
    }
}
