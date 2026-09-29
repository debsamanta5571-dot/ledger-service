package com.ledger.api;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the bundled web page where to sign in. The identity service's address is already configured here (it is the
 * issuer the ledger trusts), so the page reads it at runtime instead of having it baked in at build time. Public:
 * it contains no secrets (a PKCE client has none) and the page needs it before anyone is signed in.
 */
@RestController
public class UiConfigController {

    private final Map<String, String> config;

    public UiConfigController(JwtProperties jwt, @Value("${ledger.auth.ui.client-id:ledger-ui}") String clientId) {
        String issuer = jwt.issuer().replaceAll("/+$", ""); // "http://host:5001/" -> "http://host:5001"
        this.config = Map.of(
                "identityUrl", issuer,
                "clientId", clientId,
                // openid/profile/email identify the user; offline_access gives a refresh token; the rest are the
                // ledger's own scopes. The identity service grants only those the user's role allows, so asking for the
                // admin ones is harmless: only the admin role receives ledger:admin (every account) and users:admin
                // (create sign-in accounts from the "New account" dialog).
                "scope", "openid profile email offline_access"
                        + " accounts:read accounts:write transfers:read transfers:write"
                        + " ledger:admin users:admin");
    }

    @GetMapping("/ui-config")
    public Map<String, String> uiConfig() {
        return config;
    }
}
