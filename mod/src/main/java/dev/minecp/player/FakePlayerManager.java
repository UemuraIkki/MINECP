package dev.minecp.player;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Owns exactly one directly constructed fake ServerPlayerEntity.
 */
public final class FakePlayerManager {
    public interface Listener {
        void onDeath(ServerPlayerEntity player, String cause);

        void onRespawned(ServerPlayerEntity player);
    }

    private final MinecraftServer server;
    private final String playerName;
    private final Logger logger;
    private Listener listener;
    private ServerPlayerEntity player;
    private boolean respawning;

    public FakePlayerManager(MinecraftServer server, String playerName, Logger logger) {
        this.server = server;
        this.playerName = playerName;
        this.logger = logger;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public ServerPlayerEntity player() {
        return player;
    }

    public void spawnInitial() {
        if (player != null && !player.isRemoved()) {
            return;
        }
        ServerPlayerEntity existing = server.getPlayerManager().getPlayer(playerName);
        if (existing != null) {
            throw new IllegalStateException("Configured fake-player name is already online: " + playerName);
        }
        player = createAndRegister(false);
        logger.info("Spawned fake player {} at {}", playerName, player.getBlockPos());
    }

    public void tick() {
        if (player == null || respawning || (!player.isDead() && player.getHealth() > 0.0F)) {
            return;
        }

        respawning = true;
        ServerPlayerEntity dead = player;
        String cause = dead.getDamageTracker().getDeathMessage().getString();
        if (listener != null) {
            listener.onDeath(dead, cause);
        }

        server.getPlayerManager().remove(dead);
        player = createAndRegister(true);
        respawning = false;
        logger.info("Respawned fake player {} at {}", playerName, player.getBlockPos());
        if (listener != null) {
            listener.onRespawned(player);
        }
    }

    private ServerPlayerEntity createAndRegister(boolean respawn) {
        ServerWorld world = server.getOverworld();
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, playerName);

        // Required direct Carpet-style construction; no fake-player abstraction library.
        ServerPlayerEntity created = new ServerPlayerEntity(server, world, profile);
        BlockPos spawn = world.getSpawnPos();
        created.changeGameMode(GameMode.SURVIVAL);
        server.getPlayerManager().onPlayerConnect(new FakeClientConnection(), created);
        if (respawn) {
            // onPlayerConnect may load the just-saved death NBT. Vanilla
            // respawn semantics require a live body at the world spawn.
            created.refreshPositionAndAngles(
                    spawn.getX() + 0.5,
                    spawn.getY(),
                    spawn.getZ() + 0.5,
                    0.0F,
                    0.0F
            );
            created.setHealth(created.getMaxHealth());
            created.getHungerManager().setFoodLevel(20);
            created.clearStatusEffects();
            created.fallDistance = 0.0F;
        }
        return created;
    }
}
