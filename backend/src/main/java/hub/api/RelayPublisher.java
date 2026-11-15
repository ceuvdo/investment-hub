package hub.api;

import hub.HubProperties;
import hub.live.LiveStore;
import hub.live.Quote;
import hub.util.Http;
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
 */
@Component
public class RelayPublisher {
    private static final Logger log = LoggerFactory.getLogger(RelayPublisher.class);
    private final LiveStore store;
    private final LiveSocket socket;
    private final HubProperties props;
    private long sentVersion;
    private long lastFull;
    private int failures;

    public RelayPublisher(LiveStore store, LiveSocket socket, HubProperties props) {
        this.store = store;
        this.socket = socket;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${hub.relay.interval-ms:3000}", initialDelay = 10_000)
    public void push() {
        HubProperties.Relay r = props.relay();
        if (r == null || !r.configured()) return;
        long v = store.version();
        boolean full = System.currentTimeMillis() - lastFull > 300_000; // full snapshot every 5 minutes
        Map<String, Quote> q = full ? store.all() : store.changedSince(sentVersion);
        if (q.isEmpty()) return;
        String body = socket.message(q, full);
        try {
            HttpResponse<String> res = Http.post(r.url(), body, Map.of("Content-Type", "application/json", "X-Signature", sign(r.secret(), body)), Duration.ofSeconds(10));
            if (res.statusCode() == 200) {
                sentVersion = v;
                if (full) lastFull = System.currentTimeMillis();
                if (failures > 0) log.info("relay back after {} failures", failures);
                failures = 0;
            } else if (failures++ % 20 == 0) {
                log.warn("relay HTTP {}: {}", res.statusCode(), res.body());
            }
        } catch (Exception e) {
            if (failures++ % 20 == 0) log.warn("relay push failed: {}", e.getMessage());
        }
    }

    static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
