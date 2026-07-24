package dev.minecp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MinecpConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String websocket_host = "127.0.0.1";
    public int websocket_port = 8765;
    public String fake_player_name = "MINECP_Agent";

    public static MinecpConfig load(Logger logger) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("minecp.json");
        MinecpConfig config = new MinecpConfig();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                MinecpConfig loaded = GSON.fromJson(reader, MinecpConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (IOException | RuntimeException e) {
                logger.error("Cannot read {}; using defaults", path, e);
            }
        } else {
            try {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(config, writer);
                }
            } catch (IOException e) {
                logger.warn("Cannot write default config {}", path, e);
            }
        }

        if (config.websocket_host == null || config.websocket_host.isBlank()) {
            config.websocket_host = "127.0.0.1";
        }
        if (config.websocket_port < 1 || config.websocket_port > 65535) {
            config.websocket_port = 8765;
        }
        if (config.fake_player_name == null || !config.fake_player_name.matches("[A-Za-z0-9_]{1,16}")) {
            config.fake_player_name = "MINECP_Agent";
        }
        return config;
    }

    public String websocketUri() {
        return "ws://" + websocket_host + ":" + websocket_port;
    }
}
