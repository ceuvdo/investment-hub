package hub.news;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import hub.HubProperties;
import hub.api.RelayPublisher;
import hub.util.Http;
import hub.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes our own short brief (2–3 sentences, in our words) for each new headline from the news feeds,
 * stores it, and pushes the recent briefs to the Cloudflare relay, which serves them with the headlines.
 * Runs only when ANTHROPIC_API_KEY is set.
 */
@Component
public class NewsWriter {
    private static final Logger log = LoggerFactory.getLogger(NewsWriter.class);
    static final String MODEL = "claude-opus-5-5";
    static final int PER_RUN = 15; // new headlines per 10-minute run: keeps cost bounded
    static final String SYSTEM = """
            You write short news briefs for Wyndton, a site that tracks investment returns, risk and liquidity.
            From a headline and the publisher's teaser, write an original brief of 2 to 3 sentences in plain English:
            what happened, then which markets, assets or kinds of investors it may matter to.
            Use only facts that are in the input. Do not add numbers, names, quotes, dates or predictions that are not there.
            Write in your own words; do not reuse phrases from the teaser.
            No headline, no preamble, no markdown, no advice to buy or sell.
            If the input is too thin to say more than the headline, write a single sentence.""";

    private final JdbcTemplate jdbc;
    private final HubProperties props;
    private AnthropicClient client;
    private boolean warned;

    public NewsWriter(JdbcTemplate jdbc, HubProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    private AnthropicClient client() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            if (!warned) log.info("news briefs off: ANTHROPIC_API_KEY is not set");
            warned = true;
            return null;
        }
        if (client == null) client = AnthropicOkHttpClient.fromEnv();
        return client;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void run() {
        AnthropicClient c = client();
        if (c == null) return;
        try {
            JsonNode items = Json.parse(Http.get(newsUrl())).get("items");
            int written = 0;
            if (items != null)
                for (JsonNode n : items) {
                    if (written >= PER_RUN) break;
                    String url = Json.str(n, "url"), title = Json.str(n, "t");
                    if (url == null || title == null || exists(url)) continue;
                    String brief = write(c, title, Json.str(n, "sum"), Json.str(n, "cat"));
                    if (brief == null) continue;
                    jdbc.update("INSERT INTO news_briefs(url, title, brief, model) VALUES (?,?,?,?) ON CONFLICT (url) DO NOTHING", url, title, brief, MODEL);
                    written++;
                }
            if (written > 0) log.info("news briefs: wrote {}", written);
            push();
        } catch (Exception e) {
            log.warn("news briefs run failed: {}", e.getMessage());
        }
    }

    String write(AnthropicClient c, String title, String teaser, String category) {
        String input = "Category: " + (category == null ? "markets" : category) + "\nHeadline: " + title
                + "\nTeaser: " + (teaser == null || teaser.isBlank() ? "(none)" : teaser);
        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(2000L)
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage(input)
                .build();
        try {
            Message m = c.messages().create(params);
            if (m.stopReason().map(r -> r.toString().equals("refusal")).orElse(false)) {
                log.info("news brief declined for: {}", title);
                return null;
            }
            String text = m.content().stream().flatMap(b -> b.text().stream()).map(TextBlock::text)
                    .collect(Collectors.joining(" ")).trim();
            if (text.isEmpty() || text.length() > 900) return null;
            return text;
        } catch (RateLimitException e) {
            log.warn("news briefs: rate limited, will retry next run");
            return null;
        } catch (AnthropicServiceException e) {
            log.warn("news brief failed ({}): {}", e.statusCode(), e.getMessage());
            return null;
        }
    }

    private boolean exists(String url) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM news_briefs WHERE url = ?", Integer.class, url);
        return n != null && n > 0;
    }

    /** Send the last 3 days of briefs to the relay (signed like price pushes). */
    void push() throws Exception {
        HubProperties.Relay r = props.relay();
        if (r == null || !r.configured()) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT url, brief FROM news_briefs WHERE created_at > now() - interval '3 days' ORDER BY created_at DESC LIMIT 400");
        Map<String, String> map = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) map.put((String) row.get("url"), (String) row.get("brief"));
        String body = Json.write(Map.of("t", System.currentTimeMillis(), "briefs", map));
        var res = Http.post(r.url().replaceAll("/push$", "/push-briefs"), body,
                Map.of("Content-Type", "application/json", "X-Signature", RelayPublisher.sign(r.secret(), body)), Duration.ofSeconds(15));
        if (res.statusCode() != 200) log.warn("news briefs push: HTTP {} {}", res.statusCode(), res.body());
    }

    private String newsUrl() {
        String u = props.relay() == null ? null : props.relay().url();
        return (u == null || u.isBlank() ? "https://investment-hub-live.ceuvdo.workers.dev/push" : u).replaceAll("/push$", "/news");
    }
}
