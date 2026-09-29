package com.ledger.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where to find and how to check tokens from the identity service.
 *
 * @param jwkSetUri the identity service's JWKS endpoint (public keys only)
 * @param issuer    the exact {@code iss} value tokens must carry
 * @param audience  a value that must appear in the token's {@code aud} (this API's name)
 */
@ConfigurationProperties(prefix = "ledger.auth.jwt")
public record JwtProperties(
        String jwkSetUri,
        String issuer,
        @DefaultValue("ledger-api") String audience) {
}
