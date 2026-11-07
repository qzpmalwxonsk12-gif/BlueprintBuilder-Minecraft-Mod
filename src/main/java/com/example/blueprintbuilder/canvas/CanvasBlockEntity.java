package com.example.blueprintbuilder.canvas;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;

/**
 * Stores the per-block colour (red/green/blue, each 0–255) for a {@link CanvasBlock}.
 *
 * <p>Persistence: the colour is written to / read from NBT, so it survives world save+reload, chunk
 * unload+reload and game restarts. Client sync: {@link #toUpdatePacket()} and
 * {@link #toInitialChunkDataNbt} send the colour to clients, and on the client {@link #readNbt} schedules a
 * chunk re-render so a colour change shows immediately.</p>
 *
 * <p>This class is deliberately render-agnostic so future features (transparency, emissive, animation) can
 * extend the stored data without touching builder or CSV code.</p>
 */
public class CanvasBlockEntity extends BlockEntity {

    private static final String KEY_RED = "red";
    private static final String KEY_GREEN = "green";
    private static final String KEY_BLUE = "blue";

    private int red = 255;
    private int green = 255;
    private int blue = 255;

    public CanvasBlockEntity(BlockPos pos, BlockState state) {
        super(CanvasRegistry.CANVAS_BLOCK_ENTITY, pos, state);
    }

    /** Packed 0xRRGGBB for the colour provider / renderer. */
    public int getRgb() {
        return (red << 16) | (green << 8) | blue;
    }

    public int getRed() {
        return red;
    }

    public int getGreen() {
        return green;
    }

    public int getBlue() {
        return blue;
    }

    /** Sets the colour (values are clamped to 0–255), persists it, and syncs it to clients. */
    public void setColor(int r, int g, int b) {
        this.red = clamp(r);
        this.green = clamp(g);
        this.blue = clamp(b);
        markDirty();
        if (world != null) {
            // On the server this pushes the block-entity update to clients; on the client it schedules a
            // chunk re-render so the new tint shows right away.
            world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);
        nbt.putInt(KEY_RED, red);
        nbt.putInt(KEY_GREEN, green);
        nbt.putInt(KEY_BLUE, blue);
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);
        if (nbt.contains(KEY_RED)) {
            this.red = clamp(nbt.getInt(KEY_RED));
        }
        if (nbt.contains(KEY_GREEN)) {
            this.green = clamp(nbt.getInt(KEY_GREEN));
        }
        if (nbt.contains(KEY_BLUE)) {
            this.blue = clamp(nbt.getInt(KEY_BLUE));
        }
        // When a colour update arrives on the client, re-render this block's chunk section.
        if (world != null && world.isClient) {
            world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
        }
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registryLookup) {
        return createNbt(registryLookup);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
