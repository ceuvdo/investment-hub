package hub;

import hub.api.LocalOnlyFilter;
import hub.daily.Validator;
import hub.live.LiveStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression tests for the review fixes (Nov 2026). */
class FixesTest {

    @Test
    void localFilterServesOnlyPublicFiles() throws Exception {
        Method m = LocalOnlyFilter.class.getDeclaredMethod("allowedPath", String.class);
        m.setAccessible(true);
        for (String ok : List.of("/", "/index.html", "/way.html", "/assets/js/core.js", "/data/catalog.js", "/api/status", "/live"))
            assertTrue((boolean) m.invoke(null, ok), ok);
        for (String no : List.of("/backend/src/main/resources/application.yml", "/deploy.ps1", "/relay/worker.js", "/README.md",
                "/docs/ARCHITECTURE.md", "/.git/config", "/assets/img/CREDITS.md", "/assets/../deploy.ps1"))
            assertFalse((boolean) m.invoke(null, no), no);
    }

    @Test
    void sourceNameCountsPublishersNotAssets() throws Exception {
        Method m = Class.forName("hub.daily.CatalogBuilder").getDeclaredMethod("sourceName", String.class);
        m.setAccessible(true);
        assertEquals("Yahoo Finance", m.invoke(null, "Apple (AAPL), Yahoo Finance"));
        assertEquals("Yahoo Finance", m.invoke(null, "Vanguard Total World Stock ETF (VT), Yahoo Finance"));
        assertEquals("Knight Frank Wealth Report 2026", m.invoke(null, "Knight Frank Wealth Report 2026, KFLII"));
        assertEquals("Yahoo Finance", m.invoke(null, "sum of 23 tracked assets, Yahoo Finance"));
    }

    @Test
    void sigKeepsTinyPrices() throws Exception {
        Method m = Class.forName("hub.daily.CatalogBuilder").getDeclaredMethod("sig", double.class);
        m.setAccessible(true);
        assertEquals(4.2357e-6, (double) m.invoke(null, 4.2357e-6), 1e-15);
        assertEquals(64038.123, (double) m.invoke(null, 64038.12345), 1e-9);
    }

    @Test
    void changesAreAtomicWithTheirVersion() {
        LiveStore store = new LiveStore(mock(JdbcTemplate.class));
        store.tick("btc", 100, null, null, "t");
        LiveStore.Changes c1 = store.changes(0);
        assertEquals(1, c1.quotes().size());
        store.tick("eth", 5, null, null, "t");
        LiveStore.Changes c2 = store.changes(c1.version());
        assertEquals(Map.of("eth", store.get("eth")), c2.quotes());
        assertTrue(store.changes(c2.version()).quotes().isEmpty());
        store.tick("bad", Double.NaN, null, null, "t");
        assertNull(store.get("bad"));
    }

    @Test
    void validatorRejectsBrokenBuilds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Integer.class))).thenReturn(List.of(170));
        Validator v = new Validator(jdbc);

        List<Map<String, Object>> ways = new ArrayList<>();
        for (int i = 0; i < 64; i++) ways.add(new LinkedHashMap<>(Map.of("slug", "w" + i, "metrics", Map.of())));
        List<Map<String, Object>> assets = new ArrayList<>();
        for (int i = 0; i < 170; i++) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", "a" + i);
            a.put("price", 10.0);
            a.put("ret_1y", 5.0);
            a.put("trust", "market");
            a.put("asof", java.time.LocalDate.now().toString());
            assets.add(a);
        }
        Map<String, Object> good = Map.of("assets", assets, "ways", ways);
        assertTrue(v.check(good).ok(), () -> v.check(good).problems().toString());

        // too few assets
        assertFalse(v.check(Map.of("assets", assets.subList(0, 100), "ways", ways)).ok());
        // impossible price
        assets.get(0).put("price", 0.0);
        assertFalse(v.check(good).ok());
        assets.get(0).put("price", 10.0);
        // unsafe link from research
        ways.get(0).put("metrics", Map.of("ret_1y", Map.of("value", 1, "url", "javascript:alert(1)")));
        assertFalse(v.check(good).ok());
    }
}
