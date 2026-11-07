package com.example.blueprintbuilder.canvas;

import com.mojang.serialization.MapCodec;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/**
 * A single block type whose displayed colour is stored per-placed-block in its {@link CanvasBlockEntity}.
 * Two adjacent canvas blocks can show completely different colours.
 *
 * <p>The block itself holds no colour — it only wires up the block entity and asks to be rendered with the
 * normal block model (so the client-side colour provider can tint it). All colour storage/persistence lives
 * in {@link CanvasBlockEntity}; the actual tinting happens in the client colour provider.</p>
 */
public class CanvasBlock extends BlockWithEntity {

    public static final MapCodec<CanvasBlock> CODEC = createCodec(CanvasBlock::new);

    public CanvasBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<CanvasBlock> getCodec() {
        return CODEC;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new CanvasBlockEntity(pos, state);
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        // BlockWithEntity defaults to INVISIBLE; we want the normal model so it can be tinted.
        return BlockRenderType.MODEL;
    }
}
