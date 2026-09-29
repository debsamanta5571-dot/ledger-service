package com.ledger.api;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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

    public void insertIfAbsent(String name, String keyHash, int rateLimitPerMinute) {
        jdbc.sql("""
                INSERT INTO api_keys (id, name, key_hash, rate_limit_per_minute)
                VALUES (:id, :name, :hash, :limit)
                ON CONFLICT (key_hash) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("name", name)
                .param("hash", keyHash)
                .param("limit", rateLimitPerMinute)
                .update();
    }
}
