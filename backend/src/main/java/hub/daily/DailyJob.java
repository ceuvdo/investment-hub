package hub.daily;

import hub.HubProperties;
import hub.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Daily build: sources → metrics → catalog in the DB and as files for the static site → deploy to Cloudflare. */
@Component
public class DailyJob {
    private static final Logger log = LoggerFactory.getLogger(DailyJob.class);
    private final CatalogBuilder builder;
    private final JdbcTemplate jdbc;
    private final HubProperties props;
    @org.springframework.beans.factory.annotation.Value("${hub.log-dir:E:/tools/logs}")
    String logDir = "E:/tools/logs";
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String lastResult = "not run yet";

    private final Validator validator;

    public DailyJob(CatalogBuilder builder, Validator validator, JdbcTemplate jdbc, HubProperties props) {
        this.builder = builder;
        this.validator = validator;
        this.jdbc = jdbc;
        this.props = props;
    }

    public String lastResult() {
        return lastResult;
    }

    public boolean isRunning() {
        return running.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        // also catch up when the PC was off at 09:00: no successful build in the last 26 hours
        boolean missed = false;
        try {
            missed = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT coalesce(max(built_at), 'epoch') < now() - interval '26 hours' FROM catalog_builds", Boolean.class));
        } catch (Exception e) {
            log.warn("could not check last build: {}", e.getMessage());
        }
        if (props.dailyOnStart() || missed) {
            log.info("daily: starting build on startup (missed={})", missed);
            Thread.ofVirtual().start(() -> run(props.deploy().enabled()));
        }
    }

    /** Runs on its own thread: the scheduler thread must stay free for live pushes and candle flushes. */
    @Scheduled(cron = "${hub.daily-cron}")
    public void scheduled() {
        Thread.ofVirtual().name("daily-build").start(() -> run(props.deploy().enabled()));
    }

    /** Returns false if a build is already running. */
    public boolean run(boolean deploy) {
        if (!running.compareAndSet(false, true)) return false;
        long t0 = System.currentTimeMillis();
        try {
            CatalogBuilder.Result r = builder.build();
            Validator.Result v = validator.check(r.catalog());
            if (!v.ok()) {
                lastResult = "rejected, site unchanged: " + String.join("; ", v.problems());
                log.error("daily: {}", lastResult);
                return true;
            }
            if (!v.warnings().isEmpty()) log.warn("daily warnings: {}", v.warnings());
            String catalog = Json.write(r.catalog());
            String series = Json.write(r.series());
            jdbc.update("INSERT INTO catalog_builds(n_assets, n_ways, json) VALUES (?, ?, ?::jsonb)",
                    ((List<?>) r.catalog().get("assets")).size(), ((List<?>) r.catalog().get("ways")).size(), catalog);
            Path data = Path.of(props.siteDir(), "data");
            write(data.resolve("catalog.json"), catalog);
            write(data.resolve("series.json"), series);
            write(data.resolve("catalog.js"), "window.CATALOG=" + catalog + ";");
            write(data.resolve("series.js"), "window.SERIES=" + series + ";");
            String msg = "built " + ((Map<?, ?>) r.catalog().get("meta")).get("n_assets") + " assets in " + (System.currentTimeMillis() - t0) / 1000 + " s";
            if (deploy) msg += "; " + deploy();
            lastResult = msg;
            log.info("daily: {}", msg);
            return true;
        } catch (Exception e) {
            lastResult = "failed: " + e;
            log.error("daily build failed", e);
            return true;
        } finally {
            running.set(false);
        }
    }

    /** Write via a temp file so the site never reads half a file. */
    private static void write(Path p, String content) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private String deploy() {
        try {
            Process p = new ProcessBuilder("pwsh", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", props.deploy().script())
                    .redirectErrorStream(true)
                    .redirectOutput(Path.of(logDir, "deploy-last.log").toFile())
                    .start();
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return "deploy timed out";
            }
            return p.exitValue() == 0 ? "deployed" : "deploy failed (exit " + p.exitValue() + ", see " + logDir + "/deploy-last.log)";
        } catch (Exception e) {
            return "deploy failed: " + e.getMessage();
        }
    }
}
