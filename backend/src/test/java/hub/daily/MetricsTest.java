package hub.daily;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {
    private static final long DAY = 86400;

    private static long epoch(int y, int m, int d) {
        return LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    @Test
    void valueAtReturnsLastValueAtOrBeforeTarget() {
        long[] t = {100, 200, 300};
        double[] v = {1, 2, 3};
        assertNull(Metrics.valueAt(t, v, 99));
        assertEquals(1.0, Metrics.valueAt(t, v, 100));
        assertEquals(1.0, Metrics.valueAt(t, v, 199.9));
        assertEquals(2.0, Metrics.valueAt(t, v, 200));
        assertEquals(3.0, Metrics.valueAt(t, v, 1_000));
        assertNull(Metrics.valueAt(new long[0], new double[0], 1_000));
    }

    @Test
    void pctRoundsToOneDecimalAndRejectsNullOrZeroBase() {
        assertEquals(10.0, Metrics.pct(100.0, 110.0));
        assertEquals(-33.3, Metrics.pct(3.0, 2.0));
        assertNull(Metrics.pct(null, 1.0));
        assertNull(Metrics.pct(1.0, null));
        assertNull(Metrics.pct(0.0, 1.0));
    }

    @Test
    void annIsCompoundAnnualGrowth() {
        assertEquals(10.0, Metrics.ann(100.0, 121.0, 2));
        assertEquals(-50.0, Metrics.ann(100.0, 50.0, 1));
        assertNull(Metrics.ann(0.0, 1.0, 1));
        assertNull(Metrics.ann(1.0, -1.0, 1));
        assertNull(Metrics.ann(null, 1.0, 1));
    }

    @Test
    void annOverNeedsHistoryReachingBackNYears() {
        long now = epoch(2026, 1, 1);
        long target = (long) (now - 3 * Metrics.YEAR_S);
        double[] v = {100, 133.1};
        assertEquals(10.0, Metrics.annOver(new long[]{target - DAY, now}, v, now, 133.1, 3));
        assertEquals(10.0, Metrics.annOver(new long[]{target, now}, v, now, 133.1, 3));
        // starts 22 days after the target: beyond the 3-week slack
        assertNull(Metrics.annOver(new long[]{target + 22 * DAY, now}, v, now, 133.1, 3));
        assertNull(Metrics.annOver(new long[0], new double[0], now, 1, 3));
    }

    @Test
    void annOverSlackUsesFirstPointWhenHistoryStartsWithinThreeWeeks() {
        long now = epoch(2026, 1, 1);
        long target = (long) (now - 3 * Metrics.YEAR_S);
        double[] v = {100, 133.1};
        assertNotNull(Metrics.annOver(new long[]{target + 10 * DAY, now}, v, now, 133.1, 3));
        assertNotNull(Metrics.annOver(new long[]{target + 21 * DAY, now}, v, now, 133.1, 3));
    }

    @Test
    void drawdownFindsWorstPeakToTroughWithSpan() {
        long[] t = {epoch(2020, 1, 15), epoch(2020, 2, 15), epoch(2020, 3, 15), epoch(2021, 6, 1), epoch(2022, 10, 1), epoch(2023, 1, 1)};
        double[] v = {100, 120, 90, 150, 75, 160};
        Metrics.Drawdown dd = Metrics.drawdown(t, v);
        assertEquals(-50.0, dd.pct());
        assertEquals("Jun 2021 to Oct 2022", dd.span());
    }

    @Test
    void drawdownOfMonotonicSeriesIsNull() {
        Metrics.Drawdown dd = Metrics.drawdown(new long[]{1, 2, 3}, new double[]{1, 2, 3});
        assertNull(dd.pct());
        assertNull(dd.span());
        assertNull(Metrics.drawdown(new long[0], new double[0]).pct());
    }

    @Test
    void logReturnsSkipNonPositiveValues() {
        List<Double> r = Metrics.logReturns(new double[]{100, 110, 0, 121, 121});
        assertEquals(2, r.size());
        assertEquals(Math.log(1.1), r.get(0), 1e-12);
        assertEquals(0.0, r.get(1), 1e-12);
        assertTrue(Metrics.logReturns(new double[]{5}).isEmpty());
    }

    @Test
    void pstdevIsPopulationStdev() {
        assertEquals(2.0, Metrics.pstdev(List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)), 1e-12);
        assertEquals(0.0, Metrics.pstdev(List.of(3.0)), 1e-12);
    }

    @Test
    void pearsonCorrelation() {
        List<Double> x = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        assertEquals(1.0, Metrics.pearson(x, List.of(2.0, 4.0, 6.0, 8.0, 10.0)), 1e-12);
        assertEquals(-1.0, Metrics.pearson(x, List.of(5.0, 4.0, 3.0, 2.0, 1.0)), 1e-12);
        // uses the common prefix when lengths differ
        assertEquals(1.0, Metrics.pearson(x, List.of(1.0, 2.0, 3.0)), 1e-12);
        assertNull(Metrics.pearson(x, List.of(1.0, 2.0)), "fewer than 3 points");
        assertNull(Metrics.pearson(x, List.of(7.0, 7.0, 7.0, 7.0, 7.0)), "constant series");
        assertNull(Metrics.pearson(List.of(1.0, 1.0, 1.0), List.of(1.0, 2.0, 3.0)));
    }

    @Test
    void weekKeyUsesIsoWeekBasedYear() {
        assertEquals("2026-01", Metrics.weekKey(epoch(2025, 12, 29))); // Monday of ISO week 2026-W01
        assertEquals("2026-01", Metrics.weekKey(epoch(2026, 1, 1)));
        assertEquals("2020-53", Metrics.weekKey(epoch(2021, 1, 1))); // Friday, still in 2020-W53
        assertEquals("2021-01", Metrics.weekKey(epoch(2021, 1, 4)));
        assertEquals("2024-09", Metrics.weekKey(epoch(2024, 3, 1)));
    }

    @Test
    void dateFormats() {
        assertEquals("Mar 2024", Metrics.monYear(epoch(2024, 3, 31)));
        assertEquals("2024-03", Metrics.yearMonth(epoch(2024, 3, 31)));
        assertEquals("2024-03-31", Metrics.isoDate(epoch(2024, 3, 31) + 3600));
    }

    @Test
    void rounding() {
        assertEquals(1.3, Metrics.round1(1.25 + 1e-9));
        assertEquals(1.23, Metrics.round2(1.2345));
        assertNull(Metrics.round1(Double.NaN));
        assertNull(Metrics.round2(Double.POSITIVE_INFINITY));
    }

    @Test
    void median() {
        assertEquals(3.0, Metrics.median(List.of(5.0, 1.0, 3.0)));
        assertEquals(2.5, Metrics.median(List.of(4.0, 1.0, 3.0, 2.0)));
        assertEquals(7.0, Metrics.median(List.of(7.0)));
    }
}
