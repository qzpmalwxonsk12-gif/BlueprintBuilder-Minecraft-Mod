package com.example.blueprintbuilder.canvas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.example.blueprintbuilder.canvas.cache.CanvasCacheManager;
import com.example.blueprintbuilder.canvas.cache.CanvasPlane;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Orchestrates a canvas build: reads a CSV via {@link CsvColorReader}, maps each cell to a world position,
 * and queues a Canvas Block + colour for it via {@link CanvasBuildQueue}.
 *
 * <p>The top-left cell sits at {@code start}; columns always run +X (east). The {@link Orientation} decides
 * the other axis, mirroring the blueprint builder's {@code /build1} vs {@code /build2}. The block
 * placement/colour assignment is delegated to the queue; this class only decides <i>what</i> goes
 * <i>where</i>.</p>
 */
public final class CanvasBuilder {

    /** How the 2D image is laid into the world (same idea as the blueprint builder). */
    public enum Orientation {
        /** Vertical wall: rows run -Y (down). The image stands up. ({@code /buildCanvas}) */
        DOWNWARD,
        /** Horizontal floor: rows run +Z (south). The image lies flat. ({@code /buildCanvas2}) */
        SIDEWAYS
    }

    /** Outcome of a build request. */
    public record Result(int placed, int skipped, int invalidCells, int rows) {}

    private CanvasBuilder() {}

    public static Result build(ServerWorld world, BlockPos start, Path csv, Orientation orientation)
            throws IOException {
        CsvColorReader.Result parsed = CsvColorReader.read(csv);
        List<List<CsvColorReader.Rgb>> rows = parsed.rows();

        int height = rows.size();
        int width = 0;
        for (List<CsvColorReader.Rgb> row : rows) {
            width = Math.max(width, row.size());
        }

        // Pixel grid for the cached Canvas Project (ARGB; gaps/invalid = transparent).
        int[] argb = (width > 0 && height > 0) ? new int[width * height] : new int[0];

        int placed = 0;
        int skipped = 0;
        for (int row = 0; row < rows.size(); row++) {
            List<CsvColorReader.Rgb> cells = rows.get(row);
            for (int col = 0; col < cells.size(); col++) {
                CsvColorReader.Rgb color = cells.get(col);
                if (color == null) {
                    skipped++; // invalid/gap cell — leave it empty (stays transparent in the project)
                    continue;
                }
                BlockPos pos = positionFor(start, orientation, col, row);
                CanvasBuildQueue.enqueue(world, pos, color.red(), color.green(), color.blue());
                argb[row * width + col] = 0xFF000000 | (color.red() << 16) | (color.green() << 8) | color.blue();
                placed++;
            }
        }

        // Hand the imported image to the cache subsystem for long-distance rendering (no rendering knowledge
        // here — the importer only knows about the cache, which generates LODs and persists the project).
        if (placed > 0) {
            CanvasPlane plane = orientation == Orientation.SIDEWAYS ? CanvasPlane.FLOOR_XZ : CanvasPlane.WALL_XY;
            CanvasCacheManager.createOrUpdate(
                    world.getRegistryKey().getValue().toString(), plane,
                    start.getX(), start.getY(), start.getZ(), width, height, argb);
        }

        return new Result(placed, skipped, parsed.invalidCells(), rows.size());
    }

    private static BlockPos positionFor(BlockPos start, Orientation orientation, int col, int row) {
        return switch (orientation) {
            case DOWNWARD -> new BlockPos(start.getX() + col, start.getY() - row, start.getZ());
            case SIDEWAYS -> new BlockPos(start.getX() + col, start.getY(), start.getZ() + row);
        };
    }
}
