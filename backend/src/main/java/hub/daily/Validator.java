package hub.daily;

import hub.util.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks a freshly built catalog before anything is written or published.
 * A failed check keeps the previous files and site online.
 */
@Component
public class Validator {
    private final JdbcTemplate jdbc;

    public Validator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Result(boolean ok, List<String> problems, List<String> warnings) {}

    @SuppressWarnings("unchecked")
    public Result check(Map<String, Object> catalog) {
        List<String> problems = new ArrayList<>(), warnings = new ArrayList<>();
        List<Map<String, Object>> assets = (List<Map<String, Object>>) catalog.get("assets");
        List<Map<String, Object>> ways = (List<Map<String, Object>>) catalog.get("ways");
        if (ways == null || ways.size() < 64) problems.add("ways: " + (ways == null ? 0 : ways.size()) + " (expected 64)");
        if (assets == null || assets.isEmpty()) {
            problems.add("no assets");
            return new Result(false, problems, warnings);
        }

        // not much smaller than the last good build
        List<Integer> prev = jdbc.queryForList("SELECT n_assets FROM catalog_builds ORDER BY id DESC LIMIT 1", Integer.class);
        if (!prev.isEmpty() && assets.size() < prev.get(0) * 0.9)
            problems.add("asset count dropped from " + prev.get(0) + " to " + assets.size());

        int stale = 0, bad = 0;
        String weekAgo = LocalDate.now(ZoneOffset.UTC).minusDays(7).toString();
        for (Map<String, Object> a : assets) {
            Object price = a.get("price");
            if (!(price instanceof Number n) || !(n.doubleValue() > 0) || !Double.isFinite(n.doubleValue())) {
                bad++;
                if (bad <= 5) problems.add(a.get("id") + ": impossible price " + price);
            }
            for (String k : List.of("ret_1y", "ret_5y", "ret_10y", "swing"))
                if (a.get(k) instanceof Number r && (Math.abs(r.doubleValue()) > 1000 || !Double.isFinite(r.doubleValue())))
                    problems.add(a.get("id") + ": " + k + " = " + r);
            if (a.get("asof") instanceof String asof && asof.matches("\\d{4}-\\d{2}-\\d{2}") && asof.compareTo(weekAgo) < 0) stale++;
        }
        int market = (int) assets.stream().filter(a -> "market".equals(a.get("trust"))).count();
        if (market > 0 && stale > market * 0.2) problems.add(stale + " of " + market + " market assets are older than a week (source blocked?)");
        else if (stale > 0) warnings.add(stale + " market assets older than a week");

        // links from research must be web links (no javascript: etc.)
        for (Map<String, Object> w : ways) {
            JsonNode node = Json.M.valueToTree(w);
            for (JsonNode url : node.findValues("url"))
                if (!url.isNull() && !url.asString().matches("^https?://.+")) problems.add(w.get("slug") + ": bad url " + url.asString());
        }
        return new Result(problems.isEmpty(), problems, warnings);
    }
}
