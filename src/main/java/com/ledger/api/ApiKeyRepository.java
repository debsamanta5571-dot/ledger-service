package com.ledger.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ApiKeyRepository {

    /** What a key looks like to its owner: never the secret or its hash. */
    public record KeyInfo(UUID id, String name, String ownerId, String ownerName, List<String> scopes, boolean active,
                          Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ApiKey> findActiveByHash(String keyHash) {
        return jdbc.sql("""
                SELECT id, name, rate_limit_per_minute, owner_id, owner_name, scopes FROM api_keys
                WHERE key_hash = :hash AND active
                """)
                .param("hash", keyHash)
                .query((rs, n) -> new ApiKey(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getInt("rate_limit_per_minute"), rs.getString("owner_id"), rs.getString("owner_name"),
                        scopes(rs.getString("scopes"))))
                .optional();
    }

    /**
     * Makes {@code keyHash} the one active SERVICE key (no owner) called {@code name}, and revokes any other service
     * key with that name (without that, changing the configured bootstrap key left the previous one working forever).
     * Personal keys are never touched, even if someone named theirs "bootstrap".
     *
     * <p>Rotation replaces the secret on the EXISTING row rather than adding a new one, so the key keeps its id. The
     * id is the caller's identity ({@link Caller}): it owns accounts and scopes idempotency keys, so a new id on
     * rotation would orphan every account the key had created.
     */
    @Transactional
    public void replaceNamedKey(String name, String keyHash, int rateLimitPerMinute) {
        int rotated = jdbc.sql("""
                UPDATE api_keys SET key_hash = :hash, rate_limit_per_minute = :limit, active = TRUE
                WHERE id = (SELECT id FROM api_keys WHERE name = :name AND owner_id IS NULL
                            ORDER BY active DESC, created_at LIMIT 1)
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
        jdbc.sql("""
                UPDATE api_keys SET active = FALSE
                WHERE name = :name AND owner_id IS NULL AND key_hash <> :hash AND active
                """)
                .param("name", name)
                .param("hash", keyHash)
                .update();
    }

    /** Stores a new personal key (only its hash) that acts as {@code ownerId} with exactly {@code scopes}. */
    public KeyInfo insertPersonal(String name, String keyHash, int rateLimitPerMinute, String ownerId,
                                  String ownerName, List<String> scopes, String createdBy) {
        return jdbc.sql("""
                INSERT INTO api_keys (id, name, key_hash, rate_limit_per_minute, owner_id, owner_name, scopes, created_by)
                VALUES (:id, :name, :hash, :limit, :owner, :ownerName, :scopes, :createdBy)
                RETURNING id, name, owner_id, owner_name, scopes, active, created_at
                """)
                .param("id", UUID.randomUUID())
                .param("name", name)
                .param("hash", keyHash)
                .param("limit", rateLimitPerMinute)
                .param("owner", ownerId)
                .param("ownerName", ownerName)
                .param("scopes", String.join(" ", scopes))
                .param("createdBy", createdBy)
                .query(ApiKeyRepository::info)
                .single();
    }

    /** A caller's personal keys (an admin: everyone's), newest first. */
    public List<KeyInfo> listVisible(Caller caller) {
        return jdbc.sql("""
                SELECT id, name, owner_id, owner_name, scopes, active, created_at FROM api_keys
                WHERE owner_id IS NOT NULL AND (owner_id = :owner OR :admin)
                ORDER BY created_at DESC
                """)
                .param("owner", caller.id())
                .param("admin", caller.admin())
                .query(ApiKeyRepository::info)
                .list();
    }

    /** Revokes a personal key the caller may act on. False if there is no such key (or it is not theirs). */
    public boolean revoke(UUID id, Caller caller) {
        return jdbc.sql("""
                UPDATE api_keys SET active = FALSE
                WHERE id = :id AND owner_id IS NOT NULL AND (owner_id = :owner OR :admin)
                """)
                .param("id", id)
                .param("owner", caller.id())
                .param("admin", caller.admin())
                .update() == 1;
    }

    private static KeyInfo info(ResultSet rs, int row) throws SQLException {
        return new KeyInfo(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("owner_id"),
                rs.getString("owner_name"), scopes(rs.getString("scopes")), rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static List<String> scopes(String spaceSeparated) {
        return spaceSeparated == null ? null : Arrays.stream(spaceSeparated.split(" ")).filter(s -> !s.isBlank()).toList();
    }
}
