package hub.daily;

import hub.catalog.Universe;
import hub.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the catalog (same JSON shape the site reads): fetch sources, compute metrics, merge research. */
@Component
public class CatalogBuilder {
    private static final Logger log = LoggerFactory.getLogger(CatalogBuilder.class);

    public record Result(Map<String, Object> catalog, Map<String, Object> series) {}

    private final Universe u;
    private final Sources src;
    private final Series series;
    private final SellProfiles sell;

    public CatalogBuilder(Universe u, Sources src, Series series, SellProfiles sell) {
        this.u = u;
        this.src = src;
        this.series = series;
        this.sell = sell;
    }

    public Result build() {
        Map<String, Object> seriesOut = new LinkedHashMap<>();
        List<Map<String, Object>> assets = new ArrayList<>();
        Map<String, Map<String, Double>> weekly = new HashMap<>();

        // ---- market assets ----
        for (Universe.Asset a : u.assets) {
            Sources.YahooMeta meta = src.yahoo(a);
            Series.Points w = series.load(a.id(), 'w'), d = series.load(a.id(), 'd');
            if (meta == null || w.size() < 10) {
                log.warn("skipping {}: no history", a.symbol());
                continue;
            }
            Map<String, Object> m = assetMetrics(a, meta, w, d, weekly);
            assets.add(m);
            seriesOut.put(a.id(), monthly(w));
        }
        // correlation with the S&P 500 ETF, weekly returns, 3 years
        Map<String, Double> base = weekly.getOrDefault("spy", Map.of());
        for (Map<String, Object> a : assets) {
            Map<String, Double> mine = weekly.getOrDefault((String) a.get("id"), Map.of());
            List<String> keys = mine.keySet().stream().filter(base::containsKey).sorted().toList();
            List<Double> xs = Metrics.logReturns(keys.stream().mapToDouble(base::get).toArray());
            List<Double> ys = Metrics.logReturns(keys.stream().mapToDouble(mine::get).toArray());
            Double c = xs.size() > 30 ? Metrics.pearson(xs, ys) : null;
            a.put("corr", c == null ? null : Metrics.round2(c));
        }

        // ---- house prices (BIS via FRED) + US days on market ----
        Series.Points dom = src.fred("us_days_on_market", "MEDDAYONMARUS", 'm');
        Double usDom = dom.isEmpty() ? null : dom.v()[dom.size() - 1];
        String usDomAsof = dom.isEmpty() ? null : Metrics.monYear(dom.t()[dom.size() - 1]);
        List<Map<String, Object>> houses = new ArrayList<>();
        for (Universe.House h : u.houses) {
            Series.Points q = src.fred(h.id(), h.series(), 'q');
            if (q.size() < 8) continue;
            houses.add(houseMetrics(h, q));
            Series.Points tail = tail(q, 20);
            seriesOut.put(h.id(), Map.of("m", months(tail), "v", values(tail)));
        }

        List<Map<String, Object>> all = new ArrayList<>(assets);
        all.addAll(houses);
        for (Map<String, Object> a : all) sell.apply(a, usDom, usDomAsof);

        List<Map<String, Object>> deposits = src.deposits();
        List<Map<String, Object>> defi = src.defi(u.defi);
        Map<String, JsonNode> research = src.research();
        log.info("sources: {} assets, {} houses, {} countries, {} pools, {} research ways", assets.size(), houses.size(), deposits.size(), defi.size(), research.size());

        // ---- ways ----
        Map<String, List<Map<String, Object>>> byWay = new LinkedHashMap<>();
        for (Map<String, Object> a : all) byWay.computeIfAbsent((String) a.get("way"), k -> new ArrayList<>()).add(a);
        List<Map<String, Object>> ways = new ArrayList<>();
        for (Universe.Way way : u.ways) ways.add(wayEntry(way, byWay.getOrDefault(way.name(), List.of()), deposits, defi, research.get(way.name())));

        // ---- groups ----
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Universe.Group g : u.groups) {
            List<Map<String, Object>> gw = ways.stream().filter(w -> g.slug().equals(w.get("group"))).toList();
            List<Double> y1 = new ArrayList<>(), sw = new ArrayList<>();
            double v30 = 0;
            int maxLiq = 0;
            for (Map<String, Object> w : gw) {
                Double y = metricValue(w, "ret_1y"), s = metricValue(w, "swing"), v = metricValue(w, "vol30");
                if (y != null) y1.add(y);
                if (s != null) sw.add(s);
                if (v != null) v30 += v;
                maxLiq = Math.max(maxLiq, (Integer) w.get("liq"));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", g.slug());
            m.put("name", g.name());
            m.put("note", g.note());
            m.put("img", g.img());
            m.put("pos", g.pos());
            m.put("about", g.about());
            m.put("n", gw.size());
            m.put("ret_1y", y1.isEmpty() ? null : Metrics.round1(Metrics.median(y1)));
            m.put("ret_1y_n", y1.size());
            m.put("swing", sw.isEmpty() ? null : Metrics.round1(Metrics.median(sw)));
            m.put("vol30", v30 > 0 ? v30 : null);
            m.put("sells", maxLiq);
            m.put("ways", gw.stream().map(w -> w.get("slug")).toList());
            groups.add(m);
        }
        Map<String, String> groupOfWay = new HashMap<>();
        for (Map<String, Object> w : ways) groupOfWay.put((String) w.get("name"), (String) w.get("group"));
        for (Map<String, Object> a : all) {
            a.put("group", groupOfWay.get(a.get("way")));
            a.put("way_slug", Universe.slug((String) a.get("way")));
            a.put("live", u.binance.containsKey(a.get("id")) || u.kraken.containsKey(a.get("id")));
        }

        // ---- meta ----
        Set<String> sources = new LinkedHashSet<>(List.of("Yahoo Finance", "FRED / BIS", "World Bank", "DefiLlama", "Binance", "Kraken"));
        for (Map<String, Object> w : ways)
            for (Object v : ((Map<?, ?>) w.get("metrics")).values())
                if (v instanceof Map<?, ?> mv && mv.get("source") instanceof String s) sources.add(s.split(",")[0].split("\\(")[0].trim());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("built", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")));
        meta.put("n_ways", ways.size());
        meta.put("n_assets", all.size());
        meta.put("n_countries", deposits.size());
        meta.put("n_sources", sources.size());
        meta.put("backend", "java");

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("meta", meta);
        catalog.put("groups", groups);
        catalog.put("ways", ways);
        catalog.put("assets", all);
        catalog.put("deposits", deposits);
        return new Result(catalog, seriesOut);
    }

    // ------------------------------------------------------------------

    private Map<String, Object> assetMetrics(Universe.Asset a, Sources.YahooMeta meta, Series.Points w, Series.Points d,
                                             Map<String, Map<String, Double>> weekly) {
        long lastT = d.isEmpty() ? w.t()[w.size() - 1] : d.t()[d.size() - 1];
        double last = w.v()[w.size() - 1];
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("name", a.name());
        m.put("symbol", a.symbol());
        m.put("way", a.way());
        m.put("sub", a.sub());
        m.put("min", a.min());
        double price = meta.price() != null ? meta.price() : (d.isEmpty() ? last : d.v()[d.size() - 1]);
        m.put("price", Math.round(price * 1e6) / 1e6);
        m.put("currency", meta.currency() == null ? "USD" : meta.currency());
        m.put("chg_30d", d.isEmpty() ? null : Metrics.pct(Metrics.valueAt(d.t(), d.v(), lastT - 30 * 86400), d.v()[d.size() - 1]));
        m.put("ret_1y", Metrics.pct(Metrics.valueAt(w.t(), w.v(), lastT - Metrics.YEAR_S), last));
        m.put("ret_3y", Metrics.annOver(w.t(), w.v(), lastT, last, 3));
        m.put("ret_5y", Metrics.annOver(w.t(), w.v(), lastT, last, 5));
        m.put("ret_10y", Metrics.annOver(w.t(), w.v(), lastT, last, 10));
        // swing: annualised volatility of weekly returns, last 3 years
        long cut = (long) (lastT - 3 * Metrics.YEAR_S);
        List<Double> recent = new ArrayList<>();
        Map<String, Double> wk = new LinkedHashMap<>();
        for (int i = 0; i < w.size(); i++)
            if (w.t()[i] >= cut) {
                recent.add(w.v()[i]);
                wk.put(Metrics.weekKey(w.t()[i]), w.v()[i]);
            }
        weekly.put(a.id(), wk);
        List<Double> lr = Metrics.logReturns(recent.stream().mapToDouble(x -> x).toArray());
        m.put("swing", lr.size() > 20 ? Metrics.round1(Metrics.pstdev(lr) * Math.sqrt(52) * 100) : null);
        Metrics.Drawdown dd = Metrics.drawdown(w.t(), w.v());
        m.put("dd", dd.pct());
        m.put("dd_span", dd.span());
        boolean usdVolume = "CRYPTOCURRENCY".equals(meta.instrumentType()); // Yahoo reports crypto volume in USD already
        double vol30 = 0;
        for (int i = 0; i < d.size(); i++)
            if (d.t()[i] > lastT - 30 * 86400 && !Double.isNaN(d.vol()[i])) vol30 += d.vol()[i] * (usdVolume ? 1 : d.v()[i]);
        m.put("vol30_usd", vol30 > 0 && "USD".equals(meta.currency()) ? Math.round(vol30) : null);
        m.put("history_from", Metrics.monYear(w.t()[0]));
        m.put("asof", Metrics.isoDate(lastT));
        m.put("source", "Yahoo Finance");
        m.put("url", "https://finance.yahoo.com/quote/" + a.symbol());
        m.put("trust", "market");
        String type = meta.instrumentType() == null ? "" : meta.instrumentType();
        m.put("total_return", type.equals("ETF") || type.equals("EQUITY") || type.equals("MUTUALFUND"));
        m.put("kind", meta.instrumentType());
        return m;
    }

    private Map<String, Object> houseMetrics(Universe.House h, Series.Points q) {
        long lastT = q.t()[q.size() - 1];
        double last = q.v()[q.size() - 1];
        Series.Points tail = tail(q, 10);
        List<Double> lr = Metrics.logReturns(tail.v());
        Metrics.Drawdown dd = Metrics.drawdown(q.t(), q.v());
        ZonedDateTime ld = Instant.ofEpochSecond(lastT).atZone(ZoneOffset.UTC);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.id());
        m.put("name", h.country() + " house prices");
        m.put("country", h.country());
        m.put("symbol", h.series());
        m.put("way", "Apartments and houses");
        m.put("sub", h.country() + " · residential price index");
        m.put("min", "price of a home");
        m.put("price", Metrics.round1(last));
        m.put("currency", "index");
        m.put("chg_30d", null);
        m.put("ret_1y", Metrics.pct(Metrics.valueAt(q.t(), q.v(), lastT - Metrics.YEAR_S), last));
        m.put("ret_3y", Metrics.annOver(q.t(), q.v(), lastT, last, 3));
        m.put("ret_5y", Metrics.annOver(q.t(), q.v(), lastT, last, 5));
        m.put("ret_10y", Metrics.annOver(q.t(), q.v(), lastT, last, 10));
        m.put("swing", lr.size() > 8 ? Metrics.round1(Metrics.pstdev(lr) * 2 * 100) : null);
        m.put("dd", dd.pct());
        m.put("dd_span", dd.span());
        m.put("vol30_usd", null);
        m.put("corr", null);
        m.put("history_from", String.valueOf(Instant.ofEpochSecond(q.t()[0]).atZone(ZoneOffset.UTC).getYear()));
        m.put("asof", "Q" + ((ld.getMonthValue() - 1) / 3 + 1) + " " + ld.getYear());
        m.put("source", "BIS residential property prices via FRED");
        m.put("url", "https://fred.stlouisfed.org/series/" + h.series());
        m.put("trust", "index");
        m.put("total_return", false);
        m.put("kind", "INDEX");
        return m;
    }

    private Map<String, Object> wayEntry(Universe.Way way, List<Map<String, Object>> tracked, List<Map<String, Object>> deposits,
                                         List<Map<String, Object>> defi, JsonNode research) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("slug", Universe.slug(way.name()));
        w.put("name", way.name());
        w.put("group", way.group());
        w.put("entry", way.entry());
        w.put("liq", way.liq());
        w.put("risk", way.risk());
        w.put("desc", way.desc());
        w.put("assets", tracked.stream().map(a -> a.get("id")).toList());
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> yields = defi.stream().filter(p -> way.name().equals(p.get("way"))).toList();
        w.put("yields", yields);

        if (way.name().equals("Apartments and houses") && !tracked.isEmpty()) {
            for (String k : List.of("ret_1y", "ret_5y", "ret_10y")) {
                List<Double> vals = tracked.stream().map(a -> (Double) a.get(k)).filter(x -> x != null).toList();
                if (!vals.isEmpty())
                    m.put(k, metric(Metrics.round1(Metrics.median(vals)), "BIS house price indexes, median of " + vals.size() + " countries",
                            "https://data.bis.org/topics/RPP", (String) tracked.get(0).get("asof"), "index", null, "nominal, local currency"));
            }
            List<Double> sw = tracked.stream().map(a -> (Double) a.get("swing")).filter(x -> x != null).toList();
            if (!sw.isEmpty())
                m.put("swing", metric(Metrics.round1(Metrics.median(sw)), "BIS house price indexes, median", "https://data.bis.org/topics/RPP", (String) tracked.get(0).get("asof"), "index", null, null));
        } else if (!tracked.isEmpty()) {
            Map<String, Object> rep = tracked.get(0);
            String note = "representative asset" + (Boolean.TRUE.equals(rep.get("total_return")) ? ", total return incl. dividends" : "");
            for (String k : List.of("ret_1y", "ret_5y", "ret_10y", "swing", "dd", "corr"))
                if (rep.get(k) != null)
                    m.put(k, metric(rep.get(k), rep.get("name") + " (" + rep.get("symbol") + "), " + rep.get("source"), (String) rep.get("url"), (String) rep.get("asof"), (String) rep.get("trust"), null, note));
            double v30 = 0;
            int n = 0;
            for (Map<String, Object> a : tracked)
                if (a.get("vol30_usd") instanceof Number num) {
                    v30 += num.doubleValue();
                    n++;
                }
            if (n > 0) m.put("vol30", metric(v30, "sum of " + n + " tracked assets, Yahoo Finance", null, (String) rep.get("asof"), "market", null, null));
        }
        if (way.name().equals("Savings and deposits") && !deposits.isEmpty()) {
            List<Double> rates = deposits.stream().map(x -> (Double) x.get("rate")).toList();
            List<Double> reals = deposits.stream().map(x -> (Double) x.get("real")).filter(x -> x != null).toList();
            String year = deposits.stream().map(x -> (String) x.get("rate_year")).max(String::compareTo).orElse(null);
            m.put("yield", metric(Metrics.round2(Metrics.median(rates)), "World Bank, deposit interest rate, median of " + rates.size() + " countries",
                    "https://data.worldbank.org/indicator/FR.INR.DPST", year, "index", null, null));
            m.put("real_yield", metric(Metrics.round2(Metrics.median(reals)), "World Bank: deposit rate minus inflation, median",
                    "https://data.worldbank.org/indicator/FP.CPI.TOTL.ZG", year, "index", null, null));
        }
        if (!yields.isEmpty()) {
            Map<String, Object> best = yields.get(0);
            Object v = best.get("apy_30d") != null ? best.get("apy_30d") : best.get("apy");
            m.put("yield", metric(v, best.get("name") + ", DefiLlama (30-day average APY)", (String) best.get("url"), (String) best.get("asof"), "market", null, null));
        }
        List<Object> examples = new ArrayList<>();
        Object note = null;
        if (research != null) {
            Map<String, String> rename = Map.of("ret_5y_ann", "ret_5y", "ret_10y_ann", "ret_10y", "volatility", "swing", "max_drawdown", "dd");
            JsonNode rm = research.get("metrics");
            if (rm != null)
                for (Map.Entry<String, JsonNode> e : rm.properties()) {
                    if (e.getValue() == null || e.getValue().isNull()) continue;
                    String key = rename.getOrDefault(e.getKey(), e.getKey());
                    Object val = Json.M.convertValue(e.getValue(), Map.class);
                    Object existing = m.get(key);
                    if (existing instanceof Map<?, ?> ex && "market".equals(ex.get("trust"))) m.put(key + "_report", val);
                    else if (existing == null) m.put(key, val);
                }
            if (research.get("examples") != null) examples = Json.M.convertValue(research.get("examples"), List.class);
            note = Json.str(research, "note");
        }
        w.put("metrics", m);
        w.put("examples", examples);
        w.put("note", note);
        return w;
    }

    private static Map<String, Object> metric(Object value, String source, String url, String asof, String trust, String period, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("source", source);
        m.put("url", url);
        m.put("asof", asof);
        m.put("trust", trust);
        m.put("period", period);
        m.put("note", note);
        return m;
    }

    private static Double metricValue(Map<String, Object> way, String key) {
        Object m = ((Map<?, ?>) way.get("metrics")).get(key);
        return m instanceof Map<?, ?> mm && mm.get("value") instanceof Number n ? n.doubleValue() : null;
    }

    /** Last close of each month, for charts. */
    private static Map<String, Object> monthly(Series.Points w) {
        Map<String, Double> months = new LinkedHashMap<>();
        for (int i = 0; i < w.size(); i++) months.put(Metrics.yearMonth(w.t()[i]), Math.round(w.v()[i] * 1e6) / 1e6);
        return Map.of("m", new ArrayList<>(months.keySet()), "v", new ArrayList<>(months.values()));
    }

    private static Series.Points tail(Series.Points p, int years) {
        if (p.isEmpty()) return p;
        long cut = (long) (p.t()[p.size() - 1] - years * Metrics.YEAR_S);
        int from = 0;
        while (from < p.size() && p.t()[from] < cut) from++;
        return new Series.Points(java.util.Arrays.copyOfRange(p.t(), from, p.size()), java.util.Arrays.copyOfRange(p.v(), from, p.size()), null);
    }

    private static List<String> months(Series.Points p) {
        List<String> out = new ArrayList<>();
        for (long t : p.t()) out.add(Metrics.yearMonth(t));
        return out;
    }

    private static List<Double> values(Series.Points p) {
        List<Double> out = new ArrayList<>();
        for (double v : p.v()) out.add(v);
        return out;
    }
}
