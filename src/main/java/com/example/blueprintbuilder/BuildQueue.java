package com.example.blueprintbuilder;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * A global, rate-limited queue of block placements.
 *
 * <p>The {@code /build} command fills this queue all at once; the server-tick handler in
 * {@link BlueprintBuilderMod} drains up to {@link #ratePerTick} placements each tick. That is how the
 * "blocks per tick" rate is enforced. Everything here runs on the server thread (commands and tick
 * events both execute there), so no synchronization is needed.</p>
 */
public final class BuildQueue {

    public static final int MAX_RATE = 10_000;
    public static final int MIN_RATE = 1;
    private static final int DEFAULT_RATE = 100;

    /** One pending block placement. */
    public record Placement(ServerWorld world, BlockPos pos, BlockState state) {}

    private static final Deque<Placement> QUEUE = new ArrayDeque<>();
    private static int ratePerTick = DEFAULT_RATE;

    private BuildQueue() {}

    public static void enqueue(ServerWorld world, BlockPos pos, BlockState state) {
        QUEUE.add(new Placement(world, pos, state));
    }

    /** Sets the rate, clamped to [{@value #MIN_RATE}, {@value #MAX_RATE}]. Returns the value actually set. */
    public static int setRate(int requested) {
        ratePerTick = Math.max(MIN_RATE, Math.min(MAX_RATE, requested));
        return ratePerTick;
    }

    public static int getRate() {
        return ratePerTick;
    }

    public static int remaining() {
        return QUEUE.size();
    }

    /** Drops every queued placement. Returns how many were discarded. */
    public static int clear() {
        int n = QUEUE.size();
        QUEUE.clear();
        return n;
    }

    /** Called at the end of every server tick: place up to {@code ratePerTick} blocks. */
    public static void onEndTick() {
        int budget = ratePerTick;
        while (budget-- > 0) {
            Placement p = QUEUE.poll();
            if (p == null) {
                break;
            }
            // Skip positions outside the world's build height (setBlockState would no-op anyway).
            if (p.world().isOutOfHeightLimit(p.pos())) {
                continue;
            }
            // NOTIFY_LISTENERS (flag 2) updates clients without triggering neighbor/redstone/physics
            // cascades, which keeps large builds fast and prevents sand/gravel/water from reacting
            // mid-build. FORCE_STATE (flag 16) skips some placement side effects.
            p.world().setBlockState(p.pos(), p.state(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
        }
    }
}
