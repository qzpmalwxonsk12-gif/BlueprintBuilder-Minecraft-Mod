package com.example.blueprintbuilder.canvas.cache;

import java.util.ArrayList;
import java.util.List;

import com.example.blueprintbuilder.canvas.CanvasBlockEntity;
import com.example.blueprintbuilder.canvas.CanvasRegistry;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Reads a Canvas Project's pixels back from the actual world blocks (the source of truth) over its known
 * region. Used by {@code /canvasCache rebuild} / {@code rebuildCurrent}. Knows nothing about CSV files or
 * rendering.
 *
 * <p>Non-canvas / missing blocks in the region read as transparent (alpha 0), so gaps are preserved.</p>
 */
public final class CanvasProjectScanner {

    private CanvasProjectScanner() {}

    /** Re-reads the {@code width × height} region of {@code project} from {@code world} into ARGB pixels. */
    public static int[] readRegion(ServerWorld world, CanvasProject project) {
        int w = project.width();
        int h = project.height();
        int[] pixels = new int[w * h];

        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                BlockPos pos = worldPos(project, col, row);
                int argb = 0; // transparent unless a canvas block is found
                if (world.getBlockState(pos).isOf(CanvasRegistry.CANVAS_BLOCK)) {
                    BlockEntity be = world.getBlockEntity(pos);
                    if (be instanceof CanvasBlockEntity canvas) {
                        argb = 0xFF000000 | (canvas.getRgb() & 0xFFFFFF);
                    }
                }
                pixels[row * w + col] = argb;
            }
        }
        return pixels;
    }

    /**
     * Finds all Canvas Block positions in the loaded chunks within {@code chunkRadius} of {@code center}.
     * Iterates each loaded chunk's block-entity map (cheap) rather than scanning every block.
     */
    public static List<BlockPos> findCanvasBlocks(ServerWorld world, BlockPos center, int chunkRadius) {
        List<BlockPos> found = new ArrayList<>();
        int centerCx = center.getX() >> 4;
        int centerCz = center.getZ() >> 4;
        for (int cx = centerCx - chunkRadius; cx <= centerCx + chunkRadius; cx++) {
            for (int cz = centerCz - chunkRadius; cz <= centerCz + chunkRadius; cz++) {
                Chunk chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(chunk instanceof WorldChunk loaded)) {
                    continue; // not loaded — skip (we never force-load for a scan)
                }
                for (BlockPos pos : loaded.getBlockEntities().keySet()) {
                    if (loaded.getBlockEntity(pos) instanceof CanvasBlockEntity) {
                        found.add(pos.toImmutable());
                    }
                }
            }
        }
        return found;
    }

    /** Reads the colour of a single canvas block, or 0 (transparent) if it isn't one. */
    public static int colorAt(ServerWorld world, BlockPos pos) {
        if (world.getBlockState(pos).isOf(CanvasRegistry.CANVAS_BLOCK)
                && world.getBlockEntity(pos) instanceof CanvasBlockEntity canvas) {
            return 0xFF000000 | (canvas.getRgb() & 0xFFFFFF);
        }
        return 0;
    }

    /** World position of image cell (col,row) for a project's plane. */
    public static BlockPos worldPos(CanvasProject project, int col, int row) {
        return switch (project.plane()) {
            case WALL_XY -> new BlockPos(project.originX() + col, project.originY() - row, project.originZ());
            case FLOOR_XZ -> new BlockPos(project.originX() + col, project.originY(), project.originZ() + row);
        };
    }
}
