package dev.minecp;

import dev.minecp.config.MinecpConfig;
import dev.minecp.observation.NearbyScanner;
import dev.minecp.observation.ObservationService;
import dev.minecp.path.AutomatonePathfinder;
import dev.minecp.path.IPathfinder;
import dev.minecp.path.StraightLinePathfinder;
import dev.minecp.player.FakePlayerManager;
import dev.minecp.protocol.BridgeWebSocketClient;
import dev.minecp.skill.SkillManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinecpMod implements ModInitializer {
    public static final String MOD_ID = "minecp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Runtime runtime;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (runtime != null) {
                runtime.close();
                runtime = null;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (runtime != null && runtime.server == server) {
                runtime.tick();
            }
        });
    }

    private void onServerStarted(MinecraftServer server) {
        MinecpConfig config = MinecpConfig.load(LOGGER);
        runtime = new Runtime(server, config);
        runtime.start();
    }

    private static final class Runtime implements FakePlayerManager.Listener {
        private final MinecraftServer server;
        private final FakePlayerManager fakePlayers;
        private final NearbyScanner nearbyScanner;
        private final ObservationService observations;
        private final BridgeWebSocketClient bridge;
        private final SkillManager skills;
        private long ticks;

        private Runtime(MinecraftServer server, MinecpConfig config) {
            this.server = server;
            this.fakePlayers = new FakePlayerManager(server, config.fake_player_name, LOGGER);
            this.nearbyScanner = new NearbyScanner();

            final SkillManager[] skillHolder = new SkillManager[1];
            this.observations = new ObservationService(
                    fakePlayers::player,
                    nearbyScanner,
                    () -> skillHolder[0],
                    LOGGER
            );
            this.bridge = new BridgeWebSocketClient(
                    server,
                    config.websocketUri(),
                    json -> skillHolder[0].accept(json),
                    () -> skillHolder[0].onDisconnected(),
                    () -> observations.send("reconnected"),
                    LOGGER
            );

            IPathfinder fallback = new StraightLinePathfinder();
            IPathfinder pathfinder = AutomatonePathfinder.tryCreate(fakePlayers::player, fallback, LOGGER);
            this.skills = new SkillManager(fakePlayers::player, pathfinder, bridge, observations, LOGGER);
            skillHolder[0] = skills;
            observations.setBridge(bridge);
            fakePlayers.setListener(this);
        }

        private void start() {
            fakePlayers.spawnInitial();
            bridge.start();
        }

        private void tick() {
            fakePlayers.tick();
            ServerPlayerEntity player = fakePlayers.player();
            if (player == null) {
                return;
            }
            nearbyScanner.tick(player);
            skills.tick();
            observations.tickInterruptMonitors();
            ticks++;
            if (ticks % 100L == 0L) {
                observations.send("periodic");
            }
        }

        @Override
        public void onDeath(ServerPlayerEntity player, String cause) {
            skills.onAgentDied();
            observations.sendDeathEvent(player, cause);
        }

        @Override
        public void onRespawned(ServerPlayerEntity player) {
            nearbyScanner.reset();
            observations.sendRespawnedEvent(player);
        }

        private void close() {
            skills.onDisconnected();
            bridge.close();
        }
    }
}
