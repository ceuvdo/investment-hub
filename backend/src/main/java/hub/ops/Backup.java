package hub.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Nightly pg_dump of the hub database (our own price history), keeping the last N dumps. */
@Component
public class Backup {
    private static final Logger log = LoggerFactory.getLogger(Backup.class);

    @Value("${hub.backup.dir:}") String dir;
    @Value("${hub.backup.pg-dump:}") String pgDump;
    @Value("${HUB_PG_PORT:5432}") String port;
    @Value("${hub.backup.keep:14}") int keep;
    @Value("${spring.datasource.password:}") String password;

    @Scheduled(cron = "${hub.backup.cron:0 30 3 * * *}")
    public void nightly() {
        Thread.ofVirtual().name("backup").start(this::run);
    }

    public String run() {
        if (dir == null || dir.isBlank() || pgDump == null || pgDump.isBlank() || !new File(pgDump).exists()) return "backup not configured";
        try {
            Files.createDirectories(Path.of(dir));
            Path out = Path.of(dir, "hub-" + LocalDate.now() + ".dump");
            ProcessBuilder pb = new ProcessBuilder(pgDump, "-h", "127.0.0.1", "-p", port, "-U", "hub", "-d", "hub", "-Fc", "-f", out.toString())
                    .redirectErrorStream(true).redirectOutput(Path.of(dir, "last-backup.log").toFile());
            pb.environment().put("PGPASSWORD", password);
            Process p = pb.start();
            if (!p.waitFor(30, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return "backup timed out";
            }
            if (p.exitValue() != 0) {
                log.error("backup failed, see {}/last-backup.log", dir);
                return "backup failed";
            }
            File[] dumps = new File(dir).listFiles((d, n) -> n.startsWith("hub-") && n.endsWith(".dump"));
            if (dumps != null && dumps.length > keep) {
                Arrays.sort(dumps, Comparator.comparing(File::getName));
                for (int i = 0; i < dumps.length - keep; i++) dumps[i].delete();
            }
            log.info("backup written: {} ({} MB)", out, Files.size(out) / 1_000_000);
            return "ok";
        } catch (Exception e) {
            log.error("backup failed: {}", e.getMessage());
            return "backup failed: " + e.getMessage();
        }
    }
}
