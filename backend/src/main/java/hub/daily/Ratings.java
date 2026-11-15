package hub.daily;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Risk band, liquidity 1–5 and entry level 1–4 for each way, computed from data.
 * Source priority: our own measurements of assets that ARE the way (shares, ETFs, coins) →
 * published research figures for the market → a listed proxy (risk only) → the editorial value, labelled.
 */
public final class Ratings {
    private Ratings() {}

    /** Ways whose tracked assets are listed stand-ins, not the investment itself (a hedge fund ETF is not a hedge fund). */
    static final Set<String> PROXY = Set.of(
            "Hedge funds", "Market-making and quant funds", "Private loans to businesses", "Private credit funds",
            "Private equity", "Pre-IPO shares", "Apartments and houses", "Rental property shares", "Commercial property",
            "Farmland", "Forest and timber", "Parking and storage units", "Patent licensing", "Virtual land", "Fan tokens");

    /** Ways whose published indexes come from appraisals, which smooth prices and understate risk. */
    static final Set<String> APPRAISAL = Set.of("Venture capital funds", "Private equity", "Funds of funds", "Private credit funds",
            "Private loans to businesses", "Commercial property", "Farmland", "Forest and timber", "Parking and storage units");

    // thresholds, documented on methodology.html
    static final double HIGH_SWING = 35, HIGH_DD = -60, LOW_SWING = 12, LOW_DD = -25;
    static final double[] ENTRY_USD = {100, 1_000, 25_000};          // ≤100 → 1, ≤1k → 2, ≤25k → 3, else 4
    static final double LIQ5_VOLUME = 1e9, LIQ4_VOLUME = 1e7;          // 30-day traded value for exchange assets

    @SuppressWarnings("unchecked")
    public static Map<String, Object> apply(Map<String, Object> way, List<Map<String, Object>> tracked, Map<String, Object> metrics) {
        String name = (String) way.get("name");
        boolean proxy = PROXY.contains(name);
        List<Map<String, Object>> direct = proxy ? List.of() : tracked.stream().filter(a -> !"index".equals(a.get("currency"))).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("risk", risk(way, direct, proxy ? tracked : List.of(), metrics));
        out.put("liq", liquidity(name, way, direct, tracked, metrics));
        out.put("entry", entry(way, direct, metrics));
        way.put("ratings", out);
        for (String k : List.of("risk", "liq", "entry")) way.put(k, ((Map<String, Object>) out.get(k)).get("value"));
        return out;
    }

    // ---------------- risk ----------------

    static Map<String, Object> risk(Map<String, Object> way, List<Map<String, Object>> direct, List<Map<String, Object>> proxies, Map<String, Object> m) {
        Double s = median(direct, "swing"), d = median(direct, "dd");
        String src = "measured", basis;
        if (s == null && d == null) {
            // published figures for the market itself; for proxy ways they sit under *_report next to the listed proxy
            // only independent figures (indexes, reports): a platform's own numbers or rough estimates do not set risk
            s = independent(m.get("swing_report"));
            d = independent(m.get("dd_report"));
            if (s == null && !isMarket(m.get("swing"))) s = independent(m.get("swing"));
            if (d == null && !isMarket(m.get("dd"))) d = independent(m.get("dd"));
            src = "research";
        }
        // lending and startups: no price index, but published default or failure rates
        Double def = independent(m.get("default_rate"));
        if (s == null && d == null && def != null) {
            String band = def >= 20 ? "high" : def >= 3 ? "medium" : "low";
            return rating(band, "research", String.format(Locale.ROOT, "default or failure rate %.1f%%", def), way.get("risk"));
        }
        if (s == null && d == null && !proxies.isEmpty()) {
            s = median(proxies, "swing");
            d = median(proxies, "dd");
            src = "listed proxy";
        }
        if (s == null && d == null) return rating(way.get("risk"), "editorial", "no published volatility or drawdown yet", way.get("risk"));
        String band;
        if ((s != null && s >= HIGH_SWING) || (d != null && d <= HIGH_DD)) band = "high";
        // "low" needs a measured swing: a mild worst year alone (often one calendar year, or an appraisal index) is not proof
        else if (s != null && s < LOW_SWING && (d == null || d > LOW_DD)) band = "low";
        else band = "medium";
        List<String> parts = new ArrayList<>();
        if (s != null) parts.add(String.format(Locale.ROOT, "swing %.0f%%", s));
        if (d != null) parts.add(String.format(Locale.ROOT, "worst drop %.0f%%", d));
        basis = String.join(", ", parts) + (direct.size() > 1 && "measured".equals(src) ? " (median of " + direct.size() + " assets)" : "");
        if ("research".equals(src) && APPRAISAL.contains((String) way.get("name")))
            basis += "; appraisal-based index, real swings are larger";
        return rating(band, src, basis, way.get("risk"));
    }

    // ---------------- liquidity ----------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> liquidity(String name, Map<String, Object> way, List<Map<String, Object>> direct,
                                         List<Map<String, Object>> tracked, Map<String, Object> m) {
        if (!direct.isEmpty()) {
            boolean fx = direct.stream().allMatch(a -> String.valueOf(a.get("symbol")).endsWith("=X") || String.valueOf(a.get("symbol")).startsWith("DX-Y"));
            if (fx) return rating(5, "measured", "fills in under a second, 24/5; the largest market in the world", way.get("liq"));
            Double vol = median(direct, "vol30_usd");
            Double exec = median(direct.stream().map(a -> (Map<String, Object>) a.get("sell_detail")).filter(x -> x != null).toList(), "exec_s");
            String fill = exec == null || exec >= 1 ? "fills in under a second in trading hours" : String.format(Locale.ROOT, "fills in %.2f s (measured)", exec);
            if (vol == null) return rating(4, "measured", fill + " on an exchange; volume not reported in USD", way.get("liq"));
            int v = vol >= LIQ5_VOLUME ? 5 : vol >= LIQ4_VOLUME ? 4 : 3;
            return rating(v, "measured", fill + "; median " + money(vol) + " traded per asset in 30 days", way.get("liq"));
        }
        // homes: measured days on market where we have it
        if ("Apartments and houses".equals(name)) {
            for (Map<String, Object> a : tracked)
                if (a.get("sell_s") instanceof Number n && n.doubleValue() > 0) {
                    double days = n.doubleValue() / 86400;
                    return rating(liqFromDays(days), "measured", String.format(Locale.ROOT, "%.0f days on market, US median (Realtor.com)", days), way.get("liq"));
                }
        }
        Double days = days(m.get("time_to_sell"));
        if (days != null) {
            String text = m.get("time_to_sell") instanceof Map<?, ?> t && t.get("text") instanceof String s ? s : String.format(Locale.ROOT, "up to %.0f days", days);
            return rating(liqFromDays(days), "research", "time to sell " + shorten(text), way.get("liq"));
        }
        return rating(way.get("liq"), "editorial", "no published time to sell yet", way.get("liq"));
    }

    static int liqFromDays(double days) {
        return days <= 1 ? 4 : days <= 7 ? 3 : days <= 60 ? 2 : 1;
    }

    // ---------------- entry ----------------

    static Map<String, Object> entry(Map<String, Object> way, List<Map<String, Object>> direct, Map<String, Object> m) {
        Double usd = null;
        String src = null, basis = null;
        for (Map<String, Object> a : direct) {
            Double x = usdAmount(String.valueOf(a.get("min")));
            if (x != null && (usd == null || x < usd)) usd = x;
        }
        if (usd != null) {
            src = "measured";
            basis = "you can buy a single share or coin unit: from " + money(usd);
        } else if (m.get("min_ticket") instanceof Map<?, ?> mt) {
            usd = mt.get("usd") instanceof Number n ? n.doubleValue() : null; // structured amounts only: text often mixes fees and prices
            if (usd != null) {
                src = "research";
                basis = "minimum " + money(usd) + (mt.get("text") instanceof String t ? ": " + shorten(t) : "");
            }
        }
        if (usd == null) return rating(way.get("entry"), "editorial", "no published minimum yet", way.get("entry"));
        int level = usd <= ENTRY_USD[0] ? 1 : usd <= ENTRY_USD[1] ? 2 : usd <= ENTRY_USD[2] ? 3 : 4;
        return rating(level, src, basis, way.get("entry"));
    }

    // ---------------- helpers ----------------

    private static Map<String, Object> rating(Object value, String source, String basis, Object editorial) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("value", value);
        r.put("source", source);
        r.put("basis", basis);
        r.put("editorial", editorial);
        return r;
    }

    static Double median(List<Map<String, Object>> xs, String key) {
        List<Double> v = new ArrayList<>();
        for (Map<String, Object> x : xs) if (x.get(key) instanceof Number n && Double.isFinite(n.doubleValue())) v.add(n.doubleValue());
        return v.isEmpty() ? null : Metrics.median(v);
    }

    static Double independent(Object metric) {
        if (!(metric instanceof Map<?, ?> m)) return null;
        Object t = m.get("trust");
        return "index".equals(t) || "report".equals(t) ? num(metric, "value") : null;
    }

    static boolean isMarket(Object metric) {
        return metric instanceof Map<?, ?> m && "market".equals(m.get("trust"));
    }

    static Double num(Object metric, String key) {
        return metric instanceof Map<?, ?> m && m.get(key) instanceof Number n ? n.doubleValue() : null;
    }

    /** Upper bound of a time to sell in days: structured {days_high} first, else parsed from text. */
    static Double days(Object metric) {
        if (!(metric instanceof Map<?, ?> m)) return null;
        if (m.get("days_high") instanceof Number n) return n.doubleValue();
        if (m.get("days_low") instanceof Number n) return n.doubleValue();
        return null; // free text is too ambiguous to rate on (e.g. "entry queue peaked at 71 days")
    }

    private static final Pattern DUR = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(?:[-–—]|to)?\\s*(\\d+(?:[.,]\\d+)?)?\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|weeks?|wks?|months?|mos?|years?|yrs?)\\b",
            Pattern.CASE_INSENSITIVE);

    static Double parseDays(String text) {
        Matcher mt = DUR.matcher(text);
        Double max = null;
        while (mt.find()) {
            double n = Double.parseDouble((mt.group(2) != null ? mt.group(2) : mt.group(1)).replace(',', '.'));
            String u = mt.group(3).toLowerCase(Locale.ROOT);
            double mult = u.startsWith("s") ? 1.0 / 86400 : u.startsWith("mi") ? 1.0 / 1440 : u.startsWith("h") ? 1.0 / 24
                    : u.startsWith("d") ? 1 : u.startsWith("w") ? 7 : u.startsWith("mo") ? 30.4 : 365;
            double d = n * mult;
            if (max == null || d > max) max = d;
        }
        return max;
    }

    private static final Pattern MONEY = Pattern.compile("(US\\$|\\$|€|£|USD\\s?|EUR\\s?|GBP\\s?)\\s?(\\d[\\d,]*(?:\\.\\d+)?)\\s*(k|m|million|thousand|bn|billion)?\\b",
            Pattern.CASE_INSENSITIVE);

    /** Smallest money amount in a text, roughly in USD (EUR × 1.1, GBP × 1.3). */
    static Double usdAmount(String text) {
        if (text == null) return null;
        Matcher m = MONEY.matcher(text);
        Double min = null;
        while (m.find()) {
            double v = Double.parseDouble(m.group(2).replace(",", ""));
            String sfx = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT);
            if (sfx.startsWith("k") || sfx.startsWith("t")) v *= 1e3;
            else if (sfx.startsWith("m")) v *= 1e6;
            else if (sfx.startsWith("b")) v *= 1e9;
            String cur = m.group(1).toUpperCase(Locale.ROOT);
            if (cur.contains("€") || cur.startsWith("EUR")) v *= 1.1;
            else if (cur.contains("£") || cur.startsWith("GBP")) v *= 1.3;
            if (min == null || v < min) min = v;
        }
        return min;
    }

    static String money(double v) {
        if (v >= 1e9) return String.format(Locale.ROOT, "$%.1fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.ROOT, "$%.1fM", v / 1e6);
        if (v >= 1e3) return String.format(Locale.ROOT, "$%,.0f", v);
        return String.format(Locale.ROOT, "$%.0f", v);
    }

    private static String shorten(String s) {
        return s.length() <= 90 ? s : s.substring(0, 87) + "…";
    }
}
