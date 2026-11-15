package hub.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A websocket feed that reconnects forever with exponential backoff (1 s .. 60 s),
 * pings every 20 s and treats 90 s of silence as a dead connection.
 */
public abstract class WsFeed implements SmartLifecycle {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "feed-" + name());
        t.setDaemon(true);
        return t;
    });
    private volatile WebSocket ws;
    private volatile boolean running;
    private volatile long lastMessage;
    private volatile long backoffMs = 1000;
    private volatile long messages;
    private volatile String state = "stopped";

    /** Short name for logs and the status endpoint. */
    public abstract String name();

    protected abstract String url();

    /** Send subscriptions after connecting. */
    protected abstract void onOpen(WebSocket ws);

    /** Handle one complete text message. */
    protected abstract void onText(String msg);

    public String state() {
        return state;
    }

    public long messages() {
        return messages;
    }

    public long lastMessageAgeMs() {
        return lastMessage == 0 ? -1 : System.currentTimeMillis() - lastMessage;
    }

    @Override
    public void start() {
        running = true;
        exec.execute(this::connect);
        exec.scheduleAtFixedRate(this::watchdog, 20, 20, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running = false;
        WebSocket w = ws;
        if (w != null) w.abort();
        exec.shutdownNow();
        state = "stopped";
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void connect() {
        if (!running) return;
        state = "connecting";
        client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url()), new Listener())
                .whenComplete((w, err) -> {
                    if (err != null) {
                        log.warn("{}: connect failed: {}", name(), err.getMessage());
                        scheduleReconnect();
                    } else {
                        ws = w;
                        state = "open";
                        lastMessage = System.currentTimeMillis();
                        backoffMs = 1000;
                        log.info("{}: connected", name());
                        try {
                            onOpen(w);
                        } catch (Exception e) {
                            log.warn("{}: subscribe failed: {}", name(), e.getMessage());
                        }
                    }
                });
    }

    private void scheduleReconnect() {
        if (!running) return;
        state = "reconnecting";
        long wait = backoffMs;
        backoffMs = Math.min(60_000, backoffMs * 2);
        exec.schedule(this::connect, wait, TimeUnit.MILLISECONDS);
    }

    private void watchdog() {
        WebSocket w = ws;
        if (w == null || !"open".equals(state)) return;
        if (System.currentTimeMillis() - lastMessage > 90_000) {
            log.warn("{}: silent for 90 s, reconnecting", name());
            w.abort();
            ws = null;
            scheduleReconnect();
        } else {
            w.sendPing(ByteBuffer.wrap(new byte[]{1}));
        }
    }

    protected void send(WebSocket w, String text) {
        w.sendText(text, true);
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                lastMessage = System.currentTimeMillis();
                messages++;
                try {
                    WsFeed.this.onText(msg);
                } catch (Exception e) {
                    log.debug("{}: bad message: {}", name(), e.getMessage());
                }
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket w, ByteBuffer message) {
            lastMessage = System.currentTimeMillis();
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
            log.warn("{}: closed {} {}", name(), statusCode, reason);
            ws = null;
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) {
            log.warn("{}: error {}", name(), error.getMessage());
            ws = null;
            scheduleReconnect();
        }
    }
}
