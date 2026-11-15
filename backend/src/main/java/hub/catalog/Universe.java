package hub.catalog;

import hub.util.Json;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the catalog tracks: groups, 64 ways, assets, house price series, DeFi pools, live symbols. Edit universe.json. */
@Component
public class Universe {

    public record Group(String slug, String name, String note, String img, String pos, String about) {}
    public record Way(String name, String group, int entry, int liq, String risk, String desc) {}
    public record Asset(String id, String name, String symbol, String source, String way, String sub, String min) {}
    public record House(String id, String country, String series) {}
    public record Defi(String way, String name, String project, String chain, String symbol) {}

    public final List<Group> groups = new ArrayList<>();
    public final List<Way> ways = new ArrayList<>();
    public final List<Asset> assets = new ArrayList<>();
    public final List<House> houses = new ArrayList<>();
    public final List<Defi> defi = new ArrayList<>();
    /** asset id -> Binance symbol (e.g. btc -> BTCUSDT) */
    public final Map<String, String> binance = new LinkedHashMap<>();
    /** asset id -> Kraken v2 symbol (e.g. eurusd -> EUR/USD) */
    public final Map<String, String> kraken = new LinkedHashMap<>();
    /** asset id -> chain name used for settlement timing */
    public final Map<String, String> chain = new LinkedHashMap<>();

    public Universe() throws IOException {
        String raw = new String(new ClassPathResource("universe.json").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode u = Json.parse(raw);
        for (JsonNode g : u.get("groups"))
            groups.add(new Group(Json.str(g, "slug"), Json.str(g, "name"), Json.str(g, "note"), Json.str(g, "img"), Json.str(g, "pos"), Json.str(g, "about")));
        for (JsonNode w : u.get("ways"))
            ways.add(new Way(Json.str(w, "name"), Json.str(w, "group"), w.get("entry").asInt(), w.get("liq").asInt(), Json.str(w, "risk"), Json.str(w, "desc")));
        for (JsonNode a : u.get("assets"))
            assets.add(new Asset(Json.str(a, "id"), Json.str(a, "name"), Json.str(a, "symbol"), Json.str(a, "source"), Json.str(a, "way"), Json.str(a, "sub"), Json.str(a, "min")));
        for (JsonNode h : u.get("houses"))
            houses.add(new House(Json.str(h, "id"), Json.str(h, "country"), Json.str(h, "series")));
        for (JsonNode d : u.get("defi"))
            defi.add(new Defi(Json.str(d, "way"), Json.str(d, "name"), Json.str(d, "project"), Json.str(d, "chain"), Json.str(d, "symbol")));
        u.get("live").get("binance").properties().forEach(e -> binance.put(e.getKey(), e.getValue().asString()));
        u.get("live").get("kraken").properties().forEach(e -> kraken.put(e.getKey(), e.getValue().asString()));
        u.get("chain").properties().forEach(e -> chain.put(e.getKey(), e.getValue().asString()));
    }

    public static String slug(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '-');
        String out = b.toString();
        while (out.contains("--")) out = out.replace("--", "-");
        return out.replaceAll("^-+|-+$", "");
    }
}
