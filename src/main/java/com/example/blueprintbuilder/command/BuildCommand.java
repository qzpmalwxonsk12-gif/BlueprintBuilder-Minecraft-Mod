package com.example.blueprintbuilder.command;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.example.blueprintbuilder.BlueprintBuilderMod;
import com.example.blueprintbuilder.BlueprintParser;
import com.example.blueprintbuilder.BotManager;
import com.example.blueprintbuilder.BotSession;
import com.example.blueprintbuilder.BuildQueue;
import com.example.blueprintbuilder.ScaffoldManager;
import com.example.blueprintbuilder.network.GhostBlueprintPayload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.block.BlockState;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.PosArgument;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * Registers and implements the build commands.
 *
 * <p>Orientation (which command):</p>
 * <ul>
 *   <li>{@code /build1} &mdash; downward / vertical wall: column &rarr; +X, row &rarr; -Y.</li>
 *   <li>{@code /build2} &mdash; sideways / horizontal floor: column &rarr; +X, row &rarr; +Z.</li>
 * </ul>
 *
 * <p>Mode (optional suffix on {@code <file> <pos>}):</p>
 * <ul>
 *   <li>(none) &mdash; place the blocks directly, rate-limited.</li>
 *   <li>{@code scaffold} &mdash; show a translucent ghost guide (streamed, so blueprints can be huge) that
 *       disappears as you build it yourself; requires the mod on your client.</li>
 *   <li>{@code bot [rate]} &mdash; puppet your player to build it; optional {@code rate} is blocks/second.</li>
 * </ul>
 *
 * <p>Shared subcommands (one global rate / queue): {@code rate [amount]}, {@code cancel}.</p>
 */
public final class BuildCommand {

    /** How the 2D CSV grid is laid into the 3D world. */
    public enum Orientation { DOWNWARD, SIDEWAYS }

    /** What to do with the parsed blueprint. */
    public enum Mode { NORMAL, SCAFFOLD, BOT }

    private static final int DEFAULT_BOT_RATE = 8;   // blocks/second when no rate is given
    private static final int MAX_BOT_RATE = 1000;    // blocks/second upper bound

    private BuildCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(buildTree("build1", Orientation.DOWNWARD));
        dispatcher.register(buildTree("build2", Orientation.SIDEWAYS));
    }

    /** Builds one command tree ({@code /buildN}) wired to the given orientation. */
    private static LiteralArgumentBuilder<ServerCommandSource> buildTree(String name, Orientation orientation) {
        return literal(name)
            .requires(source -> source.hasPermissionLevel(2))
            // /buildN rate [amount]
            .then(literal("rate")
                .executes(BuildCommand::showRate)
                .then(argument("amount", IntegerArgumentType.integer(BuildQueue.MIN_RATE, BuildQueue.MAX_RATE))
                    .executes(BuildCommand::setRate)))
            // /buildN cancel
            .then(literal("cancel")
                .executes(BuildCommand::cancel))
            // /buildN <file> <pos> [scaffold | bot [rate]]
            .then(argument("file", StringArgumentType.string())
                .suggests(BuildCommand::suggestBlueprints)
                .then(argument("pos", BlockPosArgumentType.blockPos())
                    .executes(ctx -> startBuild(ctx, orientation, Mode.NORMAL, DEFAULT_BOT_RATE))
                    .then(literal("scaffold")
                        .executes(ctx -> startBuild(ctx, orientation, Mode.SCAFFOLD, DEFAULT_BOT_RATE)))
                    .then(literal("bot")
                        .executes(ctx -> startBuild(ctx, orientation, Mode.BOT, DEFAULT_BOT_RATE))
                        .then(argument("rate", IntegerArgumentType.integer(1, MAX_BOT_RATE))
                            .executes(ctx -> startBuild(ctx, orientation, Mode.BOT,
                                    IntegerArgumentType.getInteger(ctx, "rate")))))));
    }

    // ------------------------------------------------------------------ /buildN <file> <pos> [mode]

    private static int startBuild(CommandContext<ServerCommandSource> ctx, Orientation orientation, Mode mode,
                                  int botRate) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();

        // Resolve the start coordinate (supports ~ relative coordinates).
        PosArgument posArg = ctx.getArgument("pos", PosArgument.class);
        BlockPos start = posArg.toAbsoluteBlockPos(source);
        ServerWorld world = source.getWorld();

        // Resolve the blueprint file inside the blueprints folder (no path escaping allowed).
        String fileArg = StringArgumentType.getString(ctx, "file");
        String fileName = fileArg.toLowerCase(Locale.ROOT).endsWith(".csv") ? fileArg : fileArg + ".csv";
        Path dir = BlueprintBuilderMod.blueprintsDir;
        Path file = dir.resolve(fileName).normalize();

        if (!file.startsWith(dir)) {
            source.sendError(Text.literal("Invalid blueprint name: " + fileArg));
            return 0;
        }
        if (!Files.isRegularFile(file)) {
            source.sendError(Text.literal("Blueprint not found: " + fileName
                    + "  (put it in the 'blueprints' folder)"));
            return 0;
        }

        List<List<BlockState>> grid;
        try {
            grid = BlueprintParser.parse(file);
        } catch (IOException e) {
            source.sendError(Text.literal("Could not read " + fileName + ": " + e.getMessage()));
            return 0;
        }

        // Build the ordered set of placements. A1 (grid[0][0]) sits at 'start'; columns run +X,
        // rows run -Y (DOWNWARD) or +Z (SIDEWAYS). Empty cells are skipped.
        LinkedHashMap<BlockPos, BlockState> placements = new LinkedHashMap<>();
        int skipped = 0;
        for (int row = 0; row < grid.size(); row++) {
            List<BlockState> cells = grid.get(row);
            for (int col = 0; col < cells.size(); col++) {
                BlockState state = cells.get(col);
                if (state == null) {
                    skipped++;
                    continue;
                }
                placements.put(positionFor(start, orientation, col, row), state);
            }
        }

        if (placements.isEmpty()) {
            source.sendError(Text.literal(fileName + " produced no blocks to place (" + skipped + " empty cells)."));
            return 0;
        }

        return switch (mode) {
            case NORMAL -> runNormal(source, world, fileName, start, placements, skipped);
            case SCAFFOLD -> runScaffold(source, fileName, placements, skipped);
            case BOT -> runBot(source, world, fileName, placements, skipped, botRate);
        };
    }

    private static int runNormal(ServerCommandSource source, ServerWorld world, String fileName, BlockPos start,
                                 Map<BlockPos, BlockState> placements, int skipped) {
        placements.forEach((pos, state) -> BuildQueue.enqueue(world, pos, state));

        int rate = BuildQueue.getRate();
        int placed = placements.size();
        int seconds = Math.max(1, ceilDiv(placed, rate * 20)); // 20 ticks per second
        source.sendFeedback(() -> Text.literal(String.format(
                "Building %s from %s: queued %d blocks (%d cells skipped). Rate %d/tick ≈ %ds.",
                fileName, start.toShortString(), placed, skipped, rate, seconds))
                .formatted(Formatting.GREEN), true);
        return placed;
    }

    private static int runScaffold(ServerCommandSource source, String fileName,
                                   LinkedHashMap<BlockPos, BlockState> placements, int skipped)
            throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();

        if (!ServerPlayNetworking.canSend(player, GhostBlueprintPayload.ID)) {
            source.sendError(Text.literal(
                    "Scaffold needs Blueprint Builder installed on your client to show the ghost preview."));
            return 0;
        }

        ScaffoldManager.start(player, placements);

        int total = placements.size();
        int shown = Math.min(total, ScaffoldManager.WINDOW);
        source.sendFeedback(() -> Text.literal(String.format(
                "Scaffold for %s: %d ghost blocks (%d cells skipped), showing %d at a time and streaming the rest as "
                        + "you build. /build1 cancel to clear.", fileName, total, skipped, shown))
                .formatted(Formatting.AQUA), false);
        return total;
    }

    private static int runBot(ServerCommandSource source, ServerWorld world, String fileName,
                              Map<BlockPos, BlockState> placements, int skipped, int botRate)
            throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        BotManager.start(player.getUuid(), new BotSession(player, world, placements, botRate));

        int count = placements.size();
        source.sendFeedback(() -> Text.literal(String.format(
                "Bot building %s: %d blocks (%d cells skipped) at %d/s. Look around freely — /build1 cancel to stop.",
                fileName, count, skipped, botRate))
                .formatted(Formatting.LIGHT_PURPLE), true);
        return count;
    }

    /** Maps a CSV (col,row) cell to a world position for the given orientation. */
    private static BlockPos positionFor(BlockPos start, Orientation orientation, int col, int row) {
        return switch (orientation) {
            case DOWNWARD -> new BlockPos(start.getX() + col, start.getY() - row, start.getZ());
            case SIDEWAYS -> new BlockPos(start.getX() + col, start.getY(), start.getZ() + row);
        };
    }

    // ------------------------------------------------------------------ /buildN rate [amount]

    private static int setRate(CommandContext<ServerCommandSource> ctx) {
        int requested = IntegerArgumentType.getInteger(ctx, "amount");
        int actual = BuildQueue.setRate(requested);
        ctx.getSource().sendFeedback(() -> Text.literal(
                "Build rate set to " + actual + " blocks/tick (" + (actual * 20) + "/second).")
                .formatted(Formatting.YELLOW), true);
        return actual;
    }

    private static int showRate(CommandContext<ServerCommandSource> ctx) {
        int rate = BuildQueue.getRate();
        int remaining = BuildQueue.remaining();
        ctx.getSource().sendFeedback(() -> Text.literal(
                "Build rate: " + rate + " blocks/tick (" + (rate * 20) + "/second). Queued: " + remaining + "."),
                false);
        return rate;
    }

    // ------------------------------------------------------------------ /buildN cancel

    private static int cancel(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        int dropped = BuildQueue.clear();

        boolean stoppedBot = false;
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            stoppedBot = BotManager.cancel(player.getUuid());
            ScaffoldManager.cancel(player.getUuid());
            // Clear the player's scaffold ghost by sending an empty replacement set.
            if (ServerPlayNetworking.canSend(player, GhostBlueprintPayload.ID)) {
                ServerPlayNetworking.send(player, new GhostBlueprintPayload(true, new LinkedHashMap<>()));
            }
        }

        final boolean bot = stoppedBot;
        source.sendFeedback(() -> Text.literal("Cancelled: dropped " + dropped + " queued blocks"
                + (bot ? ", stopped the bot" : "") + ", cleared scaffold.")
                .formatted(Formatting.RED), true);
        return dropped;
    }

    // ------------------------------------------------------------------ suggestions

    private static CompletableFuture<Suggestions> suggestBlueprints(CommandContext<ServerCommandSource> ctx,
                                                                    SuggestionsBuilder builder) {
        List<String> names = new ArrayList<>();
        Path dir = BlueprintBuilderMod.blueprintsDir;
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".csv"))
                        .forEach(names::add);
            } catch (IOException ignored) {
                // No suggestions if the folder can't be listed.
            }
        }
        return CommandSource.suggestMatching(names, builder);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
