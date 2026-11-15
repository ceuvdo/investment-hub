package hub.util;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small helpers around Jackson 3. */
public final class Json {
    public static final JsonMapper M = JsonMapper.builder().build();

    private Json() {}

    public static JsonNode parse(String s) {
        return M.readTree(s);
    }

    public static String write(Object o) {
        return M.writeValueAsString(o);
    }

    public static String str(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.isMissingNode() ? null : v.asString();
    }

    public static Double num(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        if (v.isNumber()) return v.asDouble();
        try {
            return Double.parseDouble(v.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
