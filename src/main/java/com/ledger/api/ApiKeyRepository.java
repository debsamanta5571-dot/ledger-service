package com.ledger.api;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ApiKeyRepository {

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ApiKey> findActiveByHash(String keyHash) {
        return jdbc.sql("""
                SELECT id, name, rate_limit_per_minute FROM api_keys
                WHERE key_hash = :hash AND active
                """)
                .param("hash", keyHash)
                .query((rs, n) -> new ApiKey(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getInt("rate_limit_per_minute")))
                .optional();
    }

    /**
     * Makes {@code keyHash} the one active key called {@code name}, and revokes any other key with that name (without
     * that, changing the configured bootstrap key left the previous one working forever).
     *
     * <p>Rotation replaces the secret on the EXISTING row rather than adding a new one, so the key keeps its id. The
     * id is the caller's identity ({@link Caller}): it owns accounts and scopes idempotency keys, so a new id on
     * rotation would orphan every account the key had created.
     */
    @Transactional
    public void replaceNamedKey(String name, String keyHash, int rateLimitPerMinute) {
        int rotated = jdbc.sql("""
                UPDATE api_keys SET key_hash = :hash, rate_limit_per_minute = :limit, active = TRUE
                WHERE id = (SELECT id FROM api_keys WHERE name = :name ORDER BY active DESC, created_at LIMIT 1)
                """)
                .param("name", name)
                .param("hash", keyHash)
                .param("limit", rateLimitPerMinute)
                .update();
        if (rotated == 0) {
            jdbc.sql("""
                    INSERT INTO api_keys (id, name, key_hash, rate_limit_per_minute)
                    VALUES (:id, :name, :hash, :limit)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("name", name)
                    .param("hash", keyHash)
                    .param("limit", rateLimitPerMinute)
                    .update();
        }
        jdbc.sql("UPDATE api_keys SET active = FALSE WHERE name = :name AND key_hash <> :hash AND active")
                .param("name", name)
                .param("hash", keyHash)
                .update();
    }
}
