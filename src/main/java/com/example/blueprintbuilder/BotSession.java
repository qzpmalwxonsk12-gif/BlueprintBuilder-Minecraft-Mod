package com.example.blueprintbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Drives the "bot" build mode for one player. Each tick it:
 * <ol>
 *   <li>walks the player a small step toward the current build frontier (so it animates as walking, not
 *       teleport-hopping), keeping the player's <b>look direction free</b> &mdash; only the body is moved;</li>
 *   <li>places blocks at the chosen rate (blocks/second), swinging the player's arm.</li>
 * </ol>
 *
 * <p>Movement uses relative-rotation teleports ({@link PositionFlag#X_ROT}/{@link PositionFlag#Y_ROT} with a
 * zero delta, via {@link PositionFlag#ROT}) so the server never overwrites where the player is looking
 * &mdash; the camera stays under the player's control while the bot carries them around the build.</p>
 */
public final class BotSession {

    /** Relative-rotation teleport set {X_ROT, Y_ROT}: set absolute position, leave the camera untouched. */
    private static final Set<PositionFlag> KEEP_LOOK = PositionFlag.ROT;

    /** Avatar stroll speed in blocks/tick (~4.4 blocks/s) — purely cosmetic, independent of place rate. */
    private static final double WALK_SPEED = 0.22;
    /** How far in front of the frontier block the avatar tries to stand. */
    private static final double STAND_DISTANCE = 1.8;

    public enum Status { RUNNING, DONE }

    private final ServerPlayerEntity player;
    private final ServerWorld world;
    private final List<Map.Entry<BlockPos, BlockState>> steps;
    private final Vec3d viewerSide; // horizontal unit vector from the build toward where the player started
    private final double blocksPerTick;

    private int placeIndex = 0;
    private double accumulator = 0.0;

    public BotSession(ServerPlayerEntity player, ServerWorld world, Map<BlockPos, BlockState> placements,
                      int blocksPerSecond) {
        this.player = player;
        this.world = world;
        this.steps = new ArrayList<>(placements.entrySet());
        this.blocksPerTick = blocksPerSecond / 20.0;

        Vec3d firstCenter = Vec3d.ofCenter(this.steps.get(0).getKey());
        Vec3d toPlayer = player.getPos().subtract(firstCenter);
        Vec3d flat = new Vec3d(toPlayer.x, 0, toPlayer.z);
        this.viewerSide = flat.lengthSquared() < 1.0e-4 ? new Vec3d(0, 0, 1) : flat.normalize();
    }

    public int total() {
        return steps.size();
    }

    public int placed() {
        return placeIndex;
    }

    public ServerPlayerEntity player() {
        return player;
    }

    public Status tick() {
        if (player.isRemoved() || player.isDisconnected()) {
            return Status.DONE;
        }
        if (placeIndex >= steps.size()) {
            return Status.DONE;
        }

        // 1. Walk the body toward the current frontier (cosmetic), keeping the camera free.
        walkToward(steps.get(placeIndex).getKey());

        // 2. Place blocks at the configured rate (may be several per tick at high rates, or one every few
        //    ticks at low rates).
        accumulator += blocksPerTick;
        boolean placedAny = false;
        while (accumulator >= 1.0 && placeIndex < steps.size()) {
            Map.Entry<BlockPos, BlockState> step = steps.get(placeIndex++);
            place(step.getKey(), step.getValue());
            accumulator -= 1.0;
            placedAny = true;
        }
        if (placedAny) {
            player.swingHand(Hand.MAIN_HAND, true);
        }
        return Status.RUNNING;
    }

    private void walkToward(BlockPos frontier) {
        Vec3d center = Vec3d.ofCenter(frontier);
        Vec3d target = new Vec3d(
                center.x + viewerSide.x * STAND_DISTANCE,
                frontier.getY(),
                center.z + viewerSide.z * STAND_DISTANCE);

        Vec3d current = player.getPos();
        Vec3d delta = target.subtract(current);
        double dist = delta.length();
        Vec3d next = dist <= WALK_SPEED ? target : current.add(delta.normalize().multiply(WALK_SPEED));

        // Yaw/pitch are passed as 0 but flagged relative, so the player's current look is preserved.
        player.networkHandler.requestTeleport(next.x, next.y, next.z, 0.0f, 0.0f, KEEP_LOOK);
    }

    private void place(BlockPos pos, BlockState state) {
        if (world.isOutOfHeightLimit(pos)) {
            return;
        }
        world.setBlockState(pos, state, Block.NOTIFY_ALL);
        if (!state.isAir()) {
            world.playSound(null, pos, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 1.0f);
        }
    }
}
