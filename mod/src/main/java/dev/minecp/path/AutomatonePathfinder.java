package dev.minecp.path;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Optional reflection adapter for Automatone 0.11.0 (Minecraft 1.20.1).
 * Reflection keeps the permitted fallback build resolvable when the Ladysnake
 * Maven repository is unavailable. No judgment or route policy is implemented
 * here; all route calculation remains inside Automatone.
 */
public final class AutomatonePathfinder implements IPathfinder {
    private final Supplier<ServerPlayerEntity> playerSupplier;
    private final IPathfinder fallback;
    private final Method getProvider;
    private final Method getBaritone;
    private final Constructor<?> goalBlockConstructor;
    private Object baritone;
    private BlockPos target;
    private boolean fallbackActive;

    private AutomatonePathfinder(Supplier<ServerPlayerEntity> playerSupplier, IPathfinder fallback) throws ReflectiveOperationException {
        this.playerSupplier = playerSupplier;
        this.fallback = fallback;
        Class<?> api = Class.forName("baritone.api.BaritoneAPI");
        Class<?> providerClass = Class.forName("baritone.api.IBaritoneProvider");
        Class<?> goalBlock = Class.forName("baritone.api.pathing.goals.GoalBlock");
        this.getProvider = api.getMethod("getProvider");
        this.getBaritone = providerClass.getMethod("getBaritone", net.minecraft.entity.Entity.class);
        this.goalBlockConstructor = goalBlock.getConstructor(int.class, int.class, int.class);
    }

    public static IPathfinder tryCreate(
            Supplier<ServerPlayerEntity> playerSupplier,
            IPathfinder fallback,
            Logger logger
    ) {
        try {
            AutomatonePathfinder adapter = new AutomatonePathfinder(playerSupplier, fallback);
            logger.info("Automatone 0.11.0 API detected; movement will use Automatone");
            return adapter;
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.warn(
                    "Automatone 0.11.0 API is unavailable; using {}. See mod/README.md.",
                    fallback.implementationName()
            );
            return fallback;
        }
    }

    @Override
    public void start(ServerPlayerEntity player, BlockPos target) {
        this.target = target.toImmutable();
        this.fallbackActive = false;
        try {
            Object provider = getProvider.invoke(null);
            baritone = getBaritone.invoke(provider, player);
            if (baritone == null) {
                startFallback(player, target);
                return;
            }
            Object process = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);
            Object goal = goalBlockConstructor.newInstance(target.getX(), target.getY(), target.getZ());
            Method setGoalAndPath = findOneArgumentMethod(process.getClass(), "setGoalAndPath");
            setGoalAndPath.invoke(process, goal);
        } catch (ReflectiveOperationException | RuntimeException e) {
            startFallback(player, target);
        }
    }

    @Override
    public Status tick(ServerPlayerEntity player) {
        if (fallbackActive) {
            return fallback.tick(player);
        }
        if (target == null) {
            return Status.IDLE;
        }
        if (player.getBlockPos().isWithinDistance(target, 1.5)) {
            cancel(player);
            return Status.REACHED;
        }
        try {
            Object process = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);
            boolean active = (boolean) process.getClass().getMethod("isActive").invoke(process);
            return active ? Status.RUNNING : Status.FAILED;
        } catch (ReflectiveOperationException | RuntimeException e) {
            startFallback(player, target);
            return Status.RUNNING;
        }
    }

    @Override
    public void cancel(ServerPlayerEntity player) {
        if (fallbackActive) {
            fallback.cancel(player);
        } else if (baritone != null) {
            try {
                baritone.getClass().getMethod("getPathingBehavior").invoke(baritone)
                        .getClass().getMethod("cancelEverything").invoke(
                                baritone.getClass().getMethod("getPathingBehavior").invoke(baritone)
                        );
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Cancellation is best effort; velocity is always cleared below.
            }
        }
        player.setVelocity(0.0, player.getVelocity().y, 0.0);
        player.velocityModified = true;
        target = null;
        fallbackActive = false;
    }

    @Override
    public String implementationName() {
        return "Automatone 0.11.0";
    }

    private void startFallback(ServerPlayerEntity player, BlockPos target) {
        fallbackActive = true;
        fallback.start(player, target);
    }

    private static Method findOneArgumentMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
