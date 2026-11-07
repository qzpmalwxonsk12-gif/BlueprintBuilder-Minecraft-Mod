package com.example.blueprintbuilder.command;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.example.blueprintbuilder.BlueprintBuilderMod;
import com.example.blueprintbuilder.canvas.CanvasBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

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
 * Imports an RGB CSV from the {@code canvas/} folder, placing one Canvas Block per cell coloured to its RGB
 * value. With no coordinates it builds at the player's position. Two orientations, mirroring the blueprint
 * builder's {@code /build1} / {@code /build2}:
 *
 * <ul>
 *   <li>{@code /buildCanvas <file.csv> [x y z]}  — vertical wall (image stands up).</li>
 *   <li>{@code /buildCanvas2 <file.csv> [x y z]} — sideways floor (image lies flat; the rotated version).</li>
 * </ul>
 *
 * <p>Completely independent of {@code /build1} / {@code /build2}.</p>
 */
public final class BuildCanvasCommand {

    private BuildCanvasCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(tree("buildCanvas", CanvasBuilder.Orientation.DOWNWARD));
        dispatcher.register(tree("buildCanvas2", CanvasBuilder.Orientation.SIDEWAYS));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> tree(String name,
                                                                    CanvasBuilder.Orientation orientation) {
        return literal(name)
            .requires(source -> source.hasPermissionLevel(2))
            .then(argument("file", StringArgumentType.string())
                .suggests(BuildCanvasCommand::suggestCanvasFiles)
                .executes(ctx -> run(ctx, false, orientation))
                .then(argument("pos", BlockPosArgumentType.blockPos())
                    .executes(ctx -> run(ctx, true, orientation))));
    }

    private static int run(CommandContext<ServerCommandSource> ctx, boolean hasPos,
                           CanvasBuilder.Orientation orientation) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();

        // Start position: explicit coordinates, or the player's block position when omitted.
        BlockPos start;
        if (hasPos) {
            PosArgument posArg = ctx.getArgument("pos", PosArgument.class);
            start = posArg.toAbsoluteBlockPos(source);
        } else {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            start = player.getBlockPos();
        }

        // Resolve the CSV inside the canvas folder (no path escaping).
        String fileArg = StringArgumentType.getString(ctx, "file");
        String fileName = fileArg.toLowerCase(Locale.ROOT).endsWith(".csv") ? fileArg : fileArg + ".csv";
        Path dir = BlueprintBuilderMod.canvasDir;
        Path file = dir.resolve(fileName).normalize();

        if (!file.startsWith(dir)) {
            source.sendError(Text.literal("Invalid canvas name: " + fileArg));
            return 0;
        }
        if (!Files.isRegularFile(file)) {
            source.sendError(Text.literal("Canvas image not found: " + fileName
                    + "  (put it in the 'canvas' folder)"));
            return 0;
        }

        CanvasBuilder.Result result;
        try {
            result = CanvasBuilder.build(world, start, file, orientation);
        } catch (IOException e) {
            source.sendError(Text.literal("Could not read " + fileName + ": " + e.getMessage()));
            return 0;
        }

        if (result.rows() == 0) {
            source.sendError(Text.literal(fileName + " is empty — no image rows found."));
            return 0;
        }
        if (result.placed() == 0) {
            source.sendError(Text.literal(fileName + " produced no canvas blocks ("
                    + result.invalidCells() + " invalid cells)."));
            return 0;
        }

        int placed = result.placed();
        int skipped = result.skipped();
        String layout = orientation == CanvasBuilder.Orientation.SIDEWAYS ? "sideways floor" : "vertical wall";
        source.sendFeedback(() -> Text.literal(String.format(
                "Canvas %s (%s) from %s: queued %d canvas blocks (%d cells skipped). It builds shortly.",
                fileName, layout, start.toShortString(), placed, skipped))
                .formatted(Formatting.GOLD), true);
        return placed;
    }

    private static CompletableFuture<Suggestions> suggestCanvasFiles(CommandContext<ServerCommandSource> ctx,
                                                                     SuggestionsBuilder builder) {
        List<String> names = new ArrayList<>();
        Path dir = BlueprintBuilderMod.canvasDir;
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
}
