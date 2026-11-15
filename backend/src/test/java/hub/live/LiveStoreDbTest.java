package hub.live;

import hub.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** LiveStore.flush against the real candles_1m hypertable in hub_test. */
class LiveStoreDbTest extends IntegrationTestBase {
    private static final String CLEAN = "DELETE FROM candles_1m WHERE asset_id = 'it_btc'";

    @Autowired
    private LiveStore store;

    @AfterEach
    void cleanUp() {
        store.nowMs = System::currentTimeMillis;
        jdbc.update(CLEAN);
    }

    @Test
    void flushWritesAndMergesCandles() {
        long minute = (System.currentTimeMillis() / 60_000 - 10) * 60_000;
        AtomicLong now = new AtomicLong(minute + 1_000);
        store.nowMs = now::get;
        jdbc.update(CLEAN);

        store.tick("it_btc", 100, null, null, "Binance");
        now.addAndGet(5_000);
        store.tick("it_btc", 110, null, null, "Binance");
        now.addAndGet(5_000);
        store.tick("it_btc", 90, null, null, "Binance");
        now.set(minute + 60_000 + 1_000);
        store.flush();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM candles_1m WHERE asset_id = 'it_btc'");
        assertEquals(new Timestamp(minute), row.get("ts"));
        assertEquals(100.0, row.get("open"));
        assertEquals(110.0, row.get("high"));
        assertEquals(90.0, row.get("low"));
        assertEquals(90.0, row.get("close"));
        assertEquals(3, row.get("ticks"));
        assertEquals("Binance", row.get("source"));

        // a second candle for the same minute (e.g. after a restart) is merged, not duplicated
        now.set(minute + 2_000);
        store.tick("it_btc", 120, null, null, "Binance");
        store.tick("it_btc", 95, null, null, "Binance");
        now.set(minute + 60_000 + 2_000);
        store.flush();
        row = jdbc.queryForMap("SELECT * FROM candles_1m WHERE asset_id = 'it_btc'");
        assertEquals(100.0, row.get("open"));
        assertEquals(120.0, row.get("high"));
        assertEquals(90.0, row.get("low"));
        assertEquals(95.0, row.get("close"));
        assertEquals(5, row.get("ticks"));
    }
}
