package hub.live;

import hub.catalog.Universe;
import hub.util.Http;
import hub.util.Json;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** All live feeds. Each is a Spring bean with its own reconnecting websocket. */
public final class Feeds {
    private Feeds() {}

    /** Binance spot 24h tickers for crypto and tokenised gold. Updates about once a second per pair. */
    @Component
    public static class Binance extends WsFeed {
        private final LiveStore store;
        private final Map<String, String> idBySymbol = new HashMap<>();
        private final String url;

        public Binance(Universe u, LiveStore store) {
            this.store = store;
            u.binance.forEach((id, sym) -> idBySymbol.put(sym, id));
            this.url = "wss://stream.binance.com:9443/stream?streams=" + u.binance.values().stream()
                    .map(s -> s.toLowerCase() + "@ticker").collect(Collectors.joining("/"));
        }

        public String name() { return "binance"; }

        protected String url() { return url; }

        protected void onOpen(WebSocket ws) {}

        protected void onText(String msg) {
            JsonNode d = Json.parse(msg).get("data");
            if (d == null) return;
            String id = idBySymbol.get(Json.str(d, "s"));
            Double price = Json.num(d, "c");
            if (id != null && price != null) store.tick(id, price, Json.num(d, "P"), Json.num(d, "q"), "Binance");
        }
    }

    /** Kraken v2 tickers for currency pairs. */
    @Component
    public static class Kraken extends WsFeed {
        private final LiveStore store;
        private final Universe u;
        private final Map<String, String> idBySymbol = new HashMap<>();

        public Kraken(Universe u, LiveStore store) {
            this.u = u;
            this.store = store;
            u.kraken.forEach((id, sym) -> idBySymbol.put(sym, id));
        }

        public String name() { return "kraken"; }

        protected String url() { return "wss://ws.kraken.com/v2"; }

        protected void onOpen(WebSocket ws) {
            Map<String, Object> sub = Map.of("method", "subscribe",
                    "params", Map.of("channel", "ticker", "symbol", new ArrayList<>(u.kraken.values())));
            send(ws, Json.write(sub));
        }

        protected void onText(String msg) {
            JsonNode m = Json.parse(msg);
            if (!"ticker".equals(Json.str(m, "channel")) || m.get("data") == null) return;
            for (JsonNode d : m.get("data")) {
                String id = idBySymbol.get(Json.str(d, "symbol"));
                Double price = Json.num(d, "last");
                if (id != null && price != null) store.tick(id, price, Json.num(d, "change_pct"), null, "Kraken");
            }
        }
    }

    /** mempool.space: Bitcoin blocks. Average interval of the last 10 blocks = time for 1 confirmation. */
    @Component
    public static class Mempool extends WsFeed {
        private final Timing timing;
        private final TreeMap<Long, Long> blockTime = new TreeMap<>(); // height -> unix seconds

        public Mempool(Timing timing) {
            this.timing = timing;
        }

        public String name() { return "mempool"; }

        protected String url() { return "wss://mempool.space/api/v1/ws"; }

        protected void onOpen(WebSocket ws) {
            send(ws, "{\"action\":\"init\"}");
            send(ws, "{\"action\":\"want\",\"data\":[\"blocks\"]}");
        }

        protected void onText(String msg) {
            JsonNode m = Json.parse(msg);
            boolean changed = false;
            if (m.get("blocks") != null && m.get("blocks").isArray()) {
                for (JsonNode b : m.get("blocks")) changed |= add(b);
            }
            if (m.get("block") != null) changed |= add(m.get("block"));
            if (changed) update();
        }

        private synchronized boolean add(JsonNode b) {
            Double h = Json.num(b, "height"), t = Json.num(b, "timestamp");
            if (h == null || t == null) return false;
            blockTime.put(h.longValue(), t.longValue());
            while (blockTime.size() > 11) blockTime.pollFirstEntry();
            return true;
        }

        private synchronized void update() {
            if (blockTime.size() < 3) return;
            long first = blockTime.firstEntry().getValue(), last = blockTime.lastEntry().getValue();
            long blocks = blockTime.lastKey() - blockTime.firstKey();
            if (blocks > 0) timing.set("bitcoin.block_s", (last - first) / (double) blocks);
        }
    }

    /** Ethereum new block headers from a public node: real block interval (12 s slots, minus missed slots). */
    @Component
    public static class Ethereum extends WsFeed {
        private final Timing timing;
        private final List<Long> times = new ArrayList<>();

        public Ethereum(Timing timing) {
            this.timing = timing;
        }

        public String name() { return "ethereum"; }

        protected String url() { return "wss://ethereum-rpc.publicnode.com"; }

        protected void onOpen(WebSocket ws) {
            send(ws, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_subscribe\",\"params\":[\"newHeads\"]}");
        }

        protected synchronized void onText(String msg) {
            JsonNode p = Json.parse(msg).path("params").path("result");
            String ts = Json.str(p, "timestamp");
            if (ts == null) return;
            times.add(Long.parseLong(ts.substring(2), 16));
            while (times.size() > 11) times.remove(0);
            if (times.size() >= 3)
                timing.set("ethereum.block_s", (times.get(times.size() - 1) - times.get(0)) / (double) (times.size() - 1));
        }
    }

    /** Solana slot notifications: measured slot time (~0.4 s). Finality = 32 slots. */
    @Component
    public static class Solana extends WsFeed {
        private final Timing timing;
        private long windowStartSlot = -1, windowStartMs;

        public Solana(Timing timing) {
            this.timing = timing;
        }

        public String name() { return "solana"; }

        protected String url() { return "wss://api.mainnet-beta.solana.com"; }

        protected void onOpen(WebSocket ws) {
            windowStartSlot = -1;
            send(ws, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"slotSubscribe\"}");
        }

        protected synchronized void onText(String msg) {
            Double slot = Json.num(Json.parse(msg).path("params").path("result"), "slot");
            if (slot == null) return;
            long now = System.currentTimeMillis(), s = slot.longValue();
            if (windowStartSlot < 0) {
                windowStartSlot = s;
                windowStartMs = now;
            } else if (now - windowStartMs >= 60_000 && s > windowStartSlot) {
                timing.add("solana.slot_s", (now - windowStartMs) / 1000.0 / (s - windowStartSlot));
                windowStartSlot = s;
                windowStartMs = now;
            }
        }
    }

    /** Round trip to exchange APIs over a kept-alive connection: what an order request costs on the network. */
    @Component
    public static class RttProbe {
        private final Timing timing;

        public RttProbe(Timing timing) {
            this.timing = timing;
        }

        @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
        public void probe() {
            for (int i = 0; i < 3; i++) { // first call may include TLS setup; later ones reuse the connection
                double b = Http.rttMs("https://api.binance.com/api/v3/ping");
                double k = Http.rttMs("https://api.kraken.com/0/public/Time");
                if (i > 0) {
                    timing.add("binance.rtt_ms", b);
                    timing.add("kraken.rtt_ms", k);
                }
            }
        }
    }
}
