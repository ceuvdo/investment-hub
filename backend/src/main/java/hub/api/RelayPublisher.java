package hub.api;

import hub.HubProperties;
import hub.live.LiveStore;
import hub.live.Quote;
import hub.util.Http;
import hub.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * Pushes changed quotes to the Cloudflare relay (Worker + Durable Object) so public visitors get live prices
 * without any inbound connection to this PC. Body is signed: X-Signature = hex(HMAC-SHA256(secret, body)).
 * The relay answers with the number of connected visitors; with nobody watching we push every 20 s,
 * which keeps the Cloudflare free-tier request budget for real visitors.
 */
@Component
public class RelayPublisher {
    private static final Logger log = LoggerFactory.getLogger(RelayPublisher.class);
    private static final long IDLE_INTERVAL_MS = 20_000;
    private final LiveStore store;
    private final LiveSocket socket;
    private final HubProperties props;
    private long sentVersion;
    private long lastFull;
    private long lastPush;
    private int failures;
    private volatile int visitors = -1; // unknown until the first answer
    private volatile long lastOk;

    public RelayPublisher(LiveStore store, LiveSocket socket, HubProperties props) {
        this.store = store;
        this.socket = socket;
        this.props = props;
    }

    public int visitors() {
        return visitors;
    }

    public long lastOkMs() {
        return lastOk;
    }

    @Scheduled(fixedDelayString = "${hub.relay.interval-ms:3000}", initialDelay = 10_000)
    public void push() {
        HubProperties.Relay r = props.relay();
        if (r == null || !r.configured()) return;
        long now = System.currentTimeMillis();
        if (visitors == 0 && now - lastPush < IDLE_INTERVAL_MS) return;
        boolean full = now - lastFull > 300_000; // full snapshot every 5 minutes
        LiveStore.Changes c = store.changes(full ? 0 : sentVersion);
        Map<String, Quote> q = c.quotes();
        if (q.isEmpty()) return;
        String body = socket.message(q, full);
        lastPush = now;
        try {
            HttpResponse<String> res = Http.post(r.url(), body, Map.of("Content-Type", "application/json", "X-Signature", sign(r.secret(), body)), Duration.ofSeconds(10));
            if (res.statusCode() == 200) {
                sentVersion = c.version();
                if (full) lastFull = now;
                lastOk = now;
                try {
                    Double n = Json.num(Json.parse(res.body()), "sockets");
                    visitors = n == null ? -1 : n.intValue();
                } catch (Exception e) {
                    visitors = -1; // old relay answering plain "ok"
                }
                if (failures > 0) log.info("relay back after {} failures", failures);
                failures = 0;
            } else if (failures++ % 20 == 0) {
                log.warn("relay HTTP {}: {}", res.statusCode(), res.body());
            }
        } catch (Exception e) {
            if (failures++ % 20 == 0) log.warn("relay push failed: {}", e.getMessage());
        }
    }

    public static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
