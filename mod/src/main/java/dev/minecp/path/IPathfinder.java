package dev.minecp.path;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Narrow movement boundary. Automatone and the deterministic fallback both
 * implement this contract; skill code contains no route-selection logic.
 */
public interface IPathfinder {
    enum Status {
        IDLE,
        RUNNING,
        REACHED,
        FAILED
    }

    void start(ServerPlayerEntity player, BlockPos target);

    Status tick(ServerPlayerEntity player);

    void cancel(ServerPlayerEntity player);

    String implementationName();
}
