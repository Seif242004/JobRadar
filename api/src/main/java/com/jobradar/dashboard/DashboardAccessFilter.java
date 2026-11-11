package com.jobradar.dashboard;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the dashboard private once it is reachable from the internet.
 *
 * <p>When {@code jobradar.dashboard.access-token} is set, every {@code /api/dashboard/**} request
 * needs it as a bearer token, as the {@code jr_token} cookie, or once as {@code ?token=} (which
 * sets the cookie, so a bookmarked link works on a phone). The signed CV links of notifications are
 * the one exception: they carry their own signature and open a single stored CV. Starting with a
 * public URL and no token is refused, so the dashboard can never be exposed open by mistake.
 */
@Component
@Profile({"local-dashboard", "dashboard"})
class DashboardAccessFilter extends OncePerRequestFilter {

    private static final String COOKIE = "jr_token";
    private static final String SIGNED_CV_PATH = "/api/dashboard/cv";

    private final byte[] token;

    DashboardAccessFilter(
            @Value("${jobradar.dashboard.access-token:}") String token, @Value("${jobradar.public-url:}") String publicUrl) {
        if (token.isBlank() && !publicUrl.isBlank()) {
            throw new IllegalStateException(
                    "jobradar.public-url is set but jobradar.dashboard.access-token is empty: refusing to expose the dashboard without a token");
        }
        if (!token.isBlank() && token.length() < 16) {
            throw new IllegalStateException("jobradar.dashboard.access-token must be at least 16 characters");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // No token configured (local use on 127.0.0.1), or not a dashboard API call (static files, health).
        return token.length == 0 || !request.getRequestURI().startsWith("/api/dashboard/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().equals(SIGNED_CV_PATH)) {
            chain.doFilter(request, response); // the controller verifies the link signature
            return;
        }
        String presented = presented(request);
        if (presented != null && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            if (request.getParameter("token") != null) {
                Cookie cookie = new Cookie(COOKIE, presented);
                cookie.setHttpOnly(true);
                cookie.setSecure(request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")));
                cookie.setPath("/");
                cookie.setMaxAge(60 * 60 * 24 * 90);
                response.addCookie(cookie);
            }
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }

    private static String presented(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).strip();
        }
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (COOKIE.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return request.getParameter("token");
    }
}
