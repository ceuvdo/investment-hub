package hub.live;

import hub.catalog.Universe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.http.WebSocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Message parsing of the live feeds with realistic sample messages. No network: onText is called directly. */
class FeedsTest {
    private static Universe universe;
    private LiveStore store;
    private Timing timing;

    @BeforeAll
    static void loadUniverse() throws Exception {
        universe = new Universe();
    }

    @BeforeEach
    void setUp() {
        store = new LiveStore(mock(JdbcTemplate.class));
        timing = new Timing(mock(JdbcTemplate.class));
    }

    // ---------------- Binance ----------------

    @Test
    void binanceTickerUpdatesStore() {
        Feeds.Binance f = new Feeds.Binance(universe, store);
        assertTrue(f.url().startsWith("wss://stream.binance.com:9443/stream?streams="));
        assertTrue(f.url().contains("btcusdt@ticker/"));
        f.onText("""
                {"stream":"btcusdt@ticker","data":{"e":"24hrTicker","E":1763200000123,"s":"BTCUSDT","p":"-812.40000000",
                "P":"-0.874","w":"92650.1","x":"92950.00","c":"92137.60000000","Q":"0.00512","b":"92137.59","B":"3.1",
                "a":"92137.60","A":"1.2","o":"92950.00","h":"94100.00","l":"91500.00","v":"15234.123","q":"1411456789.12",
                "O":1763113600123,"C":1763200000123,"F":1,"L":2,"n":3}}""");
        Quote q = store.get("btc");
        assertNotNull(q);
        assertEquals(92137.6, q.price());
        assertEquals(-0.874, q.chg24h());
        assertEquals(1411456789.12, q.vol24hUsd());
        assertEquals("Binance", q.source());
    }

    @Test
    void binanceIgnoresUnknownSymbolsAndOtherMessages() {
        Feeds.Binance f = new Feeds.Binance(universe, store);
        f.onText("{\"stream\":\"foousdt@ticker\",\"data\":{\"s\":\"FOOUSDT\",\"c\":\"1.0\"}}");
        f.onText("{\"result\":null,\"id\":1}");
        f.onText("{\"stream\":\"btcusdt@ticker\",\"data\":{\"s\":\"BTCUSDT\",\"c\":\"not a number\"}}");
        f.onText("{\"stream\":\"btcusdt@ticker\",\"data\":{\"s\":\"BTCUSDT\",\"c\":\"0\"}}");
        assertTrue(store.all().isEmpty());
    }

    // ---------------- Kraken ----------------

    @Test
    void krakenTickerUpdatesStore() {
        Feeds.Kraken f = new Feeds.Kraken(universe, store);
        f.onText("""
                {"channel":"ticker","type":"snapshot","data":[
                 {"symbol":"EUR/USD","bid":1.0841,"bid_qty":1000.0,"ask":1.0843,"ask_qty":800.0,"last":1.0842,
                  "volume":12345.6,"vwap":1.0839,"low":1.0801,"high":1.0866,"change":0.0012,"change_pct":0.11},
                 {"symbol":"USD/CAD","bid":1.3901,"ask":1.3905,"last":1.3903,"change_pct":-0.2},
                 {"symbol":"XYZ/USD","last":5.0,"change_pct":1.0}]}""");
        assertEquals(1.0842, store.get("eurusd").price());
        assertEquals(0.11, store.get("eurusd").chg24h());
        assertNull(store.get("eurusd").vol24hUsd());
        assertEquals("Kraken", store.get("eurusd").source());
        assertEquals(1.3903, store.get("usdcad").price());
        assertEquals(2, store.all().size());
    }

    @Test
    void krakenIgnoresHeartbeatsAndStatus() {
        Feeds.Kraken f = new Feeds.Kraken(universe, store);
        f.onText("{\"channel\":\"heartbeat\"}");
        f.onText("{\"channel\":\"status\",\"type\":\"update\",\"data\":[{\"version\":\"2.0.9\",\"system\":\"online\",\"api_version\":\"v2\",\"connection_id\":1}]}");
        f.onText("{\"method\":\"subscribe\",\"result\":{\"channel\":\"ticker\",\"symbol\":\"EUR/USD\"},\"success\":true}");
        assertTrue(store.all().isEmpty());
    }

    // ---------------- mempool.space ----------------

    @Test
    void mempoolInitBlocksSetAverageInterval() {
        Feeds.Mempool f = new Feeds.Mempool(timing);
        f.onText("""
                {"mempoolInfo":{"loaded":true,"size":12345},"blocks":[
                 {"id":"000a","height":870000,"version":536870912,"timestamp":1763190000,"tx_count":3000,"size":1500000},
                 {"id":"000b","height":870001,"timestamp":1763190500,"tx_count":3100},
                 {"id":"000c","height":870002,"timestamp":1763191200,"tx_count":2900}],
                 "conversions":{"USD":92000}}""");
        assertEquals(600.0, timing.median("bitcoin.block_s"));

        // a new block arrives on its own
        f.onText("{\"block\":{\"id\":\"000d\",\"height\":870003,\"timestamp\":1763191500}}");
        assertEquals(500.0, timing.median("bitcoin.block_s"));
        assertEquals(1, timing.count("bitcoin.block_s"), "set() replaces the window");
    }

    @Test
    void mempoolKeepsOnlyLast11Blocks() {
        Feeds.Mempool f = new Feeds.Mempool(timing);
        StringBuilder b = new StringBuilder("{\"blocks\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) b.append(',');
            // first 10 blocks 1000 s apart, the last 10 blocks 300 s apart
            long ts = i < 10 ? 1_700_000_000L + i * 1000L : 1_700_009_000L + (i - 9) * 300L;
            b.append("{\"height\":").append(800_000 + i).append(",\"timestamp\":").append(ts).append('}');
        }
        f.onText(b.append("]}").toString());
        // window = heights 800009..800019: 10 intervals, all 300 s
        assertEquals(300.0, timing.median("bitcoin.block_s"));
    }

    @Test
    void mempoolNeedsThreeBlocksAndIgnoresOtherMessages() {
        Feeds.Mempool f = new Feeds.Mempool(timing);
        f.onText("{\"blocks\":[{\"height\":1,\"timestamp\":100},{\"height\":2,\"timestamp\":700}]}");
        assertNull(timing.median("bitcoin.block_s"));
        f.onText("{\"mempoolInfo\":{\"size\":1},\"fees\":{\"fastestFee\":5}}");
        f.onText("{\"block\":{\"height\":3}}");
        assertNull(timing.median("bitcoin.block_s"));
    }

    // ---------------- Ethereum ----------------

    private static String newHead(long ts) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"eth_subscription\",\"params\":{\"subscription\":\"0x9ce59a13059e417087c02d3236a0b1cc\","
                + "\"result\":{\"number\":\"0x1500000\",\"hash\":\"0xabc\",\"timestamp\":\"0x" + Long.toHexString(ts) + "\",\"gasUsed\":\"0x1\"}}}";
    }

    @Test
    void ethereumHeadersSetAverageBlockInterval() {
        Feeds.Ethereum f = new Feeds.Ethereum(timing);
        f.onText("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x9ce59a13059e417087c02d3236a0b1cc\"}"); // subscription ack
        f.onText(newHead(1_763_200_000L));
        f.onText(newHead(1_763_200_012L));
        assertNull(timing.median("ethereum.block_s"), "needs 3 headers");
        f.onText(newHead(1_763_200_036L)); // one missed slot
        assertEquals(18.0, timing.median("ethereum.block_s"));
        for (int i = 1; i <= 10; i++) f.onText(newHead(1_763_200_036L + 12L * i));
        assertEquals(12.0, timing.median("ethereum.block_s"), "window of the last 11 headers");
    }

    // ---------------- Solana ----------------

    private static String slot(long s) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"slotNotification\",\"params\":{\"result\":{\"parent\":" + (s - 1)
                + ",\"root\":" + (s - 32) + ",\"slot\":" + s + "},\"subscription\":0}}";
    }

    @Test
    void solanaMeasuresSlotTimeOverOneMinute() throws Exception {
        Feeds.Solana f = new Feeds.Solana(timing);
        f.onText("{\"jsonrpc\":\"2.0\",\"result\":0,\"id\":1}");
        f.onText(slot(380_000_000L));
        f.onText(slot(380_000_010L));
        assertNull(timing.median("solana.slot_s"), "window shorter than a minute");

        // pretend the window started 60 s ago
        Field start = Feeds.Solana.class.getDeclaredField("windowStartMs");
        start.setAccessible(true);
        start.setLong(f, System.currentTimeMillis() - 60_000);
        f.onText(slot(380_000_150L));
        Double s = timing.median("solana.slot_s");
        assertNotNull(s);
        assertEquals(0.4, s, 0.01);
    }

    // ---------------- listener path ----------------

    @Test
    void malformedMessagesThrowOnlyInsideOnText() {
        Feeds.Binance b = new Feeds.Binance(universe, store);
        Feeds.Ethereum e = new Feeds.Ethereum(timing);
        Feeds.Solana s = new Feeds.Solana(timing);
        // Jackson 3 exceptions are unchecked; WsFeed's listener catches them
        assertThrows(RuntimeException.class, () -> b.onText("not json"));
        assertThrows(RuntimeException.class, () -> e.onText(newHead(0).replace("0x0", "0xZZ")));
        assertDoesNotThrow(() -> s.onText("{}"));
        assertDoesNotThrow(() -> new Feeds.Mempool(timing).onText("[]"));
        assertDoesNotThrow(() -> new Feeds.Kraken(universe, store).onText("{\"channel\":\"ticker\",\"data\":{}}"));
    }

    @Test
    void listenerSwallowsBadMessagesAndKeepsRequesting() throws Exception {
        Feeds.Binance f = new Feeds.Binance(universe, store);
        Class<?> cls = Class.forName("hub.live.WsFeed$Listener");
        Constructor<?> c = cls.getDeclaredConstructor(WsFeed.class);
        c.setAccessible(true);
        WebSocket.Listener listener = (WebSocket.Listener) c.newInstance(f);
        WebSocket ws = mock(WebSocket.class);

        assertDoesNotThrow(() -> listener.onText(ws, "{{{ not json", true));
        assertEquals(1, f.messages());
        // fragmented message is reassembled before parsing
        listener.onText(ws, "{\"stream\":\"ethusdt@ticker\",\"data\":{\"s\":\"ETH", false);
        assertEquals(1, f.messages());
        listener.onText(ws, "USDT\",\"c\":\"3100.5\",\"P\":\"1.2\",\"q\":\"5000\"}}", true);
        assertEquals(2, f.messages());
        assertEquals(3100.5, store.get("eth").price());
        verify(ws, org.mockito.Mockito.times(3)).request(1);
        assertTrue(f.lastMessageAgeMs() >= 0);
    }

    @Test
    void feedsDoNotConnectWhenDisabled() {
        Feeds.Binance f = new Feeds.Binance(universe, store);
        assertTrue(f.isAutoStartup(), "production default");
        f.setLiveEnabled(false);
        assertFalse(f.isAutoStartup());
        assertFalse(f.isRunning());
        assertEquals("stopped", f.state());
        assertEquals(-1, f.lastMessageAgeMs());
    }
}
