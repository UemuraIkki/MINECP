package dev.minecp.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class BridgeWebSocketClient {
    private final MinecraftServer server;
    private final URI uri;
    private final Consumer<JsonObject> commandConsumer;
    private final Runnable disconnectConsumer;
    private final Runnable reconnectConsumer;
    private final Logger logger;
    private final AtomicLong sequence = new AtomicLong();
    private final Queue<String> pending = new ArrayDeque<>();
    private final ScheduledExecutorService reconnectExecutor;
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private volatile Client client;
    private volatile boolean stopping;
    private volatile long backoffSeconds = 1L;

    public BridgeWebSocketClient(
            MinecraftServer server,
            String uri,
            Consumer<JsonObject> commandConsumer,
            Runnable disconnectConsumer,
            Runnable reconnectConsumer,
            Logger logger
    ) {
        this.server = server;
        this.commandConsumer = commandConsumer;
        this.disconnectConsumer = disconnectConsumer;
        this.reconnectConsumer = reconnectConsumer;
        this.logger = logger;
        try {
            this.uri = new URI(uri);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid WebSocket URI: " + uri, e);
        }
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "minecp-websocket-reconnect");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduleConnect(0L);
    }

    /**
     * Adds the exact common envelope and sends/queues one schema message.
     */
    public void send(String messageType, Consumer<JsonObject> payloadWriter) {
        JsonObject message = new JsonObject();
        message.addProperty("message_type", messageType);
        message.addProperty("timestamp_ms", System.currentTimeMillis());
        message.addProperty("seq", sequence.getAndIncrement());
        payloadWriter.accept(message);
        // Periodic/live observations become stale while disconnected. Results
        // and events are durable for the process lifetime; reconnect sends one
        // fresh observation after this durable queue is flushed.
        sendSerialized(message.toString(), !"observation".equals(messageType));
    }

    public void close() {
        stopping = true;
        reconnectExecutor.shutdownNow();
        Client active = client;
        if (active != null) {
            active.close();
        }
    }

    private void sendSerialized(String text, boolean queueWhenClosed) {
        Client active = client;
        if (active != null && active.isOpen()) {
            active.send(text);
            return;
        }
        if (!queueWhenClosed) {
            return;
        }
        synchronized (pending) {
            pending.add(text);
        }
    }

    private void scheduleConnect(long delaySeconds) {
        if (stopping || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        reconnectExecutor.schedule(() -> {
            reconnectScheduled.set(false);
            if (stopping) {
                return;
            }
            Client next = new Client(uri);
            client = next;
            logger.info("Connecting MINECP bridge at {}", uri);
            next.connect();
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void scheduleRetry() {
        long delay = backoffSeconds;
        backoffSeconds = Math.min(60L, backoffSeconds * 2L);
        logger.warn("Bridge disconnected; retrying in {} second(s)", delay);
        scheduleConnect(delay);
    }

    private void flushPending() {
        Client active = client;
        if (active == null || !active.isOpen()) {
            return;
        }
        synchronized (pending) {
            while (!pending.isEmpty() && active.isOpen()) {
                active.send(pending.remove());
            }
        }
    }

    private final class Client extends WebSocketClient {
        private boolean opened;

        private Client(URI serverUri) {
            super(serverUri);
            setConnectionLostTimeout(20);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            opened = true;
            backoffSeconds = 1L;
            logger.info("Connected to MINECP bridge at {}", uri);
            flushPending();
            server.execute(reconnectConsumer);
        }

        @Override
        public void onMessage(String text) {
            try {
                JsonObject message = JsonParser.parseString(text).getAsJsonObject();
                server.execute(() -> commandConsumer.accept(message));
            } catch (RuntimeException e) {
                logger.warn("Discarded malformed bridge JSON", e);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            if (!stopping) {
                if (opened) {
                    server.execute(disconnectConsumer);
                }
                scheduleRetry();
            }
        }

        @Override
        public void onError(Exception exception) {
            if (!stopping) {
                logger.warn("Bridge WebSocket error: {}", exception.toString());
                if (!isOpen()) {
                    scheduleRetry();
                }
            }
        }
    }
}
