package hub.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Latest live quotes + 1-minute candles. Feeds call {@link #tick}; the API, the local websocket
 * and the Cloudflare relay read {@link #changedSince}. Candles are flushed to TimescaleDB every minute.
 */
@Component
public class LiveStore {
    private static final Logger log = LoggerFactory.getLogger(LiveStore.class);

    private final Map<String, Quote> latest = new ConcurrentHashMap<>();
    private final Map<String, Long> version = new ConcurrentHashMap<>();
    private final AtomicLong clock = new AtomicLong();
    private final Map<String, Candle> open = new HashMap<>();   // guarded by this
    private final List<Candle> closed = new ArrayList<>();      // guarded by this
    private final JdbcTemplate jdbc;
    /** Wall clock in epoch millis; replaceable in tests to cross minute boundaries. */
    LongSupplier nowMs = System::currentTimeMillis;

    private static final class Candle {
        final String id, source;
        final long minute;
        double o, h, l, c;
        int n;

        Candle(String id, long minute, double p, String source) {
            this.id = id;
            this.minute = minute;
            this.source = source;
            o = h = l = c = p;
            n = 1;
        }
    }

    public LiveStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Quotes changed since a version, plus the version they bring the reader up to (taken atomically). */
    public record Changes(long version, Map<String, Quote> quotes) {}

    public void tick(String id, double price, Double chg24h, Double vol24hUsd, String source) {
        if (!(price > 0) || !Double.isFinite(price)) return;
        long now = nowMs.getAsLong();
        long minute = now / 60_000 * 60_000;
        synchronized (this) {
            // version and quote are published together, so a reader never skips an update
            latest.put(id, new Quote(id, price, chg24h, vol24hUsd, now, source));
            version.put(id, clock.incrementAndGet());
            Candle c = open.get(id);
            if (c != null && c.minute != minute) {
                closed.add(c);
                c = null;
            }
            if (c == null) {
                open.put(id, new Candle(id, minute, price, source));
            } else {
                c.h = Math.max(c.h, price);
                c.l = Math.min(c.l, price);
                c.c = price;
                c.n++;
            }
        }
    }

    public Map<String, Quote> all() {
        return Map.copyOf(latest);
    }

    public Quote get(String id) {
        return latest.get(id);
    }

    public long version() {
        return clock.get();
    }

    /** Quotes updated after the given version (0 = everything). */
    public Map<String, Quote> changedSince(long v) {
        return changes(v).quotes();
    }

    /** Atomic read: the changes since {@code v} and the version to pass next time. */
    public synchronized Changes changes(long v) {
        Map<String, Quote> out = new HashMap<>();
        version.forEach((id, ver) -> {
            if (ver > v) out.put(id, latest.get(id));
        });
        return new Changes(clock.get(), out);
    }

    private static final int MAX_PENDING = 50_000; // ~1 day of candles for 30 symbols if the DB is down

    @Scheduled(fixedDelayString = "${hub.live.flush-ms:60000}", initialDelay = 65_000)
    public void flush() {
        flush(false);
    }

    /** On shutdown write the open candles too, so a restart does not lose the current minute. */
    @jakarta.annotation.PreDestroy
    public void flushOnShutdown() {
        flush(true);
    }

    private void flush(boolean all) {
        List<Candle> batch;
        long minute = nowMs.getAsLong() / 60_000 * 60_000;
        synchronized (this) {
            // close candles whose minute is over even if no new tick arrived
            open.values().removeIf(c -> {
                if (all || c.minute < minute) {
                    closed.add(c);
                    return true;
                }
                return false;
            });
            batch = new ArrayList<>(closed);
            closed.clear();
        }
        if (batch.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>(batch.size());
        for (Candle c : batch)
            rows.add(new Object[]{c.id, Timestamp.from(Instant.ofEpochMilli(c.minute)), c.o, c.h, c.l, c.c, c.n, c.source});
        try {
            jdbc.batchUpdate("""
                    INSERT INTO candles_1m(asset_id, ts, open, high, low, close, ticks, source) VALUES (?,?,?,?,?,?,?,?)
                    ON CONFLICT (asset_id, ts) DO UPDATE SET high = GREATEST(candles_1m.high, EXCLUDED.high),
                      low = LEAST(candles_1m.low, EXCLUDED.low), close = EXCLUDED.close, ticks = candles_1m.ticks + EXCLUDED.ticks
                    """, rows);
        } catch (Exception e) {
            // keep them for the next flush instead of dropping a minute of history
            synchronized (this) {
                if (closed.size() + batch.size() <= MAX_PENDING) closed.addAll(0, batch);
                else log.error("candle buffer full, dropping {} candles", batch.size());
            }
            log.warn("candle flush failed ({} candles kept for retry): {}", rows.size(), e.getMessage());
        }
    }
}
