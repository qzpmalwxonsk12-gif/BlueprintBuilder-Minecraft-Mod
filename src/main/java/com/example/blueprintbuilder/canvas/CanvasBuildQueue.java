package com.example.blueprintbuilder.canvas;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * A rate-limited queue that places Canvas Blocks and assigns each one its RGB colour, a few thousand per
 * tick, so importing a large image doesn't freeze the server. Independent from the Blueprint Builder's
 * queue.
 */
public final class CanvasBuildQueue {

    /** Canvas blocks placed per server tick. */
    private static final int RATE_PER_TICK = 1024;

    /** One pending canvas placement: a position and the colour to give its block entity. */
    public record Placement(ServerWorld world, BlockPos pos, int red, int green, int blue) {}

    private static final Deque<Placement> QUEUE = new ArrayDeque<>();

    private CanvasBuildQueue() {}

    public static void enqueue(ServerWorld world, BlockPos pos, int red, int green, int blue) {
        QUEUE.add(new Placement(world, pos, red, green, blue));
    }

    public static int remaining() {
        return QUEUE.size();
    }

    public static int clear() {
        int n = QUEUE.size();
        QUEUE.clear();
        return n;
    }

    /** Called at the end of every server tick: place up to {@link #RATE_PER_TICK} canvas blocks. */
    public static void onEndTick() {
        int budget = RATE_PER_TICK;
        while (budget-- > 0) {
            Placement p = QUEUE.poll();
            if (p == null) {
                break;
            }
            if (p.world().isOutOfHeightLimit(p.pos())) {
                continue;
            }
            // Place the block (NOTIFY_LISTENERS so the client creates the block entity), then colour it.
            p.world().setBlockState(p.pos(), CanvasRegistry.CANVAS_BLOCK.getDefaultState(),
                    Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            BlockEntity be = p.world().getBlockEntity(p.pos());
            if (be instanceof CanvasBlockEntity canvas) {
                canvas.setColor(p.red(), p.green(), p.blue());
            }
        }
    }
}
