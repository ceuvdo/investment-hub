package hub.daily;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Price points in TimescaleDB: 'w' weekly adjusted closes, 'd' daily closes, 'q' quarterly index levels. */
@Component
public class Series {

    /** Parallel arrays sorted by time; t = unix seconds; vol may hold NaN. */
    public record Points(long[] t, double[] v, double[] vol) {
        public int size() { return t.length; }
        public boolean isEmpty() { return t.length == 0; }
    }

    private final JdbcTemplate jdbc;

    public Series(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String assetId, char kind, Points p) {
        List<Object[]> rows = new ArrayList<>(p.size());
        for (int i = 0; i < p.size(); i++) {
            Double vol = p.vol() == null || Double.isNaN(p.vol()[i]) ? null : p.vol()[i];
            rows.add(new Object[]{assetId, String.valueOf(kind), Timestamp.from(Instant.ofEpochSecond(p.t()[i])), p.v()[i], vol});
        }
        jdbc.batchUpdate("""
                INSERT INTO price_points(asset_id, kind, ts, value, volume) VALUES (?,?,?,?,?)
                ON CONFLICT (asset_id, kind, ts) DO UPDATE SET value = EXCLUDED.value, volume = EXCLUDED.volume
                """, rows);
    }

    public Points load(String assetId, char kind) {
        List<Long> ts = new ArrayList<>();
        List<Double> vs = new ArrayList<>(), vols = new ArrayList<>();
        jdbc.query("SELECT ts, value, volume FROM price_points WHERE asset_id = ? AND kind = ? ORDER BY ts",
                (java.sql.ResultSet rs) -> {
                    ts.add(rs.getTimestamp(1).toInstant().getEpochSecond());
                    vs.add(rs.getDouble(2));
                    double vol = rs.getDouble(3);
                    vols.add(rs.wasNull() ? Double.NaN : vol);
                }, assetId, String.valueOf(kind));
        long[] t = new long[ts.size()];
        double[] v = new double[ts.size()], vol = new double[ts.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = ts.get(i);
            v[i] = vs.get(i);
            vol[i] = vols.get(i);
        }
        return new Points(t, v, vol);
    }
}
