package hub.api;

import hub.daily.DailyJob;
import hub.live.LiveStore;
import hub.live.Timing;
import hub.live.WsFeed;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final JdbcTemplate jdbc;
    private final LiveStore live;
    private final Timing timing;
    private final List<WsFeed> feeds;
    private final DailyJob daily;

    public ApiController(JdbcTemplate jdbc, LiveStore live, Timing timing, List<WsFeed> feeds, DailyJob daily) {
        this.jdbc = jdbc;
        this.live = live;
        this.timing = timing;
        this.feeds = feeds;
        this.daily = daily;
    }

    /** The latest built catalog (same JSON as data/catalog.json). */
    @GetMapping(value = "/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> catalog() {
        List<String> rows = jdbc.queryForList("SELECT json::text FROM catalog_builds ORDER BY id DESC LIMIT 1", String.class);
        if (rows.isEmpty()) return ResponseEntity.status(503).body("{\"error\":\"no catalog built yet\"}");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))).body(rows.get(0));
    }

    /** One asset from the latest catalog plus its live quote. */
    @GetMapping(value = "/assets/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> asset(@PathVariable String id) {
        List<String> rows = jdbc.queryForList("""
                SELECT a::text FROM catalog_builds c, jsonb_array_elements(c.json->'assets') a
                WHERE c.id = (SELECT max(id) FROM catalog_builds) AND a->>'id' = ?""", String.class, id);
        return rows.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(rows.get(0));
    }

    /**
     * Price series. range=1d|7d|30d -> our own live candles (1-minute, hourly, daily);
     * otherwise the long history from daily sources (weekly points, quarterly for house prices).
     */
    @GetMapping("/series/{id}")
    public Map<String, Object> series(@PathVariable String id, @RequestParam(defaultValue = "10y") String range) {
        String sql;
        switch (range) {
            case "1d" -> sql = "SELECT ts, close FROM candles_1m WHERE asset_id = ? AND ts > now() - interval '1 day' ORDER BY ts";
            case "7d" -> sql = "SELECT ts, close FROM candles_1h WHERE asset_id = ? AND ts > now() - interval '7 days' ORDER BY ts";
            case "30d" -> sql = "SELECT ts, close FROM candles_1d WHERE asset_id = ? AND ts > now() - interval '30 days' ORDER BY ts";
            default -> sql = "SELECT ts, value FROM price_points WHERE asset_id = ? AND kind IN ('w','q') ORDER BY ts";
        }
        List<Object[]> pts = jdbc.query(sql, (rs, i) -> new Object[]{rs.getTimestamp(1).toInstant().toString(), rs.getDouble(2)}, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("range", range);
        out.put("t", pts.stream().map(p -> p[0]).toList());
        out.put("v", pts.stream().map(p -> p[1]).toList());
        return out;
    }

    @GetMapping("/live")
    public Map<String, Object> liveSnapshot() {
        return Map.of("quotes", live.all(), "timing", timing.summary());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> f = new LinkedHashMap<>();
        for (WsFeed feed : feeds)
            f.put(feed.name(), Map.of("state", feed.state(), "messages", feed.messages(), "last_message_ms_ago", feed.lastMessageAgeMs()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feeds", f);
        out.put("live_quotes", live.all().size());
        out.put("timing", timing.summary());
        out.put("candles_1m_rows", jdbc.queryForObject("SELECT count(*) FROM candles_1m", Long.class));
        out.put("daily", Map.of("running", daily.isRunning(), "last", daily.lastResult()));
        return out;
    }

    /** Start a daily build now (local only: the server listens on 127.0.0.1). */
    @PostMapping("/admin/build")
    public ResponseEntity<Map<String, Object>> build(@RequestParam(defaultValue = "false") boolean deploy) {
        if (daily.isRunning()) return ResponseEntity.status(409).body(Map.of("status", "already running"));
        Thread.ofVirtual().start(() -> daily.run(deploy));
        return ResponseEntity.accepted().body(Map.of("status", "started", "deploy", deploy));
    }
}
