package com.ledger.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is calling, as the business logic needs it.
 *
 * @param id    stable identity: owns accounts and scopes idempotency keys. An API key is identified by its id (kept
 *              across key rotation, see {@link ApiKeyRepository#replaceNamedKey}), an identity-service user by
 *              {@code "user:" + subject}.
 * @param name  readable label for audit trails and the admin view (the user's name, or the API key's name)
 * @param admin holds the {@code ledger:admin} scope: may act on every account, not only their own
 */
public record Caller(String id, String name, boolean admin) {

    public static final String ADMIN_AUTHORITY = "SCOPE_ledger:admin";

    public static Caller of(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String name = firstNonBlank(jwt.getToken().getClaimAsString("name"),
                    jwt.getToken().getClaimAsString("email"), jwt.getName());
            return new Caller("user:" + jwt.getName(), name, admin);
        }
        // API keys: the principal is the key id; ApiKeyAuthFilter puts the key's name in the details.
        Object details = authentication.getDetails();
        String name = details instanceof String keyName ? "API key '" + keyName + "'" : "API key";
        return new Caller(authentication.getName(), name, admin);
    }

    /** Whether this caller may act on an account owned by {@code ownerId}. */
    public boolean mayActOn(String ownerId) {
        return admin || id.equals(ownerId);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "unknown";
    }
}
