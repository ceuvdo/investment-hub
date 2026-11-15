package hub.daily;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Return and risk maths, as defined on methodology.html. */
public final class Metrics {
    public static final double YEAR_S = 365.25 * 86400;
    private static final DateTimeFormatter MON_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private Metrics() {}

    /** Last value at or before target (unix seconds), or null. */
    public static Double valueAt(long[] t, double[] v, double target) {
        Double best = null;
        for (int i = 0; i < t.length; i++) {
            if (t[i] <= target) best = v[i];
            else break;
        }
        return best;
    }

    public static Double pct(Double a, Double b) {
        if (a == null || b == null || a == 0) return null;
        return round1((b / a - 1) * 100);
    }

    public static Double ann(Double a, Double b, double years) {
        if (a == null || b == null || a <= 0 || b <= 0) return null;
        return round1((Math.pow(b / a, 1 / years) - 1) * 100);
    }

    /** Annualised return over n years, only if the history really reaches back that far (3 weeks slack). */
    public static Double annOver(long[] t, double[] v, long now, double last, int years) {
        if (t.length == 0 || t[0] > now - years * YEAR_S + 21 * 86400) return null;
        return ann(valueAt(t, v, now - years * YEAR_S), last, years);
    }

    public record Drawdown(Double pct, String span) {}

    public static Drawdown drawdown(long[] t, double[] v) {
        double peak = Double.NaN, worst = 0;
        long peakT = 0, wPeak = -1, wLow = -1;
        for (int i = 0; i < t.length; i++) {
            if (Double.isNaN(peak) || v[i] > peak) {
                peak = v[i];
                peakT = t[i];
            }
            double dd = v[i] / peak - 1;
            if (dd < worst) {
                worst = dd;
                wPeak = peakT;
                wLow = t[i];
            }
        }
        if (wPeak < 0) return new Drawdown(null, null);
        return new Drawdown(round1(worst * 100), monYear(wPeak) + " to " + monYear(wLow));
    }

    public static List<Double> logReturns(double[] v) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < v.length; i++)
            if (v[i - 1] > 0 && v[i] > 0) out.add(Math.log(v[i] / v[i - 1]));
        return out;
    }

    public static double pstdev(List<Double> xs) {
        double m = xs.stream().mapToDouble(d -> d).average().orElse(0);
        return Math.sqrt(xs.stream().mapToDouble(d -> (d - m) * (d - m)).sum() / xs.size());
    }

    public static Double pearson(List<Double> x, List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 3) return null;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) {
            mx += x.get(i);
            my += y.get(i);
        }
        mx /= n;
        my /= n;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - mx, dy = y.get(i) - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        return sxx == 0 || syy == 0 ? null : sxy / Math.sqrt(sxx * syy);
    }

    public static String weekKey(long t) {
        ZonedDateTime d = Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC);
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-" + String.format("%02d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    public static String monYear(long t) {
        return Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC).format(MON_YEAR);
    }

    public static String yearMonth(long t) {
        return Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    public static String isoDate(long t) {
        return Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    public static Double round1(double x) {
        return Double.isFinite(x) ? Math.round(x * 10) / 10.0 : null;
    }

    public static Double round2(double x) {
        return Double.isFinite(x) ? Math.round(x * 100) / 100.0 : null;
    }

    public static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compare);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }
}
