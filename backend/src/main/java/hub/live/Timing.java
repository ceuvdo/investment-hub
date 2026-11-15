package hub.live;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling timing measurements: round trip to exchanges (ms), block and slot intervals (s).
 * Keys: "binance.rtt_ms", "kraken.rtt_ms", "bitcoin.block_s", "ethereum.slot_s", "solana.slot_s".
 */
@Component
public class Timing {
    private static final int WINDOW = 50;
    private final Map<String, Deque<Double>> samples = new ConcurrentHashMap<>();
    private final Map<String, Long> updated = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public Timing(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void add(String key, double value) {
        if (!(value > 0) || Double.isInfinite(value)) return;
        Deque<Double> d = samples.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            d.addLast(value);
            while (d.size() > WINDOW) d.removeFirst();
        }
        updated.put(key, System.currentTimeMillis());
    }

    /** Replace the window (e.g. average block interval computed from a fresh list of blocks). */
    public void set(String key, double value) {
        if (!(value > 0) || Double.isInfinite(value)) return; // e.g. out-of-order block timestamps
        Deque<Double> d = samples.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            d.clear();
            d.addLast(value);
        }
        updated.put(key, System.currentTimeMillis());
    }

    public Double median(String key) {
        List<Double> l = snapshot(key);
        if (l.isEmpty()) return null;
        Collections.sort(l);
        int n = l.size();
        return n % 2 == 1 ? l.get(n / 2) : (l.get(n / 2 - 1) + l.get(n / 2)) / 2;
    }

    public Double p95(String key) {
        List<Double> l = snapshot(key);
        if (l.isEmpty()) return null;
        Collections.sort(l);
        return l.get(Math.min(l.size() - 1, (int) Math.ceil(l.size() * 0.95) - 1));
    }

    public int count(String key) {
        return snapshot(key).size();
    }

    private List<Double> snapshot(String key) {
        Deque<Double> d = samples.get(key);
        if (d == null) return new ArrayList<>();
        synchronized (d) {
            return new ArrayList<>(d);
        }
    }

    /** Summary for the API and the relay: key -> {median, p95, n, age_s}. */
    public Map<String, Map<String, Object>> summary() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String k : samples.keySet().stream().sorted().toList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("median", round(median(k)));
            m.put("p95", round(p95(k)));
            m.put("n", count(k));
            m.put("age_s", (System.currentTimeMillis() - updated.getOrDefault(k, 0L)) / 1000);
            out.put(k, m);
        }
        return out;
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 1000.0) / 1000.0;
    }

    /** Keep a history of the medians: one row per key per minute. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void persist() {
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> rows = new ArrayList<>();
        for (String k : samples.keySet()) {
            Double m = median(k);
            if (m != null) rows.add(new Object[]{k, now, m});
        }
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO timing_samples(key, ts, value) VALUES (?,?,?)", rows);
    }
}
