package com.ledger.api;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ledger.auth.bootstrap-api-key}: if set, a key with this value is registered on startup so a fresh
 * deployment is usable. Further keys are managed directly in the {@code api_keys} table.
 *
 * <p>{@code ledger.auth.api-key-scopes}: the scopes every API key carries. Keys are a legacy machine
 * credential with no per-key scopes, so the default is read-only; writes need a scoped bearer token.
 */
@ConfigurationProperties(prefix = "ledger.auth")
public record AuthProperties(
        String bootstrapApiKey,
        @DefaultValue("60") int bootstrapRateLimitPerMinute,
        @DefaultValue({"accounts:read", "transfers:read"}) List<String> apiKeyScopes) {
}
