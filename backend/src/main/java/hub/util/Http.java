package hub.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Shared HTTP client with retries. One client = kept-alive connections, so timing samples are stable. */
public final class Http {
    public static final String BROWSER_UA = "Mozilla/5.0 (open investment catalog data builder)";
    public static final String CURL_UA = "curl/8.4.0"; // FRED only answers plain clients
    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {}

    public static String get(String url) throws IOException {
        return get(url, BROWSER_UA, Duration.ofSeconds(60));
    }

    public static String get(String url, String userAgent, Duration timeout) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", userAgent).timeout(timeout).GET().build();
                HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return r.body();
                last = new IOException("HTTP " + r.statusCode() + " for " + url);
                if (r.statusCode() == 404) break;
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            sleep(2000L + attempt * 3000L);
        }
        throw last;
    }

    /** GET for APIs that insist on Brotli (Skinport): asks for br and decodes it; plain bodies pass through. */
    public static String getBrotli(String url, Duration timeout) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", BROWSER_UA).header("Accept-Encoding", "br").timeout(timeout).GET().build();
                HttpResponse<byte[]> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 200) {
                    boolean br = r.headers().firstValue("Content-Encoding").map(e -> e.equalsIgnoreCase("br")).orElse(false);
                    byte[] body = r.body();
                    if (br) try (var in = new org.brotli.dec.BrotliInputStream(new java.io.ByteArrayInputStream(body))) {
                        body = in.readAllBytes();
                    }
                    return new String(body, java.nio.charset.StandardCharsets.UTF_8);
                }
                last = new IOException("HTTP " + r.statusCode() + " for " + url);
                if (r.statusCode() == 404 || r.statusCode() == 429) break; // 429: Skinport allows 8 calls per 5 min; retrying makes it worse
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            sleep(5000L + attempt * 10000L);
        }
        throw last;
    }

    public static HttpResponse<String> post(String url, String body, Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Round-trip time of a tiny GET in milliseconds (connection reused), or -1 on failure. */
    public static double rttMs(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            long t0 = System.nanoTime();
            HttpResponse<Void> r = CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            double ms = (System.nanoTime() - t0) / 1e6;
            return r.statusCode() == 200 ? ms : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
