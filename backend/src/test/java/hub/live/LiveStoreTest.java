package hub.live;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LiveStoreTest {
    private static final long MINUTE = 60_000;
    private static final long T0 = 1_763_200_000_000L / MINUTE * MINUTE; // a minute boundary in Nov 2025

    private JdbcTemplate jdbc;
    private LiveStore store;
    private final AtomicLong now = new AtomicLong(T0 + 5_000);

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new LiveStore(jdbc);
        store.nowMs = now::get;
    }

    @Test
    void tickIgnoresNonPositivePrices() {
        store.tick("btc", 0, null, null, "Binance");
        store.tick("btc", -1, null, null, "Binance");
        store.tick("btc", Double.NaN, null, null, "Binance");
        assertNull(store.get("btc"));
        assertEquals(0, store.version());
        assertTrue(store.all().isEmpty());
    }

    @Test
    void tickStoresLatestQuote() {
        store.tick("btc", 100, -1.5, 2e9, "Binance");
        store.tick("btc", 101, -1.4, 2.1e9, "Binance");
        Quote q = store.get("btc");
        assertEquals(101, q.price());
        assertEquals(-1.4, q.chg24h());
        assertEquals(2.1e9, q.vol24hUsd());
        assertEquals("Binance", q.source());
        assertEquals(T0 + 5_000, q.ts());
        assertEquals(1, store.all().size());
    }

    @Test
    void changedSinceAndVersion() {
        assertEquals(0, store.version());
        assertTrue(store.changedSince(0).isEmpty());
        store.tick("btc", 100, null, null, "Binance");
        store.tick("eth", 10, null, null, "Binance");
        long v = store.version();
        assertEquals(2, v);
        assertEquals(Map.of("btc", store.get("btc"), "eth", store.get("eth")), store.changedSince(0));
        assertTrue(store.changedSince(v).isEmpty());
        store.tick("eth", 11, null, null, "Binance");
        assertEquals(3, store.version());
        Map<String, Quote> changed = store.changedSince(v);
        assertEquals(1, changed.size());
        assertEquals(11, changed.get("eth").price());
        // ignored ticks do not bump the version
        store.tick("eth", 0, null, null, "Binance");
        assertEquals(3, store.version());
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> flushedRows() {
        ArgumentCaptor<List<Object[]>> rows = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), rows.capture());
        return rows.getValue();
    }

    @Test
    void candleTracksOhlcWithinOneMinuteAndClosesOnNextMinute() {
        store.tick("btc", 100, null, null, "Binance");
        now.addAndGet(10_000);
        store.tick("btc", 105, null, null, "Binance");
        now.addAndGet(10_000);
        store.tick("btc", 95, null, null, "Binance");
        now.addAndGet(10_000);
        store.tick("btc", 101, null, null, "Binance");

        // still inside the same minute: nothing to write
        store.flush();
        verify(jdbc, never()).batchUpdate(anyString(), anyList());

        // first tick of the next minute closes the previous candle
        now.set(T0 + MINUTE + 1_000);
        store.tick("btc", 102, null, null, "Binance");
        store.flush();
        List<Object[]> rows = flushedRows();
        assertEquals(1, rows.size());
        Object[] r = rows.get(0);
        assertEquals("btc", r[0]);
        assertEquals(new Timestamp(T0), r[1]);
        assertArrayEquals(new Object[]{100.0, 105.0, 95.0, 101.0, 4, "Binance"}, new Object[]{r[2], r[3], r[4], r[5], r[6], r[7]});
    }

    @Test
    void flushClosesFinishedMinutesWithoutNewTicks() {
        store.tick("eurusd", 1.08, null, null, "Kraken");
        store.tick("btc", 100, null, null, "Binance");
        now.set(T0 + 2 * MINUTE);
        store.flush();
        List<Object[]> rows = flushedRows();
        assertEquals(2, rows.size());
        // the closed candles are not written twice
        reset(jdbc);
        store.flush();
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    void flushSurvivesDatabaseErrors() {
        when(jdbc.batchUpdate(anyString(), anyList())).thenThrow(new DataAccessResourceFailureException("db down"));
        store.tick("btc", 100, null, null, "Binance");
        now.set(T0 + 2 * MINUTE);
        assertDoesNotThrow(store::flush);
        // ticks keep working afterwards
        store.tick("btc", 101, null, null, "Binance");
        assertEquals(101, store.get("btc").price());
    }
}
