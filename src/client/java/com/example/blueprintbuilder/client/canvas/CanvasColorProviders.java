package com.example.blueprintbuilder.client.canvas;

import com.example.blueprintbuilder.canvas.CanvasBlockEntity;
import com.example.blueprintbuilder.canvas.CanvasRegistry;

import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;

import net.minecraft.block.entity.BlockEntity;

/**
 * Client-side tinting for the Canvas Block. The block's model uses a pure-white texture with tint index 0,
 * so multiplying it by the block entity's stored RGB reproduces that RGB exactly (white × colour = colour).
 *
 * <p>No mapping to wool/concrete/terracotta and no palette approximation — the displayed colour is the
 * stored colour (modulated only by world light, since this version is non-emissive).</p>
 */
public final class CanvasColorProviders {

    private static final int WHITE = 0xFFFFFF;

    private CanvasColorProviders() {}

    public static void register() {
        // Placed blocks: look up the block entity's colour at render/mesh time.
        ColorProviderRegistry.BLOCK.register((state, view, pos, tintIndex) -> {
            if (tintIndex == 0 && view != null && pos != null) {
                BlockEntity be = view.getBlockEntity(pos);
                if (be instanceof CanvasBlockEntity canvas) {
                    return canvas.getRgb();
                }
            }
            return WHITE;
        }, CanvasRegistry.CANVAS_BLOCK);

        // The item in inventory/hand has no stored colour — show it white.
        ColorProviderRegistry.ITEM.register((stack, tintIndex) -> WHITE, CanvasRegistry.CANVAS_BLOCK_ITEM);
    }
}
