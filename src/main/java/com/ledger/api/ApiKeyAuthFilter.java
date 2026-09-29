package com.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the {@code X-API-Key} header, applies that key's rate limit, and gives the caller the scopes in
 * {@code ledger.auth.api-key-scopes}. It runs inside the Spring Security chain (see {@link SecurityConfig}) so
 * API keys and bearer tokens are authorised by the same scope rules. A request without the header simply passes
 * through: the bearer-token filter and the access rules decide what happens to it.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiKeyRepository keys;
    private final RateLimiter rateLimiter;
    private final ObjectMapper json;
    private final List<GrantedAuthority> authorities;

    public ApiKeyAuthFilter(ApiKeyRepository keys, RateLimiter rateLimiter, ObjectMapper json,
                            AuthProperties properties) {
        this.keys = keys;
        this.rateLimiter = rateLimiter;
        this.json = json;
        this.authorities = properties.apiKeyScopes().stream()
                .<GrantedAuthority>map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Public: health, API docs, and the bundled UI's static files (the UI itself sends the key on API calls).
        return path.equals("/health") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")
                || path.equals("/") || path.equals("/index.html") || path.startsWith("/assets/")
                || path.equals("/favicon.ico") || path.equals("/ui-config");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null || presented.isBlank()) {
            chain.doFilter(request, response); // no API key: a bearer token (or a 401) is next
            return;
        }
        Optional<ApiKey> key;
        try {
            key = keys.findActiveByHash(ApiKeyHasher.sha256Hex(presented));
        } catch (DataAccessException e) {
            // The key cannot be checked because the database is unreachable. Say so: letting this escape used to
            // surface as "401 Unauthorized", which sent users off to fix a perfectly good key.
            response.setHeader("Retry-After", "5");
            ProblemResponse.write(json, request, response, HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
                    "Service unavailable", "The ledger database is unreachable; try again shortly");
            return;
        }
        if (key.isEmpty()) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "invalid-api-key", "Invalid API key",
                    "The API key is not recognised or has been revoked");
            return;
        }

        TokenBucket.Decision decision = rateLimiter.check(key.get());
        response.setHeader("X-RateLimit-Limit", String.valueOf(key.get().rateLimitPerMinute()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            reject(request, response, HttpStatus.TOO_MANY_REQUESTS, "rate-limit-exceeded", "Rate limit exceeded",
                    "Limit is " + key.get().rateLimitPerMinute() + " requests per minute; retry in "
                            + decision.retryAfterSeconds() + "s");
            return;
        }

        // The principal name is the key id, which TransferController uses to scope idempotency keys per caller.
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(key.get().id().toString(), null, authorities));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String slug,
                        String title, String detail) throws IOException {
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "ApiKey");
        }
        ProblemResponse.write(json, request, response, status, slug, title, detail);
    }
}
