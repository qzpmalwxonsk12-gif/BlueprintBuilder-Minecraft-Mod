package com.example.blueprintbuilder.network;

import com.example.blueprintbuilder.BlueprintBuilderMod;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client &rarr; server packet: "I have built (satisfied) {@code count} scaffold ghosts since I last told
 * you." The server uses it to stream that many more ghosts from the remaining blueprint, keeping the
 * number of rendered ghosts within the window while supporting arbitrarily large blueprints.
 */
public record GhostConsumedPayload(int count) implements CustomPayload {

    public static final CustomPayload.Id<GhostConsumedPayload> ID =
            new CustomPayload.Id<>(Identifier.of(BlueprintBuilderMod.MOD_ID, "ghost_consumed"));

    public static final PacketCodec<PacketByteBuf, GhostConsumedPayload> CODEC = PacketCodec.ofStatic(
            (buf, payload) -> buf.writeVarInt(payload.count),
            buf -> new GhostConsumedPayload(buf.readVarInt()));

    @Override
    public CustomPayload.Id<GhostConsumedPayload> getId() {
        return ID;
    }
}
