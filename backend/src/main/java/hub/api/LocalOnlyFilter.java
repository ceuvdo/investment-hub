package hub.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * The backend is for this PC only.
 * - Host must be localhost/127.0.0.1 (blocks DNS-rebinding pages from reaching the API).
 * - Only the public site and the API are served; code, scripts and docs in the project folder are not.
 * - Admin calls need the custom header X-Hub-Admin: a web page cannot send it cross-origin without a CORS
 *   preflight, which this app never allows, so another website open in a browser cannot trigger a build or deploy.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalOnlyFilter extends OncePerRequestFilter {
    private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String host = req.getServerName();
        if (host == null || !HOSTS.contains(host.toLowerCase())) {
            res.sendError(403, "local access only");
            return;
        }
        String path = req.getRequestURI();
        if (!allowedPath(path)) {
            res.sendError(404);
            return;
        }
        if (path.startsWith("/api/admin/") && !"1".equals(req.getHeader("X-Hub-Admin"))) {
            res.sendError(403, "missing X-Hub-Admin header");
            return;
        }
        chain.doFilter(req, res);
    }

    static boolean allowedPath(String p) {
        if (p.equals("/") || p.equals("/live") || p.startsWith("/api/") || p.startsWith("/actuator/")) return true;
        if (p.contains("..")) return false;
        if (p.startsWith("/assets/") || p.startsWith("/data/")) return !p.endsWith(".md");
        return p.matches("^/[a-z0-9-]+\\.html$") || p.equals("/favicon.ico");
    }
}
