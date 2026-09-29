package com.ledger.transfer;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public record Stored(String requestHash, Integer status, String body) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key for this transaction. Returns false if the key already exists. If a concurrent
     * transaction is mid-flight with the same key, Postgres blocks this insert until that transaction
     * commits or rolls back, so duplicates are serialized by the unique index itself.
     */
    public boolean claim(String clientId, String key, String requestHash) {
        return jdbc.sql("""
                INSERT INTO idempotency_keys (client_id, idempotency_key, request_hash)
                VALUES (:client, :key, :hash)
                ON CONFLICT DO NOTHING
                """)
                .param("client", clientId)
                .param("key", key)
                .param("hash", requestHash)
                .update() == 1;
    }

    public Optional<Stored> find(String clientId, String key) {
        return jdbc.sql("""
                SELECT request_hash, response_status, response_body::text AS body
                FROM idempotency_keys WHERE client_id = :client AND idempotency_key = :key
                """)
                .param("client", clientId)
                .param("key", key)
                .query((rs, n) -> new Stored(rs.getString("request_hash"), (Integer) rs.getObject("response_status"),
                        rs.getString("body")))
                .optional();
    }

    public void complete(String clientId, String key, int status, String body) {
        jdbc.sql("""
                UPDATE idempotency_keys SET response_status = :status, response_body = CAST(:body AS jsonb)
                WHERE client_id = :client AND idempotency_key = :key
                """)
                .param("status", status)
                .param("body", body)
                .param("client", clientId)
                .param("key", key)
                .update();
    }
}
