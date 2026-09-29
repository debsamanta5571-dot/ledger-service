package com.ledger.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Personal API keys: a key that acts as a person, for scripts. The secret is returned exactly once, at creation;
 * only its SHA-256 is stored.
 *
 * <ul>
 *   <li>Only a signed-in person (bearer token) can create one, not another API key, so a leaked key cannot be used
 *       to mint more keys that outlive it.</li>
 *   <li>A key never carries {@code ledger:admin}: admin power needs an interactive sign-in.</li>
 *   <li>Only an admin can create a key for someone else (used when onboarding a new customer).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api-keys")
public class ApiKeyController {

    /** What a personal key may do: the ledger scopes of a normal customer, never ledger:admin. */
    static final List<String> PERSONAL_SCOPES = List.of("accounts:read", "accounts:write", "transfers:read",
            "transfers:write");
    static final int PERSONAL_RATE_LIMIT_PER_MINUTE = 120;

    public record CreateKeyRequest(
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "user:[A-Za-z0-9-]{1,100}", message = "must be an identity-service user, user:<id>")
            String ownerId,
            @Size(max = 200) String ownerName) {
    }

    public record CreatedKey(UUID id, String name, String key, String ownerId, String ownerName, List<String> scopes,
                             Instant createdAt) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository keys;

    public ApiKeyController(ApiKeyRepository keys) {
        this.keys = keys;
    }

    @Operation(summary = "Create a personal API key",
            description = "The key acts as its owner (you, or for admins, a customer you name). The secret is in "
                    + "this response only; store it now. Requires an interactive sign-in (bearer token).")
    @PostMapping
    public ResponseEntity<CreatedKey> create(Authentication auth, @Valid @RequestBody CreateKeyRequest request) {
        if (!(auth instanceof JwtAuthenticationToken)) {
            throw new ForbiddenOperationException("API keys can only be created by a signed-in person, not by another key");
        }
        Caller caller = Caller.of(auth);
        boolean forSomeoneElse = request.ownerId() != null && !request.ownerId().equals(caller.id());
        if (forSomeoneElse && !caller.admin()) {
            throw new ForbiddenOperationException("Only an admin can create an API key for someone else");
        }
        String ownerId = forSomeoneElse ? request.ownerId() : caller.id();
        String ownerName = forSomeoneElse
                ? (request.ownerName() == null || request.ownerName().isBlank() ? ownerId : request.ownerName())
                : caller.name();

        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        // "lk_" makes leaked keys easy to recognise (and to add to secret scanners).
        String plaintext = "lk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        ApiKeyRepository.KeyInfo info = keys.insertPersonal(request.name().strip(), ApiKeyHasher.sha256Hex(plaintext),
                PERSONAL_RATE_LIMIT_PER_MINUTE, ownerId, ownerName, PERSONAL_SCOPES, caller.id());

        return ResponseEntity.created(URI.create("/api-keys/" + info.id())).body(new CreatedKey(info.id(), info.name(),
                plaintext, info.ownerId(), info.ownerName(), info.scopes(), info.createdAt()));
    }

    @Operation(summary = "List personal API keys", description = "Your keys; an admin sees everyone's. Never the secrets.")
    @GetMapping
    public List<ApiKeyRepository.KeyInfo> list(Authentication auth) {
        return keys.listVisible(Caller.of(auth));
    }

    @Operation(summary = "Revoke a personal API key", description = "It stops working immediately. 404 if not yours.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(Authentication auth, @PathVariable UUID id) {
        if (!keys.revoke(id, Caller.of(auth))) {
            throw new NotFoundException("API key " + id + " not found");
        }
        return ResponseEntity.noContent().build();
    }

    /** Local 404 for keys (accounts have their own exception). */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
