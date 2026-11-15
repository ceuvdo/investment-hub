package hub.daily;

import hub.catalog.Universe;
import hub.live.Timing;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SellProfilesTest {
    private static final double DAY = 86400;
    private static Locale saved;
    private static Universe universe;
    private Timing timing;
    private SellProfiles sell;

    @BeforeAll
    static void setUp() throws Exception {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("uk-UA")); // decimal comma locale: output must still use dots
        universe = new Universe();
    }

    @AfterAll
    static void restore() {
        Locale.setDefault(saved);
    }

    @BeforeEach
    void fresh() {
        timing = new Timing(mock(JdbcTemplate.class));
        sell = new SellProfiles(timing, universe);
    }

    private Map<String, Object> apply(String id, String symbol, String kind) {
        return apply(id, symbol, kind, null, null);
    }

    private Map<String, Object> apply(String id, String symbol, String kind, Double dom, String domAsof) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", id);
        a.put("symbol", symbol);
        a.put("kind", kind);
        sell.apply(a, dom, domAsof);
        return a;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(Map<String, Object> a) {
        return (Map<String, Object>) a.get("sell_detail");
    }

    private static double settleDays(Map<String, Object> a) {
        return (Double) detail(a).get("settle_s") / DAY;
    }

    @Test
    void usStocksSettleTPlusOne() {
        Map<String, Object> a = apply("aapl", "AAPL", "EQUITY");
        assertEquals("< 1 s fill · T+1", a.get("sell"));
        assertEquals(1.0, settleDays(a));
        assertEquals("NYSE / Nasdaq", detail(a).get("source"));
        assertEquals(1.0 + DAY, a.get("sell_s"));
        assertEquals(false, detail(a).get("measured"));
    }

    @Test
    void brkBIsUsNotASuffix() {
        Map<String, Object> a = apply("brk", "BRK-B", "EQUITY");
        assertEquals("< 1 s fill · T+1", a.get("sell"));
        assertEquals("NYSE / Nasdaq", detail(a).get("source"));
    }

    @Test
    void europeanAndLondonVenuesSettleTPlusTwo() {
        assertEquals("Borsa Italiana (Euronext Milan)", detail(apply("juve", "JUVE.MI", "EQUITY")).get("source"));
        assertEquals("Xetra Frankfurt", detail(apply("bvb", "BVB.DE", "EQUITY")).get("source"));
        assertEquals("London Stock Exchange", detail(apply("ccp", "CCP.L", "EQUITY")).get("source"));
        for (String s : new String[]{"JUVE.MI", "BVB.DE", "CCP.L", "0700.HK", "NESN.SW"}) {
            Map<String, Object> a = apply("x", s, "EQUITY");
            assertEquals("< 1 s fill · T+2", a.get("sell"), s);
            assertEquals(2.0, settleDays(a), s);
        }
    }

    @Test
    void indiaIsTPlusOne() {
        Map<String, Object> a = apply("rel", "RELIANCE.NS", "EQUITY");
        assertEquals("< 1 s fill · T+1", a.get("sell"));
        assertEquals("National Stock Exchange of India", detail(a).get("source"));
    }

    @Test
    void unknownSuffixFallsBackToUs() {
        assertEquals("NYSE / Nasdaq", detail(apply("x", "FOO.XX", "EQUITY")).get("source"));
    }

    @Test
    void fxCadIsTPlusOneOthersTPlusTwo() {
        Map<String, Object> cad = apply("usdcad", "CAD=X", "CURRENCY");
        assertEquals("instant fill · T+1", cad.get("sell"));
        assertEquals(1.0, settleDays(cad));
        assertEquals("< 1 s", detail(cad).get("exec"));
        assertEquals(1.0, detail(cad).get("exec_s"));

        Map<String, Object> eur = apply("eurusd", "EURUSD=X", "CURRENCY");
        assertEquals("instant fill · T+2", eur.get("sell"));
        assertEquals(2.0, settleDays(eur));
        assertEquals(false, detail(eur).get("measured"));
    }

    @Test
    void fxUsesMeasuredKrakenRoundTrip() {
        timing.add("kraken.rtt_ms", 123.4);
        Map<String, Object> a = apply("usdjpy", "JPY=X", "CURRENCY");
        assertEquals("0.12 s fill · T+2", a.get("sell"));
        assertEquals("0.12 s network round trip (Kraken, measured)", detail(a).get("exec"));
        assertEquals(0.1234, (Double) detail(a).get("exec_s"), 1e-12);
        assertEquals(true, detail(a).get("measured"));
        assertEquals(Math.round((0.1234 + 2 * DAY) * 1000) / 1000.0, a.get("sell_s"));
    }

    @Test
    void indexesAreNotTradable() {
        for (String s : new String[]{"^GSPC", "DX-Y.NYB"}) {
            Map<String, Object> a = apply("idx", s, "INDEX");
            assertEquals("index, not tradable", a.get("sell"), s);
            assertNull(a.get("sell_s"), s);
            assertEquals("Not tradable directly", detail(a).get("exec"));
        }
    }

    @Test
    void cryptoProtocolTargetsWithoutLiveSamples() {
        Map<String, Object> btc = apply("btc", "BTC-USD", "CRYPTOCURRENCY");
        assertEquals("instant fill · 10.0 min on-chain", btc.get("sell"));
        assertEquals(600.0, detail(btc).get("settle_s"));
        assertEquals("Bitcoin: 1 block ≈ 10.0 min (protocol target)", detail(btc).get("settle"));
        assertEquals(false, detail(btc).get("measured"));
        assertNull(detail(btc).get("exec_s"));
        assertEquals(600.0, btc.get("sell_s"));

        Map<String, Object> eth = apply("eth", "ETH-USD", "CRYPTOCURRENCY");
        assertEquals(768.0, detail(eth).get("settle_s"));
        assertEquals("instant fill · 12.8 min on-chain", eth.get("sell"));

        Map<String, Object> sol = apply("sol", "SOL-USD", "CRYPTOCURRENCY");
        assertEquals(12.8, detail(sol).get("settle_s"));
        assertEquals("instant fill · 13 s on-chain", sol.get("sell"));

        Map<String, Object> ltc = apply("ltc", "LTC-USD", "CRYPTOCURRENCY");
        assertEquals(150.0, detail(ltc).get("settle_s"));
    }

    @Test
    void tokensWithoutOwnChainDefaultToEthereum() {
        assertFalse(universe.chain.containsKey("link"));
        Map<String, Object> link = apply("link", "LINK-USD", "CRYPTOCURRENCY");
        assertEquals(768.0, detail(link).get("settle_s"));
        assertTrue(((String) detail(link).get("settle")).startsWith("Ethereum: "));
    }

    @Test
    void cryptoUsesLiveTimingSamples() {
        timing.set("bitcoin.block_s", 540);
        timing.set("ethereum.block_s", 12.5);
        timing.add("solana.slot_s", 0.45);
        timing.add("binance.rtt_ms", 250);

        Map<String, Object> btc = apply("btc", "BTC-USD", "CRYPTOCURRENCY");
        assertEquals(540.0, detail(btc).get("settle_s"));
        assertEquals("0.25 s fill · 9.0 min on-chain", btc.get("sell"));
        assertEquals("Bitcoin: 1 block ≈ 9.0 min (average of the last 10 blocks, live)", detail(btc).get("settle"));
        assertEquals(true, detail(btc).get("measured"));
        assertEquals("measured by our backend", detail(btc).get("source"));
        assertEquals(540.25, btc.get("sell_s"));

        Map<String, Object> eth = apply("eth", "ETH-USD", "CRYPTOCURRENCY");
        // finality is 64 fixed 12 s slots regardless of missed slots; the measured interval is only reported
        assertEquals(768.0, detail(eth).get("settle_s"));
        assertEquals("Ethereum: finality 12.8 min (64 slots × 12 s); blocks arriving every 12.5 s (measured)", detail(eth).get("settle"));

        Map<String, Object> token = apply("shib", "SHIB-USD", "CRYPTOCURRENCY");
        assertEquals(768.0, detail(token).get("settle_s"), "ERC-20 tokens settle at Ethereum finality (64 x 12 s)");

        Map<String, Object> sol = apply("sol", "SOL-USD", "CRYPTOCURRENCY");
        assertEquals(14.4, (Double) detail(sol).get("settle_s"), 1e-9);
        assertEquals("Solana: finality ≈ 14 s (32 slots × 0.45 s measured)", detail(sol).get("settle"));

        // chains we do not measure stay on protocol targets even when Binance RTT is measured
        Map<String, Object> doge = apply("doge", "DOGE-USD", "CRYPTOCURRENCY");
        assertEquals(60.0, detail(doge).get("settle_s"));
        assertEquals(true, detail(doge).get("measured"), "RTT is measured");
        assertEquals("network documentation", detail(doge).get("source"));
    }

    @Test
    void usHousesUseDaysOnMarket() {
        Map<String, Object> a = apply("hp_us", "QUSN628BIS", "INDEX", 52.0, "Oct 2026");
        assertEquals("52 days on market", a.get("sell"));
        assertEquals("52 days on market (median, Oct 2026)", detail(a).get("settle"));
        assertEquals(52 * DAY, detail(a).get("settle_s"));
        assertEquals(52 * DAY, a.get("sell_s"));
        assertNotNull(detail(a).get("note"));
    }

    @Test
    void housesWithoutDataSayNoData() {
        Map<String, Object> gb = apply("hp_gb", "QGBN628BIS", "INDEX", 52.0, "Oct 2026");
        assertEquals("no data", gb.get("sell"));
        assertNull(gb.get("sell_s"));
        assertEquals("no published average for this country", detail(gb).get("settle"));
        Map<String, Object> us = apply("hp_us", "QUSN628BIS", "INDEX", null, null);
        assertEquals("no data", us.get("sell"));
    }

    @Test
    void durFormatting() {
        assertEquals("0.4 s", SellProfiles.dur(0.4));
        assertEquals("9.5 s", SellProfiles.dur(9.5));
        assertEquals("13 s", SellProfiles.dur(12.8));
        assertEquals("9.6 min", SellProfiles.dur(576));
        assertEquals("2.0 h", SellProfiles.dur(7200));
        assertEquals("3 days", SellProfiles.dur(3 * DAY));
    }

    @Test
    void numbersUseDotDecimalUnderUkrainianLocale() {
        assertEquals("uk", Locale.getDefault().getLanguage());
        assertEquals("1,5", String.format("%.1f", 1.5), "sanity: the default locale really uses a comma");
        timing.add("binance.rtt_ms", 1234.5);
        timing.add("kraken.rtt_ms", 1234.5);
        timing.add("solana.slot_s", 0.41);
        String all = apply("sol", "SOL-USD", "CRYPTOCURRENCY").toString()
                + apply("eurusd", "EURUSD=X", "CURRENCY")
                + apply("hp_us", "QUSN628BIS", "INDEX", 47.6, "Oct 2026")
                + SellProfiles.dur(0.4) + SellProfiles.dur(576) + SellProfiles.dur(7200);
        assertFalse(all.matches("(?s).*\\d,\\d.*"), all);
        assertTrue(all.contains("1.23 s fill"), all);
    }
}
