package hub.daily;

import hub.HubProperties;
import hub.catalog.Universe;
import hub.util.Http;
import hub.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Daily data sources. Every fetch stores what it got, so a failed source falls back to the last good data. */
@Component
public class Sources {
    private static final Logger log = LoggerFactory.getLogger(Sources.class);
    private final Series series;
    private final JdbcTemplate jdbc;
    private final HubProperties props;

    public Sources(Series series, JdbcTemplate jdbc, HubProperties props) {
        this.series = series;
        this.jdbc = jdbc;
        this.props = props;
    }

    // ---------- small cache for slow or flaky sources ----------

    public String cached(String key, Duration maxAge, IOSupplier fetch) {
        List<Map<String, Object>> row = jdbc.queryForList("SELECT fetched_at, body FROM source_cache WHERE key = ?", key);
        if (!row.isEmpty()) {
            Instant at = ((Timestamp) row.get(0).get("fetched_at")).toInstant();
            if (at.isAfter(Instant.now().minus(maxAge))) return (String) row.get(0).get("body");
        }
        try {
            String body = fetch.get();
            jdbc.update("""
                    INSERT INTO source_cache(key, fetched_at, body) VALUES (?, now(), ?)
                    ON CONFLICT (key) DO UPDATE SET fetched_at = now(), body = EXCLUDED.body""", key, body);
            return body;
        } catch (IOException e) {
            log.warn("{}: {}; using stored copy", key, e.getMessage());
            return row.isEmpty() ? null : (String) row.get(0).get("body");
        }
    }

    public interface IOSupplier {
        String get() throws IOException;
    }

    // ---------- Yahoo Finance: weekly adjusted history (10.6 years) + 3 months of daily closes ----------

    public record YahooMeta(String currency, String instrumentType, Double price, long lastT) {}

    /** Fetches and stores points; returns metadata (from cache if Yahoo fails), or null if never fetched. */
    public YahooMeta yahoo(Universe.Asset a) {
        String q = URLEncoder.encode(a.symbol(), StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        long p1 = now - (long) (10.6 * 365 * 86400);
        try {
            JsonNode wk = chart(Http.get("https://query1.finance.yahoo.com/v8/finance/chart/" + q + "?period1=" + p1 + "&period2=" + now + "&interval=1wk&events=div"));
            JsonNode dy = chart(Http.get("https://query1.finance.yahoo.com/v8/finance/chart/" + q + "?range=3mo&interval=1d"));
            series.upsert(a.id(), 'w', points(wk, true));
            series.upsert(a.id(), 'd', points(dy, false));
            JsonNode m = wk.get("meta");
            Series.Points d = points(dy, false);
            long lastT = d.isEmpty() ? now : d.t()[d.size() - 1];
            YahooMeta meta = new YahooMeta(Json.str(m, "currency"), Json.str(m, "instrumentType"), Json.num(m, "regularMarketPrice"), lastT);
            jdbc.update("""
                    INSERT INTO source_cache(key, fetched_at, body) VALUES (?, now(), ?)
                    ON CONFLICT (key) DO UPDATE SET fetched_at = now(), body = EXCLUDED.body""", "yahoo.meta." + a.id(), Json.write(meta));
            Http.sleep(250);
            return meta;
        } catch (Exception e) {
            log.warn("yahoo {} failed: {}", a.symbol(), e.getMessage());
            List<String> rows = jdbc.queryForList("SELECT body FROM source_cache WHERE key = ?", String.class, "yahoo.meta." + a.id());
            return rows.isEmpty() ? null : Json.M.readValue(rows.get(0), YahooMeta.class);
        }
    }

    private static JsonNode chart(String body) {
        JsonNode r = Json.parse(body).path("chart").path("result");
        if (!r.isArray() || r.isEmpty()) throw new IllegalStateException("no chart result");
        return r.get(0);
    }

    private static Series.Points points(JsonNode r, boolean adjusted) {
        JsonNode ts = r.get("timestamp");
        JsonNode quote = r.path("indicators").path("quote").get(0);
        JsonNode adj = r.path("indicators").path("adjclose");
        JsonNode closes = adjusted && adj.isArray() && !adj.isEmpty() ? adj.get(0).get("adjclose") : quote.get("close");
        JsonNode vols = quote.get("volume");
        List<long[]> t = new ArrayList<>();
        List<double[]> v = new ArrayList<>();
        if (ts != null)
            for (int i = 0; i < ts.size(); i++) {
                JsonNode c = closes.get(i);
                if (c == null || c.isNull()) continue;
                JsonNode vo = vols == null ? null : vols.get(i);
                t.add(new long[]{ts.get(i).asLong()});
                v.add(new double[]{c.asDouble(), vo == null || vo.isNull() ? Double.NaN : vo.asDouble()});
            }
        long[] tt = new long[t.size()];
        double[] vv = new double[t.size()], vol = new double[t.size()];
        for (int i = 0; i < tt.length; i++) {
            tt[i] = t.get(i)[0];
            vv[i] = v.get(i)[0];
            vol[i] = v.get(i)[1];
        }
        return new Series.Points(tt, vv, vol);
    }

    // ---------- FRED (BIS house prices, Realtor.com days on market) ----------

    /** Fetch a FRED series as CSV, store it as points of the given kind, return what is stored. */
    public Series.Points fred(String assetId, String seriesId, char kind) {
        String body = cached("fred." + seriesId, Duration.ofDays(1),
                () -> Http.get("https://fred.stlouisfed.org/graph/fredgraph.csv?id=" + seriesId, Http.CURL_UA, Duration.ofSeconds(40)));
        if (body != null) {
            List<long[]> t = new ArrayList<>();
            List<Double> v = new ArrayList<>();
            for (String line : body.split("\\R")) {
                String[] p = line.split(",");
                if (p.length != 2) continue;
                try {
                    t.add(new long[]{LocalDate.parse(p[0].trim()).atStartOfDay(ZoneOffset.UTC).toEpochSecond()});
                    v.add(Double.parseDouble(p[1].trim()));
                } catch (Exception ignored) {
                    // header or missing value
                }
            }
            long[] tt = new long[t.size()];
            double[] vv = new double[t.size()];
            for (int i = 0; i < tt.length; i++) {
                tt[i] = t.get(i)[0];
                vv[i] = v.get(i);
            }
            if (tt.length > 0) series.upsert(assetId, kind, new Series.Points(tt, vv, null));
        }
        return series.load(assetId, kind);
    }

    // ---------- World Bank: deposit rate and inflation, latest year per country ----------

    public List<Map<String, Object>> deposits() {
        Map<String, JsonNode> dep = worldBank("FR.INR.DPST"), inf = worldBank("FP.CPI.TOTL.ZG");
        List<Map<String, Object>> out = new ArrayList<>();
        dep.forEach((iso, r) -> {
            JsonNode i = inf.get(iso);
            double rate = r.get("value").asDouble();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("iso", iso);
            m.put("country", r.path("country").path("value").asString());
            m.put("rate", Metrics.round2(rate));
            m.put("rate_year", Json.str(r, "date"));
            m.put("infl", i == null ? null : Metrics.round2(i.get("value").asDouble()));
            m.put("infl_year", i == null ? null : Json.str(i, "date"));
            m.put("real", i == null ? null : Metrics.round2(rate - i.get("value").asDouble()));
            out.add(m);
        });
        out.sort((a, b) -> ((String) a.get("country")).compareTo((String) b.get("country")));
        return out;
    }

    private Map<String, JsonNode> worldBank(String indicator) {
        String body = cached("worldbank." + indicator, Duration.ofDays(7),
                () -> Http.get("https://api.worldbank.org/v2/country/all/indicator/" + indicator + "?format=json&mrnev=1&per_page=400"));
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (body == null) return out;
        JsonNode rows = Json.parse(body).get(1);
        if (rows != null)
            for (JsonNode r : rows) {
                String iso = Json.str(r, "countryiso3code");
                if (iso != null && !iso.isBlank() && r.get("value") != null && !r.get("value").isNull()) out.put(iso, r);
            }
        return out;
    }

    // ---------- DefiLlama: staking and lending yields ----------

    public List<Map<String, Object>> defi(List<Universe.Defi> wanted) {
        String body = cached("defillama.pools", Duration.ofHours(6), () -> Http.get("https://yields.llama.fi/pools"));
        List<Map<String, Object>> out = new ArrayList<>();
        if (body == null) return out;
        JsonNode pools = Json.parse(body).get("data");
        for (Universe.Defi d : wanted) {
            JsonNode best = null;
            for (JsonNode p : pools) {
                if (!d.project().equals(Json.str(p, "project"))) continue;
                if (d.chain() != null && !d.chain().equals(Json.str(p, "chain"))) continue;
                if (d.symbol() != null && !d.symbol().equals(Json.str(p, "symbol"))) continue;
                if (best == null || Json.num(p, "tvlUsd") > Json.num(best, "tvlUsd")) best = p;
            }
            if (best == null) {
                log.warn("no DefiLlama pool for {}", d.name());
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("way", d.way());
            m.put("name", d.name());
            Double apy = Json.num(best, "apy"), apy30 = Json.num(best, "apyMean30d");
            m.put("apy", apy == null ? 0 : Metrics.round2(apy));
            m.put("apy_30d", apy30 == null ? null : Metrics.round2(apy30));
            m.put("tvl_usd", Math.round(Json.num(best, "tvlUsd")));
            m.put("chain", Json.str(best, "chain"));
            m.put("source", "DefiLlama yields");
            m.put("url", "https://defillama.com/yields/pool/" + Json.str(best, "pool"));
            m.put("asof", LocalDate.now(ZoneOffset.UTC).toString());
            out.add(m);
        }
        return out;
    }

    // ---------- hand-collected research figures (data/research/*.json) ----------

    public Map<String, JsonNode> research() {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        Path dir = Path.of(props.researchDir());
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList())
                for (JsonNode w : Json.parse(Files.readString(f)).get("ways")) out.put(Json.str(w, "way"), w);
        } catch (IOException e) {
            log.warn("research: {}", e.getMessage());
        }
        return out;
    }
}
