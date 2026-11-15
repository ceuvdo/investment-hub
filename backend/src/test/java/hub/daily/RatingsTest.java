package hub.daily;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RatingsTest {

    private static Map<String, Object> way(String name, int entry, int liq, String risk) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("name", name);
        w.put("entry", entry);
        w.put("liq", liq);
        w.put("risk", risk);
        return w;
    }

    private static Map<String, Object> asset(double swing, double dd, Double vol30, String min, String symbol) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("swing", swing);
        a.put("dd", dd);
        a.put("vol30_usd", vol30);
        a.put("min", min);
        a.put("symbol", symbol);
        a.put("currency", "USD");
        a.put("sell_detail", Map.of("exec_s", 0.27));
        return a;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> r(Map<String, Object> w, String k) {
        return (Map<String, Object>) ((Map<String, Object>) w.get("ratings")).get(k);
    }

    @Test
    void coinsAreHighRiskVeryLiquidCheap() {
        Map<String, Object> w = way("Coins and tokens", 1, 5, "high");
        Ratings.apply(w, List.of(asset(44, -83, 8e10, "$1", "BTC-USD"), asset(70, -90, 5e9, "$1", "ETH-USD")), Map.of());
        assertEquals("high", w.get("risk"));
        assertEquals(5, w.get("liq"));
        assertEquals(1, w.get("entry"));
        assertEquals("measured", r(w, "risk").get("source"));
        assertTrue(((String) r(w, "liq").get("basis")).contains("0.27 s"));
    }

    @Test
    void bondsAreLowRisk() {
        Map<String, Object> w = way("Government bonds", 1, 5, "low");
        Ratings.apply(w, List.of(asset(5.5, -23, 7e9, "$1", "GOVT")), Map.of());
        assertEquals("low", w.get("risk"));
    }

    @Test
    void thinVolumeLowersLiquidity() {
        Map<String, Object> w = way("Football club shares", 1, 5, "high");
        Ratings.apply(w, List.of(asset(31, -57, 5e6, "$1", "MANU")), Map.of());
        assertEquals(3, w.get("liq"));
    }

    @Test
    void currenciesAreTopLiquidityWithoutVolume() {
        Map<String, Object> w = way("Currencies", 1, 5, "high");
        Map<String, Object> eur = asset(6.5, -22, null, "$1", "EURUSD=X");
        Ratings.apply(w, List.of(eur), Map.of());
        assertEquals(5, w.get("liq"));
        assertEquals("low", w.get("risk"));
    }

    @Test
    void proxyWaysUseResearchNotTheEtf() {
        Map<String, Object> w = way("Hedge funds", 4, 2, "medium");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("swing", Map.of("value", 5.7, "trust", "market"));               // QAI ETF
        m.put("dd_report", Map.of("value", -21.4, "trust", "index"));          // HFRI 2008
        m.put("time_to_sell", Map.of("days_low", 30, "days_high", 120, "text", "quarterly redemption, 90 days notice"));
        m.put("min_ticket", Map.of("usd", 1_000_000, "text", "typical minimum $1M"));
        Ratings.apply(w, List.of(asset(5.7, -15, 6e7, "$1", "QAI")), m);
        assertEquals("medium", w.get("risk"));
        assertEquals("research", r(w, "risk").get("source"));
        assertEquals(1, w.get("liq"));
        assertEquals(4, w.get("entry"));
    }

    @Test
    void editorialOnlyWhenNothingIsKnown() {
        Map<String, Object> w = way("Book rights", 2, 1, "high");
        Ratings.apply(w, new ArrayList<>(), Map.of());
        assertEquals("high", w.get("risk"));
        assertEquals("editorial", r(w, "risk").get("source"));
        assertEquals("editorial", r(w, "liq").get("source"));
        assertEquals("editorial", r(w, "entry").get("source"));
    }

    @Test
    void textParsing() {
        assertEquals(42.0, Ratings.parseDays("2–6 weeks on eBay"), 1e-9);
        assertEquals(365 * 10.0, Ratings.parseDays("locked 7-10 years"), 1e-9);
        assertEquals(149.0, Ratings.parseDays("Median ~149 days on market (Q3 2025)"), 1e-9);
        assertEquals(500.0, Ratings.usdAmount("typical entry $500 to $2,000"), 1e-9);
        assertEquals(1.1, Ratings.usdAmount("Mintos from $500, Bondora €1"), 1e-9); // the smallest amount wins
        assertEquals(1.1, Ratings.usdAmount("from €1"), 1e-9);
        assertEquals(250_000.0, Ratings.usdAmount("minimum $250k"), 1e-9);
        assertNull(Ratings.usdAmount("HK$ board lot"));
        assertEquals(1, Ratings.liqFromDays(90));
        assertEquals(2, Ratings.liqFromDays(42));
        assertEquals(3, Ratings.liqFromDays(4));
        assertEquals(4, Ratings.liqFromDays(0.5));
    }
}
