package com.example.blueprintbuilder.canvas.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.example.blueprintbuilder.BlueprintBuilderMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Owns the Canvas Project cache: an in-memory map (read by the client renderer in singleplayer), backed by
 * one gzipped file per project in the world save folder. Loads on server start, persists on change, and
 * exposes the rebuild/clear/info operations behind {@code /canvasCache}.
 *
 * <p>Projects are immutable once built (a rebuild creates a replacement), so the render thread can read the
 * map without locking.</p>
 */
public final class CanvasCacheManager {

    /** LOD0..LOD4 = 100%, 50%, 25%, 12.5%, 6.25%. */
    public static final int LOD_LEVELS = 5;

    private static final Map<String, CanvasProject> PROJECTS = new ConcurrentHashMap<>();
    private static volatile Path cacheDir;

    private CanvasCacheManager() {}

    // ----------------------------------------------------------------- lifecycle

    public static void onServerStarted(MinecraftServer server) {
        PROJECTS.clear();
        cacheDir = server.getSavePath(WorldSavePath.ROOT).resolve("canvascache");
        try {
            Files.createDirectories(cacheDir);
            try (Stream<Path> files = Files.list(cacheDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".ccp")).forEach(file -> {
                    try {
                        CanvasProject project = CanvasCache.read(file);
                        PROJECTS.put(project.id(), project);
                    } catch (Exception e) {
                        // Catch everything, not just IOException: a corrupted file must never be able
                        // to abort world start — skip it and keep loading the rest.
                        BlueprintBuilderMod.LOGGER.warn("[CanvasCache] Failed to load {}", file, e);
                    }
                });
            }
            BlueprintBuilderMod.LOGGER.info("[CanvasCache] Loaded {} canvas project(s) from {}",
                    PROJECTS.size(), cacheDir);
        } catch (IOException e) {
            BlueprintBuilderMod.LOGGER.error("[CanvasCache] Could not open cache dir {}", cacheDir, e);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        PROJECTS.clear();
        cacheDir = null;
    }

    // ----------------------------------------------------------------- reads (renderer)

    /** Live view of cached projects. Safe to iterate from the render thread (projects are immutable). */
    public static Collection<CanvasProject> projects() {
        return PROJECTS.values();
    }

    public static boolean isEmpty() {
        return PROJECTS.isEmpty();
    }

    // ----------------------------------------------------------------- creation / update

    /**
     * Creates or replaces the project at this origin from a freshly-imported pixel grid (called by the CSV
     * importer when a build is dispatched). Skips all work if the content is unchanged.
     */
    public static void createOrUpdate(String dimensionId, CanvasPlane plane,
                                      int originX, int originY, int originZ,
                                      int width, int height, int[] argb) {
        String id = CanvasProject.idFor(dimensionId, plane, originX, originY, originZ);
        long hash = CanvasProject.hash(argb);
        CanvasProject existing = PROJECTS.get(id);
        if (existing != null && existing.contentHash() == hash) {
            return; // unchanged → don't regenerate
        }
        List<CanvasLod> lods = CanvasLodGenerator.generate(argb, width, height, LOD_LEVELS - 1);
        CanvasProject project = new CanvasProject(id, dimensionId, plane,
                originX, originY, originZ, width, height, 1, hash, lods);
        store(project);
        BlueprintBuilderMod.LOGGER.info("[CanvasCache] Cached project {} ({}x{}, {} LODs)",
                id, width, height, lods.size());
    }

    // ----------------------------------------------------------------- scan (discover existing art)

    /**
     * Discovers canvas blocks in the loaded chunks around {@code center}, groups connected ones into flat
     * Canvas Projects, and caches them. This is how art placed before the cache existed (or by hand) gets
     * picked up. Returns how many flat projects were found.
     */
    public static int scanAndCreate(ServerWorld world, BlockPos center, int chunkRadius) {
        List<BlockPos> blocks = CanvasProjectScanner.findCanvasBlocks(world, center, chunkRadius);
        if (blocks.isEmpty()) {
            return 0;
        }
        Set<BlockPos> remaining = new HashSet<>(blocks);
        String dimension = world.getRegistryKey().getValue().toString();
        int created = 0;
        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            if (createFromComponent(world, dimension, floodFill(seed, remaining))) {
                created++;
            }
        }
        return created;
    }

    private static List<BlockPos> floodFill(BlockPos seed, Set<BlockPos> remaining) {
        List<BlockPos> component = new ArrayList<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        remaining.remove(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            component.add(pos);
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.offset(dir);
                if (remaining.remove(next)) {
                    queue.add(next);
                }
            }
        }
        return component;
    }

    /** Builds a project from a connected component if it's a flat XY or XZ plane; false otherwise. */
    private static boolean createFromComponent(ServerWorld world, String dimension, List<BlockPos> component) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : component) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int extentX = maxX - minX, extentY = maxY - minY, extentZ = maxZ - minZ;

        CanvasPlane plane;
        int ox, oy, oz, width, height;
        if (extentZ == 0) {            // flat in Z → vertical wall (rows go down from the top)
            plane = CanvasPlane.WALL_XY; ox = minX; oy = maxY; oz = minZ; width = extentX + 1; height = extentY + 1;
        } else if (extentY == 0) {     // flat in Y → horizontal floor
            plane = CanvasPlane.FLOOR_XZ; ox = minX; oy = minY; oz = minZ; width = extentX + 1; height = extentZ + 1;
        } else {
            return false;              // not a flat XY/XZ plane (e.g. a wall facing X) — can't represent yet
        }

        int[] argb = new int[width * height];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                BlockPos pos = plane == CanvasPlane.WALL_XY
                        ? new BlockPos(ox + col, oy - row, oz) : new BlockPos(ox + col, oy, oz + row);
                argb[row * width + col] = CanvasProjectScanner.colorAt(world, pos);
            }
        }
        createOrUpdate(dimension, plane, ox, oy, oz, width, height, argb);
        return true;
    }

    // ----------------------------------------------------------------- rebuild (from world)

    /** Rebuilds every project in the given world from its real blocks. Returns how many were rebuilt. */
    public static int rebuildAll(ServerWorld world) {
        int n = 0;
        for (CanvasProject project : new ArrayList<>(PROJECTS.values())) {
            if (inWorld(project, world) && rebuild(world, project)) {
                n++;
            }
        }
        return n;
    }

    /** Rebuilds only the project nearest to {@code pos}. Returns it (rebuilt or not), or null if none. */
    public static CanvasProject rebuildNearest(ServerWorld world, BlockPos pos) {
        CanvasProject best = null;
        double bestDist = Double.MAX_VALUE;
        for (CanvasProject project : PROJECTS.values()) {
            if (!inWorld(project, world)) {
                continue;
            }
            BlockPos center = CanvasProjectScanner.worldPos(project, project.width() / 2, project.height() / 2);
            double d = center.getSquaredDistance(pos);
            if (d < bestDist) {
                bestDist = d;
                best = project;
            }
        }
        if (best != null) {
            rebuild(world, best);
        }
        return best;
    }

    private static boolean rebuild(ServerWorld world, CanvasProject project) {
        int[] pixels = CanvasProjectScanner.readRegion(world, project);
        long hash = CanvasProject.hash(pixels);
        if (hash == project.contentHash()) {
            return false; // nothing changed in the world → keep existing LODs
        }
        List<CanvasLod> lods = CanvasLodGenerator.generate(pixels, project.width(), project.height(), LOD_LEVELS - 1);
        store(new CanvasProject(project.id(), project.dimensionId(), project.plane(),
                project.originX(), project.originY(), project.originZ(),
                project.width(), project.height(), project.depth(), hash, lods));
        return true;
    }

    // ----------------------------------------------------------------- clear

    /** Deletes all cached projects (memory + disk). Returns how many were removed. */
    public static int clear() {
        int n = PROJECTS.size();
        PROJECTS.clear();
        if (cacheDir != null) {
            try (Stream<Path> files = Files.list(cacheDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".ccp")).forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        BlueprintBuilderMod.LOGGER.warn("[CanvasCache] Could not delete {}", file, e);
                    }
                });
            } catch (IOException e) {
                BlueprintBuilderMod.LOGGER.warn("[CanvasCache] Could not list cache dir for clear", e);
            }
        }
        return n;
    }

    // ----------------------------------------------------------------- info

    public static int projectCount() {
        return PROJECTS.size();
    }

    public static long totalBlockCount() {
        long n = 0;
        for (CanvasProject p : PROJECTS.values()) {
            n += p.blockCount();
        }
        return n;
    }

    public static long totalDiskBytes() {
        if (cacheDir == null) {
            return 0;
        }
        long bytes = 0;
        try (Stream<Path> files = Files.list(cacheDir)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().endsWith(".ccp"))::iterator) {
                bytes += Files.size(file);
            }
        } catch (IOException ignored) {
            // best effort
        }
        return bytes;
    }

    // ----------------------------------------------------------------- internals

    private static void store(CanvasProject project) {
        PROJECTS.put(project.id(), project);
        if (cacheDir != null) {
            try {
                CanvasCache.write(cacheDir.resolve(CanvasCache.fileName(project.id())), project);
            } catch (IOException e) {
                BlueprintBuilderMod.LOGGER.error("[CanvasCache] Could not save project {}", project.id(), e);
            }
        }
    }

    private static boolean inWorld(CanvasProject project, ServerWorld world) {
        return project.dimensionId().equals(world.getRegistryKey().getValue().toString());
    }
}
