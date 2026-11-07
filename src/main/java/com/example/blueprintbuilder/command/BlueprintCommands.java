package com.example.blueprintbuilder.command;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.example.blueprintbuilder.BlueprintBuilderMod;
import com.example.blueprintbuilder.BotManager;
import com.example.blueprintbuilder.BotSession;
import com.example.blueprintbuilder.ScaffoldManager;
import com.example.blueprintbuilder.blueprint.BlueprintExporter;
import com.example.blueprintbuilder.blueprint.BlueprintImporter;
import com.example.blueprintbuilder.blueprint.BlueprintManager;
import com.example.blueprintbuilder.blueprint.BlueprintMetadata;
import com.example.blueprintbuilder.blueprint.BlueprintSelectionManager;
import com.example.blueprintbuilder.blueprint.structure.StructureNbtExporter;
import com.example.blueprintbuilder.blueprint.version.StructureVersion;
import com.example.blueprintbuilder.network.GhostBlueprintPayload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
 * {@code /blueprint pos1 | pos2 | export <name> | import <name> [x y z] [scaffold|bot [rate]] | cancel} —
 * the standalone Blueprint export/import system. Independent of {@code /build*}, but the {@code scaffold} and
 * {@code bot} import modes reuse the same scaffold/bot systems as {@code /build} (both take a placement map),
 * so they behave identically: scaffold shows translucent ghost guide blocks, bot puppets the player.
 */
public final class BlueprintCommands {

    private enum Mode { NORMAL, SCAFFOLD, BOT }

    private static final int DEFAULT_BOT_RATE = 8;
    private static final int MAX_BOT_RATE = 1000;

    private BlueprintCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            literal("blueprint")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("pos1").executes(ctx -> setPos(ctx, true)))
                .then(literal("pos2").executes(ctx -> setPos(ctx, false)))
                .then(literal("cancel").executes(BlueprintCommands::cancel))
                .then(literal("export")
                    .then(argument("name", StringArgumentType.string())
                        .executes(BlueprintCommands::export)
                        // /blueprint export <name> nbt [<version>] [overwrite] — existing blueprint → .nbt
                        .then(literal("nbt")
                            .executes(ctx -> exportNbt(ctx, null, false))
                            .then(argument("version", StringArgumentType.string())
                                .suggests(BlueprintCommands::suggestVersions)
                                .executes(ctx -> exportNbt(ctx,
                                        StringArgumentType.getString(ctx, "version"), false))
                                .then(literal("overwrite")
                                    .executes(ctx -> exportNbt(ctx,
                                            StringArgumentType.getString(ctx, "version"), true)))))))
                .then(literal("import")
                    .then(argument("name", StringArgumentType.string())
                        .suggests(BlueprintCommands::suggestBlueprints)
                        .executes(ctx -> runImport(ctx, false, Mode.NORMAL, DEFAULT_BOT_RATE))
                        .then(modeBranch("scaffold", false, Mode.SCAFFOLD))
                        .then(botBranch(false))
                        .then(argument("pos", BlockPosArgumentType.blockPos())
                            .executes(ctx -> runImport(ctx, true, Mode.NORMAL, DEFAULT_BOT_RATE))
                            .then(modeBranch("scaffold", true, Mode.SCAFFOLD))
                            .then(botBranch(true))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> modeBranch(
            String name, boolean hasPos, Mode mode) {
        return literal(name).executes(ctx -> runImport(ctx, hasPos, mode, DEFAULT_BOT_RATE));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> botBranch(boolean hasPos) {
        return literal("bot")
                .executes(ctx -> runImport(ctx, hasPos, Mode.BOT, DEFAULT_BOT_RATE))
                .then(argument("rate", IntegerArgumentType.integer(1, MAX_BOT_RATE))
                        .executes(ctx -> runImport(ctx, hasPos, Mode.BOT, IntegerArgumentType.getInteger(ctx, "rate"))));
    }

    // ------------------------------------------------------------------ pos1 / pos2

    private static int setPos(CommandContext<ServerCommandSource> ctx, boolean first) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        BlockPos pos = player.getBlockPos();
        if (first) {
            BlueprintSelectionManager.setPos1(player.getUuid(), pos);
        } else {
            BlueprintSelectionManager.setPos2(player.getUuid(), pos);
        }
        source.sendFeedback(() -> Text.literal("Blueprint position " + (first ? "1" : "2") + " set to "
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".").formatted(Formatting.AQUA), false);
        return 1;
    }

    // ------------------------------------------------------------------ export

    private static int export(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        BlockPos pos1 = BlueprintSelectionManager.pos1(player.getUuid());
        BlockPos pos2 = BlueprintSelectionManager.pos2(player.getUuid());
        if (pos1 == null || pos2 == null) {
            source.sendError(Text.literal("Select both corners first: /blueprint pos1 and /blueprint pos2."));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        if (!BlueprintManager.isValidName(name)) {
            source.sendError(Text.literal("Invalid blueprint name (no / \\ or .. ; max 128 chars)."));
            return 0;
        }
        if (BlueprintManager.exists(name)) {
            source.sendError(Text.literal("A blueprint named '" + name
                    + "' already exists. Choose another name or delete blueprints/" + name + "/ first."));
            return 0;
        }

        try {
            BlueprintExporter.Result r = BlueprintExporter.export(source.getWorld(), pos1, pos2, name);
            source.sendFeedback(() -> Text.literal(String.format(
                    "Exported '%s': %d×%d×%d, %d blocks → blueprints/%s/",
                    name, r.width(), r.height(), r.depth(), r.blockCount(), name))
                    .formatted(Formatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            source.sendError(Text.literal("Export failed: " + e.getMessage()));
            return 0;
        }
    }

    // ------------------------------------------------------------------ export nbt (structure template)

    /** {@code /blueprint export <name> nbt [<version>] [overwrite]}; null version = this game's version. */
    private static int exportNbt(CommandContext<ServerCommandSource> ctx, String versionId, boolean overwrite) {
        ServerCommandSource source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (!BlueprintManager.isValidName(name)) {
            source.sendError(Text.literal("Invalid blueprint name."));
            return 0;
        }
        if (!BlueprintManager.exists(name)) {
            source.sendError(Text.literal("Blueprint '" + name + "' not found (expected blueprints/"
                    + name + "/metadata.json). Note: 'nbt' exports an existing blueprint folder."));
            return 0;
        }
        StructureVersion version = versionId == null ? StructureVersion.CURRENT
                : StructureVersion.byId(versionId).orElse(null);
        if (version == null) {
            source.sendError(Text.literal("Unknown Minecraft version '" + versionId
                    + "'. Supported: " + StructureVersion.idList()));
            return 0;
        }

        BlueprintMetadata meta;
        try {
            meta = BlueprintMetadata.read(BlueprintManager.metadataFile(name));
        } catch (IOException e) {
            source.sendError(Text.literal("Could not read blueprint metadata: " + e.getMessage()));
            return 0;
        }

        StructureNbtExporter.Result r;
        try {
            r = StructureNbtExporter.export(source.getServer(), name, meta, version, overwrite);
        } catch (StructureNbtExporter.OutputExistsException e) {
            source.sendError(Text.literal("Structure file " + e.getMessage() + " already exists (names that "
                    + "differ only in case/spacing share one file). To replace it: /blueprint export "
                    + name + " nbt " + version.id() + " overwrite"));
            return 0;
        } catch (IOException e) {
            source.sendError(Text.literal("NBT export failed: " + e.getMessage()));
            return 0;
        } catch (Exception e) {
            // A malformed blueprint must never crash the game — report and log instead.
            BlueprintBuilderMod.LOGGER.error("[Blueprint] NBT export of '{}' failed", name, e);
            source.sendError(Text.literal("NBT export failed unexpectedly: " + e));
            return 0;
        }

        sendNbtFeedback(source, name, version, r);
        return (int) Math.min(Integer.MAX_VALUE, r.blocksWritten());
    }

    private static void sendNbtFeedback(ServerCommandSource source, String name, StructureVersion version,
                                        StructureNbtExporter.Result r) {
        StringBuilder msg = new StringBuilder(String.format(
                "Exported '%s' for Minecraft %s: %d blocks, %d palette entries → generated/%s/structures/%s",
                name, version.id(), r.blocksWritten(), r.paletteSize(),
                BlueprintBuilderMod.MOD_ID, r.file().getFileName()));
        if (r.substitutedBlocks() > 0 || r.droppedBlocks() > 0) {
            msg.append(String.format(" — %d blocks substituted, %d dropped for %s compatibility",
                    r.substitutedBlocks(), r.droppedBlocks(), version.id()));
            int shown = 0;
            for (Map.Entry<String, String> s : r.substitutions().entrySet()) {
                msg.append(shown++ == 0 ? ": " : ", ");
                if (shown > 4) {
                    msg.append("…");
                    break;
                }
                msg.append(s.getKey().replace("minecraft:", "")).append(" → ")
                   .append(s.getValue().replace("minecraft:", ""));
            }
        }
        if (r.invalidCells() > 0 || r.layersMissing() > 0 || r.outOfBoundsCells() > 0
                || r.layersTruncated() > 0) {
            msg.append(String.format(" (skipped: %d invalid cells, %d missing layers, %d out-of-bounds "
                            + "cells, %d layers with extra rows)",
                    r.invalidCells(), r.layersMissing(), r.outOfBoundsCells(), r.layersTruncated()));
        }
        final String summary = msg.toString();
        source.sendFeedback(() -> Text.literal(summary).formatted(Formatting.GREEN), true);

        String hint;
        if (version.isCurrent()) {
            hint = "Place it now: /place template " + r.structureId()
                    + "  (or load it with a Structure Block)";
        } else if (version.hasPlaceCommand()) {
            hint = "Copy the file into a " + version.id() + " world's generated/ folder (or a datapack's data/"
                    + BlueprintBuilderMod.MOD_ID + "/" + version.datapackFolder() + "/), then: /place template "
                    + r.structureId();
        } else {
            hint = "Copy the file into a " + version.id() + " world's generated/ folder, then load it with a "
                    + "Structure Block (LOAD mode, name: " + r.structureId() + ")";
        }
        final String hintMsg = hint;
        source.sendFeedback(() -> Text.literal(hintMsg).formatted(Formatting.AQUA), false);
    }

    // ------------------------------------------------------------------ import (normal / scaffold / bot)

    private static int runImport(CommandContext<ServerCommandSource> ctx, boolean hasPos, Mode mode, int botRate)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (!BlueprintManager.isValidName(name)) {
            source.sendError(Text.literal("Invalid blueprint name."));
            return 0;
        }
        if (!BlueprintManager.exists(name)) {
            source.sendError(Text.literal("Blueprint '" + name + "' not found (expected blueprints/"
                    + name + "/metadata.json)."));
            return 0;
        }

        BlueprintMetadata meta;
        try {
            meta = BlueprintMetadata.read(BlueprintManager.metadataFile(name));
        } catch (IOException e) {
            source.sendError(Text.literal("Could not read blueprint metadata: " + e.getMessage()));
            return 0;
        }

        BlockPos origin;
        if (hasPos) {
            PosArgument posArg = ctx.getArgument("pos", PosArgument.class);
            origin = posArg.toAbsoluteBlockPos(source);
        } else {
            origin = source.getPlayerOrThrow().getBlockPos();
        }

        return switch (mode) {
            case NORMAL -> runNormal(source, name, meta, origin);
            case SCAFFOLD -> runScaffold(source, name, meta, origin);
            case BOT -> runBot(source, name, meta, origin, botRate);
        };
    }

    private static int runNormal(ServerCommandSource source, String name, BlueprintMetadata meta, BlockPos origin) {
        ServerWorld world = source.getWorld();
        try {
            BlueprintImporter.Result r = BlueprintImporter.importBlueprint(world, origin, name, meta);
            source.sendFeedback(() -> Text.literal(String.format(
                    "Imported '%s' at %s: placed %d blocks (%d skipped%s).",
                    name, origin.toShortString(), r.placed(), r.skipped(),
                    r.layersMissing() > 0 ? ", " + r.layersMissing() + " layers missing" : ""))
                    .formatted(Formatting.GOLD), true);
            return 1;
        } catch (IOException e) {
            source.sendError(Text.literal("Import failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int runScaffold(ServerCommandSource source, String name, BlueprintMetadata meta, BlockPos origin)
            throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        if (!ServerPlayNetworking.canSend(player, GhostBlueprintPayload.ID)) {
            source.sendError(Text.literal(
                    "Scaffold needs Blueprint Builder installed on your client to show the ghost preview."));
            return 0;
        }
        LinkedHashMap<BlockPos, BlockState> placements = readPlacementsOrError(source, name, meta, origin);
        if (placements == null) {
            return 0;
        }
        ScaffoldManager.start(player, placements);
        int count = placements.size();
        source.sendFeedback(() -> Text.literal(String.format(
                "Scaffold for '%s' at %s: showing %d ghost blocks. Build them to make the ghosts vanish; "
                        + "/blueprint cancel to clear.", name, origin.toShortString(), count))
                .formatted(Formatting.AQUA), false);
        return count;
    }

    private static int runBot(ServerCommandSource source, String name, BlueprintMetadata meta, BlockPos origin,
                              int botRate) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        LinkedHashMap<BlockPos, BlockState> placements = readPlacementsOrError(source, name, meta, origin);
        if (placements == null) {
            return 0;
        }
        BotManager.start(player.getUuid(), new BotSession(player, source.getWorld(), placements, botRate));
        int count = placements.size();
        source.sendFeedback(() -> Text.literal(String.format(
                "Bot building '%s' at %s: %d blocks at %d/s. Look around freely — /blueprint cancel to stop.",
                name, origin.toShortString(), count, botRate))
                .formatted(Formatting.LIGHT_PURPLE), true);
        return count;
    }

    private static LinkedHashMap<BlockPos, BlockState> readPlacementsOrError(ServerCommandSource source, String name,
                                                                            BlueprintMetadata meta, BlockPos origin) {
        try {
            LinkedHashMap<BlockPos, BlockState> placements = BlueprintImporter.readPlacements(origin, name, meta);
            if (placements.isEmpty()) {
                source.sendError(Text.literal("Blueprint '" + name + "' has no solid blocks to build."));
                return null;
            }
            return placements;
        } catch (IOException e) {
            source.sendError(Text.literal("Could not read blueprint: " + e.getMessage()));
            return null;
        }
    }

    // ------------------------------------------------------------------ cancel

    private static int cancel(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        boolean stoppedBot = false;
        if (player != null) {
            stoppedBot = BotManager.cancel(player.getUuid());
            ScaffoldManager.cancel(player.getUuid());
            if (ServerPlayNetworking.canSend(player, GhostBlueprintPayload.ID)) {
                ServerPlayNetworking.send(player, new GhostBlueprintPayload(true, new HashMap<>()));
            }
        }
        final boolean bot = stoppedBot;
        source.sendFeedback(() -> Text.literal("Cancelled blueprint build"
                + (bot ? " (stopped the bot)" : "") + " and cleared any scaffold ghost.")
                .formatted(Formatting.RED), true);
        return 1;
    }

    // ------------------------------------------------------------------ suggestions

    private static CompletableFuture<Suggestions> suggestBlueprints(CommandContext<ServerCommandSource> ctx,
                                                                    SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(BlueprintManager.list(), builder);
    }

    private static CompletableFuture<Suggestions> suggestVersions(CommandContext<ServerCommandSource> ctx,
                                                                  SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(StructureVersion.ids(), builder);
    }
}
