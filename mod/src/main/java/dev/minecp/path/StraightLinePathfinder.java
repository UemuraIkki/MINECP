package dev.minecp.path;

import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Fallback used when Automatone is absent or its reflective API detection
 * fails (see {@link AutomatonePathfinder}): walk directly toward the
 * requested coordinate and apply a vanilla-height jump when the next foot
 * block is obstructed. It deliberately does not search around cliffs,
 * walls, or hazards; enable Automatone (`-Pwith_automatone=true`, and place
 * it in a production server's mods/) for real pathfinding.
 */
public final class StraightLinePathfinder implements IPathfinder {
    private BlockPos target;
    private int ticks;

    @Override
    public void start(ServerPlayerEntity player, BlockPos target) {
        this.target = target.toImmutable();
        this.ticks = 0;
    }

    @Override
    public Status tick(ServerPlayerEntity player) {
        if (target == null) {
            return Status.IDLE;
        }

        Vec3d destination = Vec3d.ofBottomCenter(target);
        Vec3d delta = destination.subtract(player.getPos());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horizontal <= 1.25 && Math.abs(delta.y) <= 1.5) {
            stop(player);
            return Status.REACHED;
        }
        if (++ticks > 20 * 180) {
            stop(player);
            return Status.FAILED;
        }

        double speed = 0.23;
        double velocityX = horizontal < 0.001 ? 0.0 : delta.x / horizontal * speed;
        double velocityZ = horizontal < 0.001 ? 0.0 : delta.z / horizontal * speed;
        player.setSprinting(true);
        player.setYaw((float) (MathHelper.atan2(-velocityX, velocityZ) * 180.0 / Math.PI));
        player.setVelocity(velocityX, player.getVelocity().y, velocityZ);
        player.velocityModified = true;

        BlockPos ahead = player.getBlockPos().add(
                (int) Math.signum(velocityX),
                0,
                (int) Math.signum(velocityZ)
        );
        BlockState atFeet = player.getServerWorld().getBlockState(ahead);
        if (player.isOnGround() && (delta.y > 0.6 || !atFeet.getCollisionShape(player.getServerWorld(), ahead).isEmpty())) {
            player.addVelocity(0.0, 0.42, 0.0);
            player.velocityModified = true;
        }
        return Status.RUNNING;
    }

    @Override
    public void cancel(ServerPlayerEntity player) {
        stop(player);
    }

    @Override
    public String implementationName() {
        return "straight-line-plus-jump fallback";
    }

    private void stop(ServerPlayerEntity player) {
        target = null;
        player.setSprinting(false);
        player.setVelocity(0.0, player.getVelocity().y, 0.0);
        player.velocityModified = true;
    }
}
