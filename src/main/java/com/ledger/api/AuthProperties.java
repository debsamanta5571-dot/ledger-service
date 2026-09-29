package com.ledger.api;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ledger.auth.bootstrap-api-key}: if set, a service key with this value is registered on startup, so a fresh
 * deployment is usable. People create their own personal keys through {@code POST /api-keys}.
 *
 * <p>{@code ledger.auth.api-key-scopes}: the scopes of service keys such as the bootstrap key. The default is
 * read-only, so writing needs either a personal key or a scoped bearer token. Personal keys carry their own scopes.
 */
@ConfigurationProperties(prefix = "ledger.auth")
public record AuthProperties(
        String bootstrapApiKey,
        @DefaultValue("60") int bootstrapRateLimitPerMinute,
        @DefaultValue({"accounts:read", "transfers:read"}) List<String> apiKeyScopes) {
}
