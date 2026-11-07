package com.example.blueprintbuilder.command;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.example.blueprintbuilder.canvas.cache.CanvasCacheManager;
import com.example.blueprintbuilder.canvas.cache.CanvasProject;
import com.example.blueprintbuilder.canvas.cache.CanvasRenderSettings;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * {@code /canvasCache rebuild | rebuildCurrent | clear | info | lod <n|auto>} — manages the Canvas Cache
 * long-distance render datasets. Independent of {@code /buildCanvas} and the blueprint commands.
 */
public final class CanvasCacheCommand {

    /** Chunk radius scanned by {@code /canvasCache scan} (only loaded chunks are read). */
    private static final int SCAN_CHUNK_RADIUS = 8;

    private CanvasCacheCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            literal("canvasCache")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("rebuild").executes(CanvasCacheCommand::rebuild))
                .then(literal("rebuildCurrent").executes(CanvasCacheCommand::rebuildCurrent))
                .then(literal("scan").executes(CanvasCacheCommand::scan))
                .then(literal("clear").executes(CanvasCacheCommand::clear))
                .then(literal("info").executes(CanvasCacheCommand::info))
                .then(literal("debug")
                    .then(literal("on").executes(ctx -> setDebug(ctx, true)))
                    .then(literal("off").executes(ctx -> setDebug(ctx, false))))
                .then(literal("lod")
                    .then(literal("auto").executes(CanvasCacheCommand::lodAuto))
                    .then(argument("level", IntegerArgumentType.integer(0, CanvasCacheManager.LOD_LEVELS - 1))
                        .executes(CanvasCacheCommand::lodForced))));
    }

    private static int scan(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        int n = CanvasCacheManager.scanAndCreate(source.getWorld(), player.getBlockPos(), SCAN_CHUNK_RADIUS);
        source.sendFeedback(() -> Text.literal(n == 0
                ? "No flat canvas projects found in nearby loaded chunks."
                : "Scanned and cached " + n + " canvas project(s) from nearby canvas blocks.")
                .formatted(Formatting.GOLD), true);
        return n;
    }

    private static int setDebug(CommandContext<ServerCommandSource> ctx, boolean on) {
        CanvasRenderSettings.setDebugAlwaysRender(on);
        ctx.getSource().sendFeedback(() -> Text.literal("Canvas Cache debug render " + (on
                ? "ON — drawing every cached project at full opacity, ignoring distance and frustum."
                : "OFF — back to normal distance-based rendering.")).formatted(Formatting.YELLOW), false);
        return on ? 1 : 0;
    }

    private static int rebuild(CommandContext<ServerCommandSource> ctx) {
        ServerWorld world = ctx.getSource().getWorld();
        int n = CanvasCacheManager.rebuildAll(world);
        ctx.getSource().sendFeedback(() -> Text.literal("Rebuilt " + n + " canvas project(s) from world blocks.")
                .formatted(Formatting.GOLD), true);
        return n;
    }

    private static int rebuildCurrent(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        BlockPos pos = player.getBlockPos();
        CanvasProject project = CanvasCacheManager.rebuildNearest(source.getWorld(), pos);
        if (project == null) {
            source.sendError(Text.literal("No canvas projects in this world to rebuild."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("Rebuilt nearest canvas project: " + project.id())
                .formatted(Formatting.GOLD), true);
        return 1;
    }

    private static int clear(CommandContext<ServerCommandSource> ctx) {
        int n = CanvasCacheManager.clear();
        ctx.getSource().sendFeedback(() -> Text.literal("Cleared " + n + " cached canvas project(s).")
                .formatted(Formatting.RED), true);
        return n;
    }

    private static int info(CommandContext<ServerCommandSource> ctx) {
        int projects = CanvasCacheManager.projectCount();
        long bytes = CanvasCacheManager.totalDiskBytes();
        long blocks = CanvasCacheManager.totalBlockCount();
        ctx.getSource().sendFeedback(() -> Text.literal(
                "Canvas Cache:\n"
                        + " • Cached projects: " + projects + "\n"
                        + " • Total cache size: " + humanBytes(bytes) + "\n"
                        + " • LOD levels: " + CanvasCacheManager.LOD_LEVELS + " (LOD0..LOD"
                        + (CanvasCacheManager.LOD_LEVELS - 1) + ")\n"
                        + " • Total canvas blocks: " + blocks + "\n"
                        + " • LOD mode: " + (CanvasRenderSettings.isAuto()
                                ? "automatic" : "forced LOD" + CanvasRenderSettings.forcedLod()))
                .formatted(Formatting.AQUA), false);
        return projects;
    }

    private static int lodForced(CommandContext<ServerCommandSource> ctx) {
        int level = IntegerArgumentType.getInteger(ctx, "level");
        CanvasRenderSettings.setForcedLod(level);
        ctx.getSource().sendFeedback(() -> Text.literal("Canvas LOD forced to LOD" + level
                + " (overrides distance).").formatted(Formatting.YELLOW), false);
        return level;
    }

    private static int lodAuto(CommandContext<ServerCommandSource> ctx) {
        CanvasRenderSettings.setAuto();
        ctx.getSource().sendFeedback(() -> Text.literal("Canvas LOD set to automatic (distance-based).")
                .formatted(Formatting.YELLOW), false);
        return 1;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
