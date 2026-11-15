package hub.daily;

import hub.catalog.Universe;
import hub.live.Timing;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How long a sale takes, in two measured parts:
 *  exec   = time to get the order filled (network round trip to the exchange, or the exchange's trading hours),
 *  settle = time until the money (or the coin on-chain) is really yours (settlement cycle, block confirmation).
 * Market rules are cited; network times are measured live by the backend.
 */
@Component
public class SellProfiles {
    private static final double DAY = 86400;

    /** Exchange settlement cycles and trading hours by Yahoo symbol suffix. */
    private record Venue(String exchange, String hours, int tPlus, String rule, String url) {}

    private static final String SEC = "https://www.sec.gov/newsroom/press-releases/2023-29";
    private static final String CSDR = "https://www.esma.europa.eu/esmas-activities/post-trading/settlement";
    private static final Map<String, Venue> VENUES = new LinkedHashMap<>();

    static {
        VENUES.put("", new Venue("NYSE / Nasdaq", "Mon–Fri 9:30–16:00 New York", 1, "T+1 since 28 May 2024 (SEC Rule 15c6-1)", SEC));
        VENUES.put(".L", new Venue("London Stock Exchange", "Mon–Fri 8:00–16:30 London", 2, "T+2; UK moves to T+1 on 11 Oct 2027", "https://www.gov.uk/government/publications/accelerated-settlement-taskforce-report"));
        VENUES.put(".MI", new Venue("Borsa Italiana (Euronext Milan)", "Mon–Fri 9:00–17:30 CET", 2, "T+2 (EU CSDR); EU moves to T+1 on 11 Oct 2027", CSDR));
        VENUES.put(".DE", new Venue("Xetra Frankfurt", "Mon–Fri 9:00–17:30 CET", 2, "T+2 (EU CSDR); EU moves to T+1 on 11 Oct 2027", CSDR));
        VENUES.put(".AS", new Venue("Euronext Amsterdam", "Mon–Fri 9:00–17:30 CET", 2, "T+2 (EU CSDR); EU moves to T+1 on 11 Oct 2027", CSDR));
        VENUES.put(".PA", new Venue("Euronext Paris", "Mon–Fri 9:00–17:30 CET", 2, "T+2 (EU CSDR); EU moves to T+1 on 11 Oct 2027", CSDR));
        VENUES.put(".LS", new Venue("Euronext Lisbon", "Mon–Fri 8:00–16:30 Lisbon", 2, "T+2 (EU CSDR); EU moves to T+1 on 11 Oct 2027", CSDR));
        VENUES.put(".SW", new Venue("SIX Swiss Exchange", "Mon–Fri 9:00–17:30 CET", 2, "T+2", "https://www.six-group.com"));
        VENUES.put(".HK", new Venue("Hong Kong Exchange", "Mon–Fri 9:30–16:00 Hong Kong", 2, "T+2", "https://www.hkex.com.hk"));
        VENUES.put(".KS", new Venue("Korea Exchange", "Mon–Fri 9:00–15:30 Seoul", 2, "T+2", "https://global.krx.co.kr"));
        VENUES.put(".NS", new Venue("National Stock Exchange of India", "Mon–Fri 9:15–15:30 Mumbai", 1, "T+1 since Jan 2023 (SEBI)", "https://www.sebi.gov.in"));
        VENUES.put(".SR", new Venue("Saudi Exchange (Tadawul)", "Sun–Thu 10:00–15:00 Riyadh", 2, "T+2", "https://www.saudiexchange.sa"));
        VENUES.put(".IS", new Venue("Borsa Istanbul", "Mon–Fri 10:00–18:00 Istanbul", 2, "T+2", "https://www.borsaistanbul.com"));
    }

    /**
     * Seconds for one confirmation (block) where we do not measure live: protocol targets from each network's docs.
     * ethereum, bitcoin and solana are measured live (Timing) and fall back to these values.
     */
    private record Chain(String label, double blockS, double finalS, String what) {}

    private static final Map<String, Chain> CHAINS = Map.ofEntries(
            Map.entry("bitcoin", new Chain("Bitcoin", 600, 600, "1 block")),
            Map.entry("bitcoin-cash", new Chain("Bitcoin Cash", 600, 600, "1 block")),
            Map.entry("litecoin", new Chain("Litecoin", 150, 150, "1 block")),
            Map.entry("dogecoin", new Chain("Dogecoin", 60, 60, "1 block")),
            Map.entry("ethereum", new Chain("Ethereum", 12, 768, "finality (2 epochs = 64 slots)")),
            Map.entry("ethereum-classic", new Chain("Ethereum Classic", 13, 13, "1 block")),
            Map.entry("solana", new Chain("Solana", 0.4, 12.8, "finality (32 slots)")),
            Map.entry("xrp", new Chain("XRP Ledger", 4, 4, "1 ledger close")),
            Map.entry("bnb", new Chain("BNB Smart Chain", 0.75, 0.75, "1 block")),
            Map.entry("cardano", new Chain("Cardano", 20, 20, "1 block")),
            Map.entry("tron", new Chain("TRON", 3, 3, "1 block")),
            Map.entry("avalanche", new Chain("Avalanche C-Chain", 2, 2, "finality")),
            Map.entry("polkadot", new Chain("Polkadot", 6, 6, "1 block")),
            Map.entry("stellar", new Chain("Stellar", 5, 5, "1 ledger close")),
            Map.entry("cosmos", new Chain("Cosmos Hub", 6, 6, "1 block (instant finality)")),
            Map.entry("near", new Chain("NEAR", 1.2, 2.4, "finality")),
            Map.entry("hedera", new Chain("Hedera", 4, 4, "consensus finality")),
            Map.entry("filecoin", new Chain("Filecoin", 30, 30, "1 epoch")));

    private final Timing timing;
    private final Universe universe;

    public SellProfiles(Timing timing, Universe universe) {
        this.timing = timing;
        this.universe = universe;
    }

    /** Adds sell, sell_s and sell_detail to an asset map. */
    public void apply(Map<String, Object> a, Double usDaysOnMarket, String usDomAsof) {
        String id = (String) a.get("id"), sym = (String) a.get("symbol");
        Map<String, Object> d = new LinkedHashMap<>();
        if ("INDEX".equals(a.get("kind")) && id.startsWith("hp_")) {
            if ("hp_us".equals(id) && usDaysOnMarket != null) {
                put(d, "Listing to accepted offer", null, null,
                        String.format(Locale.ROOT, "%.0f days on market (median, %s)", usDaysOnMarket, usDomAsof), usDaysOnMarket * DAY,
                        "Realtor.com via FRED (MEDDAYONMARUS)", "https://fred.stlouisfed.org/series/MEDDAYONMARUS", true);
                d.put("note", "Closing and registration come on top, typically 30–45 days.");
                a.put("sell", String.format(Locale.ROOT, "%.0f days on market", usDaysOnMarket));
            } else {
                put(d, null, null, null, "no published average for this country", null, null, null, false);
                a.put("sell", "no data");
            }
        } else if (sym.startsWith("^") || sym.startsWith("DX-Y")) {
            put(d, "Not tradable directly", null, null, "trade it through ETFs or futures", null, null, null, false);
            a.put("sell", "index, not tradable");
        } else if (sym.endsWith("=X")) {
            Double rtt = timing.median("kraken.rtt_ms");
            int t = sym.equals("CAD=X") ? 1 : 2;
            put(d, rtt != null ? String.format(Locale.ROOT, "%.2f s network round trip (Kraken, measured)", rtt / 1000) : "< 1 s", rtt != null ? rtt / 1000 : 1.0,
                    "24/5, Sun 22:00 – Fri 22:00 UTC", "T+" + t + " spot settlement", t * DAY,
                    "FX market convention", "https://www.bis.org/cpmi/publ/d81.pdf", rtt != null);
            a.put("sell", fill(rtt) + " · T+" + t);
        } else if (sym.endsWith("-USD")) {
            Double rtt = timing.median("binance.rtt_ms");
            String chainKey = universe.chain.getOrDefault(id, "ethereum"); // tokens without their own chain are ERC-20
            Chain c = CHAINS.get(chainKey);
            double settle = c.finalS();
            boolean live = false;
            String settleText;
            if ("bitcoin".equals(chainKey) && timing.median("bitcoin.block_s") != null) {
                settle = timing.median("bitcoin.block_s");
                live = true;
                settleText = String.format(Locale.ROOT, "1 block ≈ %s (average of the last 10 blocks, live)", dur(settle));
            } else if ("ethereum".equals(chainKey) && timing.median("ethereum.block_s") != null) {
                // slots are fixed 12 s time windows; missed slots do not delay finality (2 epochs = 64 slots)
                settle = 768;
                live = true;
                settleText = String.format(Locale.ROOT, "finality %s (64 slots × 12 s); blocks arriving every %.1f s (measured)", dur(settle), timing.median("ethereum.block_s"));
            } else if ("solana".equals(chainKey) && timing.median("solana.slot_s") != null) {
                settle = timing.median("solana.slot_s") * 32;
                live = true;
                settleText = String.format(Locale.ROOT, "finality ≈ %s (32 slots × %.2f s measured)", dur(settle), timing.median("solana.slot_s"));
            } else {
                settleText = c.what() + " ≈ " + dur(settle) + " (protocol target)";
            }
            put(d, rtt != null ? String.format(Locale.ROOT, "%.2f s network round trip to Binance (measured)", rtt / 1000) : "fills instantly on an exchange", rtt != null ? rtt / 1000 : null,
                    "24/7", c.label() + ": " + settleText, settle, live ? "measured by our backend" : "network documentation", null, rtt != null || live);
            a.put("sell", fill(rtt) + " · " + dur(settle) + " on-chain");
        } else {
            Venue v = venue(sym);
            put(d, "< 1 s in trading hours (" + v.exchange() + ")", 1.0, v.hours(), v.rule(), v.tPlus() * DAY, v.exchange(), v.url(), false);
            a.put("sell", "< 1 s fill · T+" + v.tPlus());
        }
        a.put("sell_detail", d);
        Double e = (Double) d.get("exec_s"), s = (Double) d.get("settle_s");
        a.put("sell_s", e == null && s == null ? null : Math.round(((e == null ? 0 : e) + (s == null ? 0 : s)) * 1000) / 1000.0);
    }

    private static Venue venue(String sym) {
        int dot = sym.lastIndexOf('.');
        String suffix = dot > 0 && !sym.startsWith("BRK") ? sym.substring(dot) : "";
        return VENUES.getOrDefault(suffix, VENUES.get(""));
    }

    private static void put(Map<String, Object> d, String exec, Double execS, String hours, String settle, Double settleS,
                            String source, String url, boolean measured) {
        d.put("exec", exec);
        d.put("exec_s", execS);
        d.put("hours", hours);
        d.put("settle", settle);
        d.put("settle_s", settleS);
        d.put("source", source);
        d.put("url", url);
        d.put("measured", measured);
    }

    private static String fill(Double rttMs) {
        return rttMs == null ? "instant fill" : String.format(Locale.ROOT, "%.2f s fill", rttMs / 1000);
    }

    /** 0.4 s, 12.8 s, 9.6 min, 2.0 h, 3 days */
    public static String dur(double s) {
        if (s < 60) return (s < 10 ? String.format(Locale.ROOT, "%.1f", s) : String.format(Locale.ROOT, "%.0f", s)) + " s";
        if (s < 3600) return String.format(Locale.ROOT, "%.1f min", s / 60);
        if (s < DAY) return String.format(Locale.ROOT, "%.1f h", s / 3600);
        return String.format(Locale.ROOT, "%.0f days", s / DAY);
    }
}
