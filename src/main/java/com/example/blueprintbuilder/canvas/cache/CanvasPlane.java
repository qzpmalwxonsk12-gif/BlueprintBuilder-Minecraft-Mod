package com.example.blueprintbuilder.canvas.cache;

/**
 * Which world plane a Canvas Project's image lies in, mirroring the two {@code /buildCanvas} orientations.
 * Pure data (no Minecraft types) so the cache/LOD core stays unit-testable.
 *
 * <p>In both cases image column 0 / row 0 (the top-left pixel) is at the project origin, and columns run
 * +X (east).</p>
 */
public enum CanvasPlane {
    /** Vertical wall: rows run -Y (down), fixed Z. The image stands up. ({@code /buildCanvas}) */
    WALL_XY,
    /** Horizontal floor: rows run +Z (south), fixed Y. The image lies flat. ({@code /buildCanvas2}) */
    FLOOR_XZ
}
