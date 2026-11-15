package hub.daily;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Equal-weight index of a group's tracked assets, rebalanced monthly: each month's return is the average
 * of the monthly returns of the assets that existed that month (so assets with shorter histories still count
 * from when they start). From it: return per year over the last 10 years (5 if the index is shorter) and the
 * best and worst 5-year stretches (rolling 60-month windows).
 */
public final class GroupIndex {
    private GroupIndex() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compute(List<String> assetIds, Map<String, Object> series) {
        TreeMap<String, double[]> sum = new TreeMap<>(); // month -> {sum of returns, count}
        int used = 0;
        for (String id : assetIds) {
            Object s = series.get(id);
            if (!(s instanceof Map<?, ?> sm)) continue;
            List<String> m = (List<String>) sm.get("m");
            List<Double> v = (List<Double>) sm.get("v");
            if (m == null || v == null || m.size() < 13) continue;
            used++;
            for (int i = 1; i < m.size(); i++) {
                double a = v.get(i - 1), b = v.get(i);
                if (a <= 0 || b <= 0) continue;
                double[] acc = sum.computeIfAbsent(m.get(i), k -> new double[2]);
                acc[0] += b / a - 1;
                acc[1]++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idx_assets", used);
        // drop the current, still-forming month and months where only one asset reports in a multi-asset group
        List<Double> rets = new ArrayList<>();
        List<String> months = new ArrayList<>(sum.keySet());
        if (!months.isEmpty()) months.remove(months.size() - 1);
        for (String mo : months) rets.add(sum.get(mo)[0] / sum.get(mo)[1]);
        if (used == 0 || rets.size() < 60) {
            out.put("idx_ret", null);
            out.put("idx_years", null);
            out.put("best5", null);
            out.put("worst5", null);
            out.put("idx_flag", used == 0 ? "no data yet" : "under 5 years of prices");
            return out;
        }
        int years = rets.size() >= 120 ? 10 : 5;
        out.put("idx_ret", Metrics.round1(cagr(rets.subList(rets.size() - years * 12, rets.size()))));
        out.put("idx_years", years);
        double best = Double.NEGATIVE_INFINITY, worst = Double.POSITIVE_INFINITY;
        String bestEnd = null, worstEnd = null;
        for (int end = 60; end <= rets.size(); end++) {
            double r = cagr(rets.subList(end - 60, end));
            if (r > best) {
                best = r;
                bestEnd = months.get(end - 1);
            }
            if (r < worst) {
                worst = r;
                worstEnd = months.get(end - 1);
            }
        }
        out.put("best5", Metrics.round1(best));
        out.put("best5_to", bestEnd);
        out.put("worst5", Metrics.round1(worst));
        out.put("worst5_to", worstEnd);
        out.put("idx_from", months.get(0));
        String flag = null;
        if (used < 3) flag = "little data";
        else if (years == 5) flag = "only 5 years";
        out.put("idx_flag", flag);
        return out;
    }

    /** Compound annual growth from monthly returns, percent. */
    static double cagr(List<Double> monthly) {
        double g = 1;
        for (double r : monthly) g *= 1 + r;
        return (Math.pow(g, 12.0 / monthly.size()) - 1) * 100;
    }
}
