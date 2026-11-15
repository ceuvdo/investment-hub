package hub.api;

import hub.HubProperties;
import hub.IntegrationTestBase;
import hub.daily.DailyJob;
import hub.live.Feeds;
import hub.live.WsFeed;
import hub.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ApiIntegrationTest extends IntegrationTestBase {
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private DailyJob daily;
    @Autowired
    private HubProperties props;
    @Autowired
    private List<WsFeed> feeds;
    @Autowired
    private ApplicationContext ctx;

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Hub-Admin", "1").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void testProfileIsSafe() {
        assertNotEquals(8080, port);
        assertFalse(props.deploy().enabled());
        assertFalse(props.relay().configured());
        assertEquals(5, feeds.size());
        for (WsFeed f : feeds) {
            assertFalse(f.isRunning(), f.name());
            assertFalse(f.isAutoStartup(), f.name());
        }
        assertTrue(ctx.getBeansOfType(Feeds.RttProbe.class).isEmpty(), "RTT probe would call Binance and Kraken");
    }

    @Test
    void flywayCreatedHypertables() {
        List<String> hypertables = jdbc.queryForList(
                "SELECT hypertable_name::text FROM timescaledb_information.hypertables ORDER BY 1", String.class);
        assertTrue(hypertables.containsAll(List.of("candles_1m", "timing_samples")), hypertables.toString());
        List<String> aggs = jdbc.queryForList(
                "SELECT view_name::text FROM timescaledb_information.continuous_aggregates ORDER BY 1", String.class);
        assertEquals(List.of("candles_1d", "candles_1h"), aggs);
        for (String t : List.of("price_points", "catalog_builds", "source_cache"))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, t), t);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class));
    }

    @Test
    void statusReportsFeeds() throws Exception {
        HttpResponse<String> r = get("/api/status");
        assertEquals(200, r.statusCode());
        JsonNode s = Json.parse(r.body());
        JsonNode f = s.get("feeds");
        assertEquals(Set.of("binance", "kraken", "mempool", "ethereum", "solana"), Set.copyOf(f.propertyNames()));
        assertEquals("stopped", f.get("binance").get("state").asString());
        assertEquals(0, f.get("kraken").get("messages").asInt());
        assertEquals(-1, f.get("solana").get("last_message_ms_ago").asInt());
        assertNotNull(s.get("candles_1m_rows"));
        assertTrue(s.has("timing"));
        assertFalse(s.get("daily").get("running").asBoolean());
    }

    @Test
    void seriesOfUnknownAssetIsEmpty() throws Exception {
        for (String range : List.of("10y", "1d", "7d", "30d")) {
            HttpResponse<String> r = get("/api/series/no-such-asset?range=" + range);
            assertEquals(200, r.statusCode(), range);
            JsonNode s = Json.parse(r.body());
            assertEquals("no-such-asset", s.get("id").asString());
            assertEquals(range, s.get("range").asString());
            assertTrue(s.get("t").isArray() && s.get("t").isEmpty(), range);
            assertTrue(s.get("v").isArray() && s.get("v").isEmpty(), range);
        }
    }

    @Test
    void seriesReturnsStoredWeeklyAndQuarterlyPoints() throws Exception {
        jdbc.update("DELETE FROM price_points WHERE asset_id = 'it_series'");
        jdbc.update("INSERT INTO price_points(asset_id, kind, ts, value) VALUES "
                + "('it_series','w','2024-01-01T00:00:00Z',1.5),('it_series','w','2024-01-08T00:00:00Z',2.5),('it_series','d','2024-01-09T00:00:00Z',9)");
        try {
            JsonNode s = Json.parse(get("/api/series/it_series").body());
            assertEquals(2, s.get("t").size(), "daily points are not part of the long range");
            assertEquals("2024-01-01T00:00:00Z", s.get("t").get(0).asString());
            assertEquals(2.5, s.get("v").get(1).asDouble());
        } finally {
            jdbc.update("DELETE FROM price_points WHERE asset_id = 'it_series'");
        }
    }

    @Test
    void catalogIs503WithoutBuildAnd200WithOne() throws Exception {
        jdbc.update("DELETE FROM catalog_builds");
        try {
            HttpResponse<String> r = get("/api/catalog");
            assertEquals(503, r.statusCode());
            assertTrue(r.body().contains("no catalog built yet"));
            assertEquals(404, get("/api/assets/spy").statusCode());

            jdbc.update("INSERT INTO catalog_builds(n_assets, n_ways, json) VALUES (1, 0, ?::jsonb)",
                    "{\"assets\":[{\"id\":\"spy\",\"price\":1.5}],\"ways\":[]}");
            r = get("/api/catalog");
            assertEquals(200, r.statusCode());
            assertEquals("spy", Json.parse(r.body()).get("assets").get(0).get("id").asString());
            assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("max-age=300"));
            HttpResponse<String> a = get("/api/assets/spy");
            assertEquals(200, a.statusCode());
            assertEquals(1.5, Json.parse(a.body()).get("price").asDouble());
        } finally {
            jdbc.update("DELETE FROM catalog_builds");
        }
    }

    @Test
    void adminBuildReturns409WhileRunning() throws Exception {
        jdbc.update("DELETE FROM catalog_builds");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(catalogBuilder.build()).thenAnswer(inv -> {
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            throw new IllegalStateException("stub build: nothing to write");
        });
        try {
            HttpResponse<String> first = post("/api/admin/build");
            assertEquals(202, first.statusCode(), first.body());
            assertEquals(false, Json.parse(first.body()).get("deploy").asBoolean());
            assertTrue(entered.await(10, TimeUnit.SECONDS), "build started");
            assertTrue(daily.isRunning());

            HttpResponse<String> second = post("/api/admin/build");
            assertEquals(409, second.statusCode());
            assertEquals("already running", Json.parse(second.body()).get("status").asString());
            assertTrue(Json.parse(get("/api/status").body()).get("daily").get("running").asBoolean());
        } finally {
            release.countDown();
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (daily.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertFalse(daily.isRunning());
        assertTrue(daily.lastResult().startsWith("failed"), daily.lastResult());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM catalog_builds", Integer.class));
    }

    @Test
    void liveSnapshot() throws Exception {
        HttpResponse<String> r = get("/api/live");
        assertEquals(200, r.statusCode());
        JsonNode s = Json.parse(r.body());
        assertTrue(s.has("quotes"));
        assertTrue(s.has("timing"));
    }
}
