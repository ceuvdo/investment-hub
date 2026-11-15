package hub.daily;

import hub.catalog.Universe;
import hub.live.Timing;
import hub.util.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * CatalogBuilder with stubbed Sources and Series (no network, no DB): checks the JSON keys the website relies on.
 */
class CatalogBuilderTest {
    private static final long DAY = 86400;
    private static final long WEEK = 7 * DAY;
    private static final long LAST = LocalDate.of(2026, 11, 13).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    private static Universe universe;

    private Sources src;
    private Series series;
    private CatalogBuilder builder;

    @BeforeAll
    static void loadUniverse() throws Exception {
        universe = new Universe();
    }

    /** Weekly points over `years`, growing `growth` per year, with a deterministic wiggle. */
    private static Series.Points weekly(double years, double start, double growth, double wiggle, int phase) {
        int n = (int) (years * 52);
        long[] t = new long[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = LAST - (long) (n - 1 - i) * WEEK;
            double y = i / 52.0;
            v[i] = start * Math.pow(1 + growth, y) * (1 + wiggle * Math.sin((i + phase) * 0.7) + 0.3 * wiggle * Math.cos(i * 1.9));
        }
        return new Series.Points(t, v, null);
    }

    private static Series.Points daily(double last, double volume) {
        int n = 90;
        long[] t = new long[n];
        double[] v = new double[n], vol = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = LAST - (long) (n - 1 - i) * DAY;
            v[i] = last * (0.95 + 0.05 * i / (n - 1));
            vol[i] = i % 10 == 0 ? Double.NaN : volume;
        }
        return new Series.Points(t, v, vol);
    }

    private static Series.Points quarterly(int quarters, double start, double growthPerQuarter) {
        long[] t = new long[quarters];
        double[] v = new double[quarters];
        LocalDate d = LocalDate.of(2026, 4, 1).minusMonths(3L * (quarters - 1));
        for (int i = 0; i < quarters; i++) {
            t[i] = d.plusMonths(3L * i).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            v[i] = start * Math.pow(1 + growthPerQuarter, i) * (i % 7 == 3 ? 0.97 : 1);
        }
        return new Series.Points(t, v, null);
    }

    private static final Series.Points EMPTY = new Series.Points(new long[0], new double[0], new double[0]);

    private void market(String id, String currency, String type, Series.Points w, Series.Points d) {
        Universe.Asset a = universe.assets.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        when(src.yahoo(a)).thenReturn(new Sources.YahooMeta(currency, type, d.v()[d.size() - 1], LAST));
        when(series.load(id, 'w')).thenReturn(w);
        when(series.load(id, 'd')).thenReturn(d);
    }

    private static Map<String, Object> deposit(String iso, String country, double rate, Double infl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("iso", iso);
        m.put("country", country);
        m.put("rate", rate);
        m.put("rate_year", "2025");
        m.put("infl", infl);
        m.put("infl_year", infl == null ? null : "2025");
        m.put("real", infl == null ? null : Metrics.round2(rate - infl));
        return m;
    }

    @BeforeEach
    void setUp() {
        src = mock(Sources.class);
        series = mock(Series.class);
        Timing timing = new Timing(mock(JdbcTemplate.class));
        builder = new CatalogBuilder(universe, src, series, new SellProfiles(timing, universe));

        when(series.load(anyString(), anyChar())).thenReturn(EMPTY);
        when(src.fred(anyString(), anyString(), anyChar())).thenReturn(EMPTY);
        when(src.yahoo(any())).thenReturn(null);

        Series.Points spyW = weekly(10.6, 100, 0.10, 0.04, 0);
        market("spy", "USD", "ETF", spyW, daily(spyW.v()[spyW.size() - 1], 5e7));
        Series.Points vtW = weekly(10.6, 50, 0.08, 0.035, 1);
        market("vt", "USD", "ETF", vtW, daily(vtW.v()[vtW.size() - 1], 3e6));
        Series.Points aaplW = weekly(4, 120, 0.2, 0.08, 3); // short history: no ret_5y / ret_10y
        market("aapl", "USD", "EQUITY", aaplW, daily(aaplW.v()[aaplW.size() - 1], 5e7));
        Series.Points btcW = weekly(10.6, 400, 0.5, 0.2, 5);
        market("btc", "USD", "CRYPTOCURRENCY", btcW, daily(btcW.v()[btcW.size() - 1], 3e10));
        Series.Points eurW = weekly(10.6, 1.1, -0.005, 0.02, 2);
        market("eurusd", "USD", "CURRENCY", eurW, daily(eurW.v()[eurW.size() - 1], 0));

        Universe.House us = universe.houses.stream().filter(h -> h.id().equals("hp_us")).findFirst().orElseThrow();
        Universe.House gb = universe.houses.stream().filter(h -> h.id().equals("hp_gb")).findFirst().orElseThrow();
        when(src.fred("hp_us", us.series(), 'q')).thenReturn(quarterly(100, 50, 0.012));
        when(src.fred("hp_gb", gb.series(), 'q')).thenReturn(quarterly(60, 70, 0.01));
        when(src.fred("us_days_on_market", "MEDDAYONMARUS", 'm')).thenReturn(new Series.Points(
                new long[]{LocalDate.of(2026, 9, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond(), LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()},
                new double[]{49, 52}, null));

        when(src.deposits()).thenReturn(List.of(deposit("DEU", "Germany", 1.5, 2.2), deposit("USA", "United States", 4.0, 3.0), deposit("JPN", "Japan", 0.2, null)));
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("way", universe.defi.get(0).way());
        pool.put("name", universe.defi.get(0).name());
        pool.put("apy", 2.9);
        pool.put("apy_30d", 3.1);
        pool.put("tvl_usd", 1_000_000L);
        pool.put("chain", "Ethereum");
        pool.put("source", "DefiLlama yields");
        pool.put("url", "https://defillama.com/yields/pool/x");
        pool.put("asof", "2026-11-13");
        when(src.defi(any())).thenReturn(List.of(pool));
        when(src.research()).thenReturn(Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    private static Map<String, Object> byId(List<Map<String, Object>> l, String key, String id) {
        return l.stream().filter(x -> id.equals(x.get(key))).findFirst().orElseThrow(() -> new AssertionError("missing " + id));
    }

    @Test
    void catalogHasTheShapeTheSiteReads() {
        CatalogBuilder.Result r = builder.build();
        Map<String, Object> c = r.catalog();
        assertEquals(List.of("meta", "groups", "ways", "assets", "deposits"), new ArrayList<>(c.keySet()));

        List<Map<String, Object>> assets = list(c, "assets");
        assertEquals(List.of("vt", "spy", "aapl", "eurusd", "btc", "hp_us", "hp_gb"), assets.stream().map(a -> a.get("id")).toList());
        for (Map<String, Object> a : assets)
            for (String k : List.of("id", "name", "price", "ret_1y", "ret_5y", "ret_10y", "swing", "dd", "corr", "sell", "sell_s", "sell_detail", "group", "way_slug", "live"))
                assertTrue(a.containsKey(k), a.get("id") + " lacks " + k);

        Map<String, Object> meta = (Map<String, Object>) c.get("meta");
        assertEquals(77, meta.get("n_ways"));
        assertEquals(7, meta.get("n_assets"));
        assertEquals(3, meta.get("n_countries"));
        assertEquals("java", meta.get("backend"));

        String json = Json.write(c);
        assertFalse(json.contains("NaN") || json.contains("Infinity"), "catalog must be valid JSON");
        assertEquals(77, Json.parse(json).get("ways").size());
    }

    @Test
    void marketAssetMetrics() {
        List<Map<String, Object>> assets = list(builder.build().catalog(), "assets");
        Map<String, Object> spy = byId(assets, "id", "spy");
        assertEquals(1.0, spy.get("corr"), "SPY against itself");
        assertEquals("etfs-and-index-funds", spy.get("way_slug"));
        assertNotNull(spy.get("group"));
        assertEquals(false, spy.get("live"));
        assertEquals(true, spy.get("total_return"));
        assertEquals("< 1 s fill · T+1", spy.get("sell"));
        assertNotNull(spy.get("ret_1y"));
        assertEquals(10.0, (Double) spy.get("ret_10y"), 1.5);
        assertNotNull(spy.get("ret_5y"));
        assertNotNull(spy.get("swing"));
        assertTrue((Double) spy.get("dd") < 0);
        assertTrue(((String) spy.get("dd_span")).matches("[A-Z][a-z]{2} \\d{4} to [A-Z][a-z]{2} \\d{4}"));
        assertNotNull(spy.get("vol30_usd"));

        Map<String, Object> vt = byId(assets, "id", "vt");
        Double corr = (Double) vt.get("corr");
        assertNotNull(corr);
        assertTrue(corr >= -1 && corr <= 1);

        Map<String, Object> aapl = byId(assets, "id", "aapl");
        assertNotNull(aapl.get("ret_1y"));
        assertNotNull(aapl.get("ret_3y"));
        assertNull(aapl.get("ret_5y"), "4 years of history");
        assertNull(aapl.get("ret_10y"));

        Map<String, Object> btc = byId(assets, "id", "btc");
        assertEquals(true, btc.get("live"));
        assertTrue(((String) btc.get("sell")).endsWith("on-chain"));
        assertEquals(false, btc.get("total_return"));

        Map<String, Object> eur = byId(assets, "id", "eurusd");
        assertEquals(true, eur.get("live"));
        assertEquals("instant fill · T+2", eur.get("sell"));
        assertNull(eur.get("vol30_usd"), "no volume");
    }

    @Test
    void houseAssets() {
        List<Map<String, Object>> assets = list(builder.build().catalog(), "assets");
        Map<String, Object> us = byId(assets, "id", "hp_us");
        assertEquals("52 days on market", us.get("sell"));
        assertEquals("apartments-and-houses", us.get("way_slug"));
        assertEquals("INDEX", us.get("kind"));
        assertEquals("Q2 2026", us.get("asof"));
        assertNotNull(us.get("ret_10y"));
        assertNull(us.get("corr"));
        assertEquals(false, us.get("live"));
        assertEquals("no data", byId(assets, "id", "hp_gb").get("sell"));
    }

    @Test
    void waysAndGroups() {
        CatalogBuilder.Result r = builder.build();
        List<Map<String, Object>> ways = list(r.catalog(), "ways");
        for (Map<String, Object> w : ways)
            for (String k : List.of("slug", "name", "group", "metrics", "assets", "yields", "examples", "note"))
                assertTrue(w.containsKey(k), w.get("name") + " lacks " + k);

        Map<String, Object> etf = byId(ways, "slug", "etfs-and-index-funds");
        assertEquals(List.of("vt", "spy"), etf.get("assets"));
        Map<String, Object> m = (Map<String, Object>) etf.get("metrics");
        Map<String, Object> ret1 = (Map<String, Object>) m.get("ret_1y");
        assertEquals(byId(list(r.catalog(), "assets"), "id", "vt").get("ret_1y"), ret1.get("value"), "first tracked asset is representative");
        assertEquals("market", ret1.get("trust"));
        assertTrue(m.containsKey("vol30"));

        Map<String, Object> houses = byId(ways, "slug", "apartments-and-houses");
        Map<String, Object> hm = (Map<String, Object>) houses.get("metrics");
        assertTrue(((String) ((Map<String, Object>) hm.get("ret_1y")).get("source")).contains("median of 2 countries"));

        Map<String, Object> savings = byId(ways, "name", "Savings and deposits");
        Map<String, Object> sm = (Map<String, Object>) savings.get("metrics");
        assertEquals(1.5, ((Map<String, Object>) sm.get("yield")).get("value"));
        assertEquals(0.15, ((Map<String, Object>) sm.get("real_yield")).get("value"), "median of -0.7 and 1.0; Japan has no inflation");

        Map<String, Object> staking = byId(ways, "name", universe.defi.get(0).way());
        assertEquals(3.1, ((Map<String, Object>) ((Map<String, Object>) staking.get("metrics")).get("yield")).get("value"));

        List<Map<String, Object>> groups = list(r.catalog(), "groups");
        assertEquals(universe.groups.size(), groups.size());
        for (Map<String, Object> g : groups) {
            assertTrue(g.containsKey("ret_1y"), g.get("slug") + " lacks ret_1y");
            assertTrue(g.containsKey("ret_1y_n"), g.get("slug") + " lacks ret_1y_n");
            assertTrue(g.containsKey("ways"));
        }
        assertEquals(77, groups.stream().mapToInt(g -> ((List<?>) g.get("ways")).size()).sum(), "every way in exactly one group");
        Map<String, Object> etfGroup = byId(groups, "slug", (String) etf.get("group"));
        assertTrue((Integer) etfGroup.get("ret_1y_n") >= 1);
        assertNotNull(etfGroup.get("ret_1y"));
    }

    @Test
    void seriesForCharts() {
        Map<String, Object> s = builder.build().series();
        assertEquals(List.of("vt", "spy", "aapl", "eurusd", "btc", "hp_us", "hp_gb"), new ArrayList<>(s.keySet()));
        Map<String, Object> spy = (Map<String, Object>) s.get("spy");
        List<?> months = (List<?>) spy.get("m"), values = (List<?>) spy.get("v");
        assertEquals(months.size(), values.size());
        assertEquals("2026-11", months.get(months.size() - 1));
        Map<String, Object> us = (Map<String, Object>) s.get("hp_us");
        assertEquals(((List<?>) us.get("m")).size(), ((List<?>) us.get("v")).size());
        assertTrue(((List<?>) us.get("m")).size() <= 81, "last 20 years of quarters");
    }

    @Test
    void assetsWithoutHistoryAreSkipped() {
        Universe.Asset a = universe.assets.stream().filter(x -> x.id().equals("qqq")).findFirst().orElseThrow();
        when(src.yahoo(a)).thenReturn(new Sources.YahooMeta("USD", "ETF", 500.0, LAST));
        when(series.load("qqq", 'w')).thenReturn(weekly(0.1, 1, 0, 0, 0)); // 5 points < 10
        List<Map<String, Object>> assets = list(builder.build().catalog(), "assets");
        assertTrue(assets.stream().noneMatch(x -> "qqq".equals(x.get("id"))));
    }

    private static tools.jackson.databind.JsonNode skinHistory(String hash, double med7, double vol7, double avg30, double vol30) {
        return Json.parse("""
                {"market_hash_name": "%s", "item_page": "https://skinport.com/item/x",
                 "last_24_hours": {"median": %s, "min": 1, "max": 9, "volume": 20},
                 "last_7_days": {"median": %s, "min": 1, "max": 9, "volume": %s},
                 "last_30_days": {"avg": %s, "median": 6.5, "min": 1, "max": 9, "volume": %s},
                 "last_90_days": {"median": 6.2, "min": 1, "max": 9, "volume": 20000}}
                """.formatted(hash.replace("\"", "\\\""), med7, med7, vol7, avg30, vol30));
    }

    @Test
    void skinAssetsFromSkinportSales() {
        Universe.Skin s = universe.skins.get(0), slow = universe.skins.get(1);
        var listing = Json.parse("{\"min_price\": 6.93, \"quantity\": 410}");
        when(src.skinport(any())).thenReturn(new Sources.Skinport(
                Map.of(s.hash(), skinHistory(s.hash(), 6.6, 2000, 6.7, 8640), slow.hash(), skinHistory(slow.hash(), 50, 1, 48, 10)),
                Map.of(s.hash(), listing), "2026-11-13", true));
        // 120 days of our own daily prices, rising slowly
        int n = 120;
        long[] t = new long[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = LAST - (long) (n - 1 - i) * DAY;
            v[i] = 6 + 0.6 * i / (n - 1) + 0.1 * Math.sin(i);
        }
        when(series.load(s.id(), 'd')).thenReturn(new Series.Points(t, v, null));

        CatalogBuilder.Result r = builder.build();
        List<Map<String, Object>> assets = list(r.catalog(), "assets");
        Map<String, Object> a = byId(assets, "id", s.id());
        assertEquals(6.6, a.get("price"), "7-day median sale");
        assertEquals("SKIN", a.get("kind"));
        assertEquals("cards", a.get("group"));
        assertEquals("skins-and-cases", a.get("way_slug"));
        assertEquals("Skinport", a.get("source"));
        assertEquals("market", a.get("trust"));
        assertEquals(Math.round(6.7 * 8640), a.get("vol30_usd"));
        assertEquals("~1 sale / 5.0 min · 7-day hold", a.get("sell"));
        assertEquals(300 + 7 * 86400.0, (Double) a.get("sell_s"), 0.01);
        assertNotNull(a.get("chg_30d"), "120 days of history");
        assertNotNull(a.get("swing"));
        assertNotNull(a.get("dd"));
        assertNull(a.get("ret_1y"), "no full year yet");
        assertNull(a.get("corr"));
        assertEquals(5.0, ((Map<String, Object>) a.get("listings")).get("gap"), "lowest ask 5% above the median sale");
        assertEquals(2000.0, ((Map<String, Object>) ((Map<String, Object>) a.get("sales")).get("7d")).get("volume"));

        // 1 sale in 7 days: priced from the 30-day median; no own history yet, so no returns and no chart
        Map<String, Object> b = byId(assets, "id", slow.id());
        assertEquals(6.5, b.get("price"));
        assertEquals("30d", b.get("price_basis"));
        assertEquals("7d", a.get("price_basis"));
        assertNull(b.get("chg_30d"));
        assertNull(b.get("swing"));
        assertFalse(r.series().containsKey(slow.id()));
        assertTrue(r.series().containsKey(s.id()), "4 months of own history");
        assertEquals(2, assets.stream().filter(x -> "SKIN".equals(x.get("kind"))).count(), "items without sales are skipped");

        Map<String, Object> way = byId(list(r.catalog(), "ways"), "slug", "skins-and-cases");
        Map<String, Object> liq = (Map<String, Object>) ((Map<String, Object>) way.get("ratings")).get("liq");
        assertEquals(3, liq.get("value"), "fast sales, capped by the 7-day hold");
        assertEquals("measured", liq.get("source"));
        Map<String, Object> wm = (Map<String, Object>) way.get("metrics");
        assertNull(wm.get("swing"), "one item does not stand for the skin market");
        assertNotNull(wm.get("vol30"));
        assertTrue(((Map<String, Object>) r.catalog().get("meta")).get("n_sources") instanceof Integer);
    }

    @Test
    void skinPriceNeedsEnoughSales() {
        assertEquals(6.6, Sources.skinPrice(skinHistory("x", 6.6, 3, 6, 10)));
        assertEquals(6.5, Sources.skinPrice(skinHistory("x", 6.6, 2, 6, 10)), "falls back to 30 days");
        assertNull(Sources.skinPrice(skinHistory("x", 6.6, 2, 6, 2)));
        assertNull(Sources.skinPrice(null));
    }

    @Test
    void depositsWithoutInflationDoNotBreakTheBuild() {
        when(src.deposits()).thenReturn(List.of(deposit("JPN", "Japan", 0.2, null)));
        Map<String, Object> savings = byId(list(builder.build().catalog(), "ways"), "name", "Savings and deposits");
        assertEquals(0.2, ((Map<String, Object>) ((Map<String, Object>) savings.get("metrics")).get("yield")).get("value"));
    }
}
