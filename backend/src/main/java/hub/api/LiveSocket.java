package hub.api;

import hub.live.LiveStore;
import hub.live.Quote;
import hub.live.Timing;
import hub.util.Json;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local live stream at ws://127.0.0.1:8080/live. Same message format as the Cloudflare relay:
 * {"t": epochMs, "q": {"btc": [price, chg24h, ts], ...}, "timing": {...}, "snapshot": bool}
 */
@Configuration
@EnableWebSocket
public class LiveSocket extends TextWebSocketHandler implements WebSocketConfigurer {
    private final LiveStore store;
    private final Timing timing;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private long sentVersion;

    public LiveSocket(LiveStore store, Timing timing) {
        this.store = store;
        this.timing = timing;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/live").setAllowedOrigins("http://127.0.0.1:8080", "http://localhost:8080", "http://127.0.0.1:8765", "http://localhost:8765");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession s) throws Exception {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(s, 5000, 512 * 1024);
        sessions.add(safe);
        safe.sendMessage(new TextMessage(message(store.all(), true)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        sessions.removeIf(x -> x.getId().equals(s.getId()));
    }

    @Scheduled(fixedDelay = 1000)
    public void broadcast() {
        if (sessions.isEmpty()) {
            sentVersion = store.version();
            return;
        }
        LiveStore.Changes c = store.changes(sentVersion);
        Map<String, Quote> changed = c.quotes();
        sentVersion = c.version();
        if (changed.isEmpty()) return;
        TextMessage msg = new TextMessage(message(changed, false));
        for (WebSocketSession s : sessions) {
            try {
                s.sendMessage(msg);
            } catch (Exception e) {
                sessions.remove(s);
            }
        }
    }

    public String message(Map<String, Quote> quotes, boolean snapshot) {
        Map<String, Object> q = new LinkedHashMap<>();
        quotes.forEach((id, x) -> q.put(id, new Object[]{x.price(), x.chg24h(), x.ts()}));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", System.currentTimeMillis());
        m.put("q", q);
        m.put("timing", timing.summary());
        m.put("snapshot", snapshot);
        return Json.write(m);
    }
}
