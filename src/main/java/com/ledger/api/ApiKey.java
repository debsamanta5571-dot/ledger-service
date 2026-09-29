package com.ledger.api;

import java.util.List;
import java.util.UUID;

/**
 * An active API key. A <em>personal</em> key has an {@code ownerId} and acts as that person (see V8); a service key
 * (the bootstrap key) has none and acts as itself. {@code scopes} null means the configured default.
 */
public record ApiKey(UUID id, String name, int rateLimitPerMinute, String ownerId, String ownerName,
                     List<String> scopes) {

    public ApiKey(UUID id, String name, int rateLimitPerMinute) {
        this(id, name, rateLimitPerMinute, null, null, null);
    }

    /** The {@link Caller} id this key acts as. */
    public String principal() {
        return ownerId != null ? ownerId : id.toString();
    }

    /** Readable label for audit trails: "Ada (API key)" for a personal key, "API key 'bootstrap'" otherwise. */
    public String label() {
        return ownerName != null ? ownerName + " (API key)" : "API key '" + name + "'";
    }
}
