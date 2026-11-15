package hub.daily;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Group figures as a visitor could have earned them: split money equally across the group's tracked assets
 * at the start of a period and hold (no rebalancing, which would inflate returns of volatile groups).
 * Only assets with prices at both ends of the period count.
 *  - idx_ret: return per year over the last 10 years (5 if fewer than 3 assets, or less than half, reach back 10 years)
 *  - best5 / worst5: best and worst such 5-year buy-and-hold stretch, rolling monthly
 */
public final class GroupIndex {
    private GroupIndex() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compute(List<String> assetIds, Map<String, Object> series) {
        List<Map<String, Double>> assets = new ArrayList<>();
        TreeSet<String> months = new TreeSet<>();
        for (String id : assetIds) {
            if (!(series.get(id) instanceof Map<?, ?> sm)) continue;
            List<String> m = (List<String>) sm.get("m");
            List<Double> v = (List<Double>) sm.get("v");
            if (m == null || v == null || m.size() < 13) continue;
            Map<String, Double> byMonth = new HashMap<>();
            for (int i = 0; i < m.size(); i++) if (v.get(i) != null && v.get(i) > 0) byMonth.put(m.get(i), v.get(i));
            assets.add(byMonth);
            months.addAll(byMonth.keySet());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idx_assets", assets.size());
        List<String> ms = new ArrayList<>(months);
        if (ms.size() > 1) ms.remove(ms.size() - 1); // the current month is still forming
        String last = ms.isEmpty() ? null : ms.get(ms.size() - 1);

        Integer years = null;
        Window w10 = window(assets, ms, last, 10), w5 = window(assets, ms, last, 5);
        Window head = null;
        if (w10 != null && w10.n >= 3 && w10.n * 2 >= assets.size()) {
            head = w10;
            years = 10;
        } else if (w5 != null) {
            head = w5;
            years = 5;
        }
        out.put("idx_ret", head == null ? null : Metrics.round1(head.cagr));
        out.put("idx_years", years);
        out.put("idx_n", head == null ? 0 : head.n);

        Window best = null, worst = null;
        for (String end : ms) {
            Window w = window(assets, ms, end, 5);
            if (w == null || w.n < Math.min(3, assets.size())) continue;
            if (best == null || w.cagr > best.cagr) best = w;
            if (worst == null || w.cagr < worst.cagr) worst = w;
        }
        out.put("best5", best == null ? null : Metrics.round1(best.cagr));
        out.put("best5_to", best == null ? null : best.end);
        out.put("worst5", worst == null ? null : Metrics.round1(worst.cagr));
        out.put("worst5_to", worst == null ? null : worst.end);
        // spread of every window as long as the headline period (10 or 5 years): "10 years, worst → best"
        Window rb = null, rw = null;
        if (years != null)
            for (String end : ms) {
                Window w = window(assets, ms, end, years);
                if (w == null || w.n < Math.min(3, assets.size())) continue;
                if (rb == null || w.cagr > rb.cagr) rb = w;
                if (rw == null || w.cagr < rw.cagr) rw = w;
            }
        out.put("range_years", rb == null ? null : years);
        out.put("range_best", rb == null ? null : Metrics.round1(rb.cagr));
        out.put("range_worst", rw == null ? null : Metrics.round1(rw.cagr));
        String flag = null;
        if (assets.isEmpty()) flag = "no data yet";
        else if (head == null) flag = "under 5 years of prices";
        else if (assets.size() < 3) flag = "little data";
        else if (years == 5) flag = "only 5 years";
        out.put("idx_flag", flag);
        return out;
    }

    private record Window(String end, double cagr, int n) {}

    /** Equal money in every asset priced at both ends of the window ending in month {@code end}. */
    private static Window window(List<Map<String, Double>> assets, List<String> months, String end, int years) {
        if (end == null) return null;
        String start = shift(end, -12 * years);
        if (months.isEmpty() || start.compareTo(months.get(0)) < 0) return null;
        double sum = 0;
        int n = 0;
        for (Map<String, Double> a : assets) {
            Double s = a.get(start), e = a.get(end);
            if (s != null && e != null) {
                sum += e / s;
                n++;
            }
        }
        if (n == 0) return null;
        return new Window(end, (Math.pow(sum / n, 1.0 / years) - 1) * 100, n);
    }

    /** "2026-11" shifted by k months. */
    static String shift(String ym, int k) {
        int y = Integer.parseInt(ym.substring(0, 4)), m = Integer.parseInt(ym.substring(5, 7)) - 1 + k;
        y += Math.floorDiv(m, 12);
        m = Math.floorMod(m, 12);
        return String.format("%04d-%02d", y, m + 1);
    }
}
