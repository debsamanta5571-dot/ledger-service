package com.ledger.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The stable identity of whoever is calling. It owns accounts and scopes idempotency keys, so it must not change for
 * the same caller: an API key is identified by its id (kept across key rotation, see
 * {@link ApiKeyRepository#replaceNamedKey}), an identity-service user by {@code "user:" + subject}.
 */
public final class Caller {

    private Caller() {
    }

    public static String id(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken
                ? "user:" + authentication.getName()
                : authentication.getName();
    }
}
