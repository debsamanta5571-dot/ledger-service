package com.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledger.AbstractIntegrationTest;
import com.ledger.account.AccountType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Regression tests for the behaviour fixed in the code audit (V4 migration, key rotation, lock timeout). */
class AuditFixesIT extends AbstractIntegrationTest {

    @Autowired ApiKeyRepository keys;
    @Autowired PlatformTransactionManager transactionManager;

    private boolean isActive(String plaintext) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM api_keys WHERE key_hash = ? AND active)",
                Boolean.class, ApiKeyHasher.sha256Hex(plaintext)));
    }

    @Test
    void changingANamedKeyRevokesThePreviousOne() {
        String name = "rotating-" + UUID.randomUUID();
        String first = "first-" + UUID.randomUUID();
        String second = "second-" + UUID.randomUUID();

        keys.replaceNamedKey(name, ApiKeyHasher.sha256Hex(first), 10);
        UUID idBefore = keys.findActiveByHash(ApiKeyHasher.sha256Hex(first)).orElseThrow().id();
        keys.replaceNamedKey(name, ApiKeyHasher.sha256Hex(second), 20);
        // The id is the caller's identity and owns its accounts, so rotation must keep it.
        assertThat(keys.findActiveByHash(ApiKeyHasher.sha256Hex(second)).orElseThrow().id()).isEqualTo(idBefore);

        assertThat(isActive(first)).as("old key must stop working").isFalse();
        assertThat(isActive(second)).isTrue();
        assertThat(keys.findActiveByHash(ApiKeyHasher.sha256Hex(second))).get()
                .extracting(ApiKey::rateLimitPerMinute).isEqualTo(20);

        // Rotating back works too, still on the same key record.
        keys.replaceNamedKey(name, ApiKeyHasher.sha256Hex(first), 10);
        assertThat(isActive(first)).isTrue();
        assertThat(isActive(second)).isFalse();
    }

    @Test
    void databaseRejectsOversizedNamesAndOverdrafts() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO accounts (id, owner_id, name, currency, type) VALUES (?, 'raw-test', ?, 'USD', 'ASSET')",
                UUID.randomUUID(), "x".repeat(201)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO accounts (id, owner_id, name, currency, type, overdraft_limit) VALUES (?, 'raw-test', 'a', 'USD', 'ASSET', ?)",
                UUID.randomUUID(), 1_000_000_000_000_000L))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void connectionsCarryALockTimeoutSoAHotAccountCannotHangARequestForever() {
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
    }

    @Test
    void aLockTimeoutIsClassifiedAsTemporarySoTheApiReturns503() throws Exception {
        UUID account = newAccount(AccountType.ASSET);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        tx.executeWithoutResult(holder -> {
            jdbc.queryForList("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", account);
            // A second transaction (own thread, own connection) wants the same row and gives up after 100 ms.
            Thread contender = new Thread(() -> {
                try {
                    tx.executeWithoutResult(waiter -> {
                        jdbc.execute("SET LOCAL lock_timeout = '100ms'");
                        jdbc.queryForList("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", account);
                    });
                } catch (RuntimeException e) {
                    failure.set(e);
                }
            });
            contender.start();
            try {
                contender.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Spring reports 55P03 as an UncategorizedSQLException, which used to fall through to a 500.
        assertThat(failure.get()).isInstanceOf(DataAccessException.class);
        assertThat(GlobalExceptionHandler.isTemporary((DataAccessException) failure.get())).isTrue();
    }
}
