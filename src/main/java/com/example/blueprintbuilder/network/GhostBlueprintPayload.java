package com.example.blueprintbuilder.network;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.blueprintbuilder.BlueprintBuilderMod;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Server &rarr; client packet describing "scaffold" ghost blocks (target block states keyed by world
 * position).
 *
 * <ul>
 *   <li>{@code replace == true}  &mdash; clear the client's ghosts and use exactly this set (initial send /
 *       cancel with an empty map).</li>
 *   <li>{@code replace == false} &mdash; merge these into the existing set (streaming more in as the player
 *       builds).</li>
 * </ul>
 *
 * <p>Block states are sent compactly via the global block-state id palette
 * ({@link Block#getRawIdFromState}/{@link Block#getStateFromRawId}).</p>
 */
public record GhostBlueprintPayload(boolean replace, Map<BlockPos, BlockState> blocks) implements CustomPayload {

    public static final CustomPayload.Id<GhostBlueprintPayload> ID =
            new CustomPayload.Id<>(Identifier.of(BlueprintBuilderMod.MOD_ID, "ghost_blueprint"));

    public static final PacketCodec<PacketByteBuf, GhostBlueprintPayload> CODEC =
            PacketCodec.ofStatic(GhostBlueprintPayload::write, GhostBlueprintPayload::read);

    private static void write(PacketByteBuf buf, GhostBlueprintPayload payload) {
        buf.writeBoolean(payload.replace);
        buf.writeVarInt(payload.blocks.size());
        payload.blocks.forEach((pos, state) -> {
            buf.writeBlockPos(pos);
            buf.writeVarInt(Block.getRawIdFromState(state));
        });
    }

    private static GhostBlueprintPayload read(PacketByteBuf buf) {
        boolean replace = buf.readBoolean();
        int count = buf.readVarInt();
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>(Math.max(16, count));
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            BlockState state = Block.getStateFromRawId(buf.readVarInt());
            blocks.put(pos, state);
        }
        return new GhostBlueprintPayload(replace, blocks);
    }

    @Override
    public CustomPayload.Id<GhostBlueprintPayload> getId() {
        return ID;
    }
}
