package hub.live;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TimingTest {
    private Timing timing;

    @BeforeEach
    void setUp() {
        timing = new Timing(mock(JdbcTemplate.class));
    }

    @Test
    void emptyKeyHasNoStats() {
        assertNull(timing.median("x"));
        assertNull(timing.p95("x"));
        assertEquals(0, timing.count("x"));
    }

    @Test
    void medianOddAndEven() {
        timing.add("k", 3);
        timing.add("k", 1);
        timing.add("k", 2);
        assertEquals(2.0, timing.median("k"));
        timing.add("k", 10);
        assertEquals(2.5, timing.median("k"));
    }

    @Test
    void p95IsNearestRank() {
        timing.add("one", 7);
        assertEquals(7.0, timing.p95("one"));
        for (int i = 1; i <= 20; i++) timing.add("k", i);
        assertEquals(19.0, timing.p95("k"));
        for (int i = 1; i <= 40; i++) timing.add("k40", i);
        assertEquals(38.0, timing.p95("k40"));
    }

    @Test
    void windowKeepsLast50Samples() {
        for (int i = 1; i <= 60; i++) timing.add("k", i);
        assertEquals(50, timing.count("k"));
        assertEquals(35.5, timing.median("k")); // samples 11..60
        assertEquals(58.0, timing.p95("k"));
    }

    @Test
    void setReplacesTheWindow() {
        for (int i = 1; i <= 10; i++) timing.add("k", i);
        timing.set("k", 600);
        assertEquals(1, timing.count("k"));
        assertEquals(600.0, timing.median("k"));
        timing.set("fresh", 12);
        assertEquals(12.0, timing.median("fresh"));
    }

    @Test
    void addIgnoresNonPositiveAndNonFiniteValues() {
        timing.add("k", 0);
        timing.add("k", -1); // Http.rttMs returns -1 on failure
        timing.add("k", Double.NaN);
        timing.add("k", Double.POSITIVE_INFINITY);
        assertEquals(0, timing.count("k"));
        timing.add("k", 5);
        assertEquals(1, timing.count("k"));
    }

    @Test
    void summaryHasRoundedStats() {
        timing.add("b", 1.23456);
        timing.add("a", 2);
        Map<String, Map<String, Object>> s = timing.summary();
        assertEquals(List.of("a", "b"), List.copyOf(s.keySet()));
        assertEquals(1.235, s.get("b").get("median"));
        assertEquals(1, s.get("b").get("n"));
        assertEquals(0L, s.get("b").get("age_s"));
    }

    @Test
    void setIgnoresNonPositiveValues() {
        timing.set("k", 600);
        timing.set("k", -5);
        timing.set("k", Double.NaN);
        assertEquals(600.0, timing.median("k"));
    }
}
