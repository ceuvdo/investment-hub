package hub;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hub")
public record HubProperties(
        String siteDir,
        String researchDir,
        String dailyCron,
        boolean dailyOnStart,
        Deploy deploy,
        Relay relay,
        Live live) {

    public record Deploy(boolean enabled, String script) {}

    public record Relay(String url, String secret, long intervalMs) {
        public boolean configured() {
            return url != null && !url.isBlank() && secret != null && !secret.isBlank();
        }
    }

    public record Live(long flushMs) {}
}
