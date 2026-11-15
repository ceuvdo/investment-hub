package hub.catalog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class UniverseTest {
    private static Universe u;

    @BeforeAll
    static void load() throws Exception {
        u = new Universe();
    }

    private static Set<String> wayNames() {
        return u.ways.stream().map(Universe.Way::name).collect(Collectors.toSet());
    }

    private static Set<String> assetIds() {
        return u.assets.stream().map(Universe.Asset::id).collect(Collectors.toSet());
    }

    @Test
    void loadsEverySection() {
        assertFalse(u.groups.isEmpty());
        assertFalse(u.assets.isEmpty());
        assertFalse(u.houses.isEmpty());
        assertFalse(u.defi.isEmpty());
        assertFalse(u.binance.isEmpty());
        assertFalse(u.kraken.isEmpty());
        assertFalse(u.chain.isEmpty());
        assertEquals("BTCUSDT", u.binance.get("btc"));
        assertEquals("EUR/USD", u.kraken.get("eurusd"));
        assertEquals("bitcoin", u.chain.get("btc"));
    }

    @Test
    void has64UniqueWaysInKnownGroups() {
        assertEquals(77, u.ways.size());
        assertEquals(77, wayNames().size(), "way names unique");
        assertEquals(77, u.ways.stream().map(w -> Universe.slug(w.name())).distinct().count(), "way slugs unique");
        Set<String> groups = u.groups.stream().map(Universe.Group::slug).collect(Collectors.toSet());
        assertEquals(u.groups.size(), groups.size(), "group slugs unique");
        for (Universe.Way w : u.ways) {
            assertTrue(groups.contains(w.group()), w.name() + " -> unknown group " + w.group());
            assertTrue(w.liq() >= 0 && w.entry() >= 0, w.name());
        }
    }

    @Test
    void assetIdsUniqueAndWaysExist() {
        Set<String> seen = new HashSet<>();
        for (Universe.Asset a : u.assets) {
            assertTrue(seen.add(a.id()), "duplicate asset id " + a.id());
            assertTrue(wayNames().contains(a.way()), a.id() + " -> unknown way " + a.way());
            assertNotNull(a.symbol(), a.id());
        }
        Set<String> houseIds = new HashSet<>();
        for (Universe.House h : u.houses) {
            assertTrue(h.id().startsWith("hp_"), h.id());
            assertTrue(houseIds.add(h.id()), "duplicate house id " + h.id());
            assertFalse(seen.contains(h.id()), "house id clashes with an asset id " + h.id());
        }
        // CatalogBuilder hard-codes these way names
        assertTrue(wayNames().contains("Apartments and houses"));
        assertTrue(wayNames().contains("Savings and deposits"));
        for (Universe.Defi d : u.defi) assertTrue(wayNames().contains(d.way()), d.name() + " -> unknown way " + d.way());
    }

    @Test
    void liveAndChainKeysAreAssetIds() {
        Set<String> ids = assetIds();
        for (String id : u.binance.keySet()) assertTrue(ids.contains(id), "live.binance key " + id);
        for (String id : u.kraken.keySet()) assertTrue(ids.contains(id), "live.kraken key " + id);
        for (String id : u.chain.keySet()) assertTrue(ids.contains(id), "chain key " + id);
    }

    @Test
    void cryptoAssetsUseUsdPairs() {
        for (String id : u.chain.keySet()) {
            String sym = u.assets.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow().symbol();
            assertTrue(sym.endsWith("-USD"), id + " " + sym);
        }
    }

    @Test
    void slug() {
        assertEquals("etfs-and-index-funds", Universe.slug("ETFs and index funds"));
        assertEquals("p2p-lending-crowdfunding", Universe.slug("  P2P lending / crowdfunding! "));
        assertEquals("art-collectibles", Universe.slug("Art & collectibles"));
        assertEquals("a-b", Universe.slug("a---b"));
        assertEquals("", Universe.slug("---"));
        assertEquals(List.of("stocks"), List.of(Universe.slug("Stocks")));
    }
}
