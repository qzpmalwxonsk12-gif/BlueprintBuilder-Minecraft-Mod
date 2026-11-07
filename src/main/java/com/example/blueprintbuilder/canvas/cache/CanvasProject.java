package com.example.blueprintbuilder.canvas.cache;

import java.util.List;

/**
 * One imported image as a cached render dataset: a unique id, where it sits in the world (dimension, plane,
 * origin, size), a content hash (to skip regeneration when unchanged), and a pyramid of {@link CanvasLod}s.
 *
 * <p>Pure data (no Minecraft types) so the whole cache/LOD pipeline can be unit-tested and serialized
 * directly. The renderer/scanner translate {@code (plane, origin, width, height)} into world coordinates.</p>
 *
 * <p>Future-proofing: {@code depth} is kept (currently always 1) and the per-LOD pixels are ARGB, so
 * transparency/animation/emissive can be layered on without changing this shape.</p>
 */
public record CanvasProject(
        String id,
        String dimensionId,
        CanvasPlane plane,
        int originX, int originY, int originZ,
        int width, int height, int depth,
        long contentHash,
        List<CanvasLod> lods) {

    /** Deterministic id so re-importing at the same spot updates the same project. */
    public static String idFor(String dimensionId, CanvasPlane plane, int originX, int originY, int originZ) {
        return plane.name() + "@" + originX + "_" + originY + "_" + originZ + "@"
                + dimensionId.replace(':', '.').replace('/', '.');
    }

    /** 64-bit FNV-1a over the pixel data — cheap change detection. */
    public static long hash(int[] pixels) {
        long h = 0xcbf29ce484222325L;
        for (int p : pixels) {
            h = (h ^ (p & 0xFFFFFFFFL)) * 0x100000001b3L;
        }
        return h;
    }

    public int lodCount() {
        return lods.size();
    }

    /** LOD 0 (full resolution). */
    public CanvasLod base() {
        return lods.get(0);
    }

    /** Clamped LOD lookup. */
    public CanvasLod lod(int level) {
        return lods.get(Math.max(0, Math.min(level, lods.size() - 1)));
    }

    /** Rough in-memory size of all LODs, in bytes. */
    public long approxBytes() {
        long bytes = 0;
        for (CanvasLod lod : lods) {
            bytes += 16L + (long) lod.pixelCount() * 4L;
        }
        return bytes;
    }

    /** Number of non-transparent base pixels = number of real Canvas Blocks the project represents. */
    public int blockCount() {
        int n = 0;
        for (int p : base().pixels()) {
            if ((p >>> 24) != 0) {
                n++;
            }
        }
        return n;
    }
}
