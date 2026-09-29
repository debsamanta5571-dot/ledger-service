package com.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** Verifies invariants that are enforced by the schema itself, independent of application code. */
class SchemaIT extends AbstractIntegrationTest {


    private long seedEntry() {
        UUID account = UUID.randomUUID();
        UUID tx = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, name, currency, type) VALUES (?, 'a', 'USD', 'ASSET')", account);
        jdbc.update("INSERT INTO transactions (id) VALUES (?)", tx);
        return jdbc.queryForObject("""
                INSERT INTO entries (transaction_id, account_id, direction, amount)
                VALUES (?, ?, 'DEBIT', 100) RETURNING id
                """, Long.class, tx, account);
    }

    @Test
    void entriesCannotBeUpdated() {
        long id = seedEntry();
        assertThatThrownBy(() -> jdbc.update("UPDATE entries SET amount = 1 WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("SELECT amount FROM entries WHERE id = ?", Long.class, id)).isEqualTo(100);
    }

    @Test
    void entriesCannotBeDeleted() {
        long id = seedEntry();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM entries WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void entriesCannotBeTruncated() {
        seedEntry();
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE entries"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void entriesRejectNonPositiveAmounts() {
        UUID account = UUID.randomUUID();
        UUID tx = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, name, currency, type) VALUES (?, 'a', 'USD', 'ASSET')", account);
        jdbc.update("INSERT INTO transactions (id) VALUES (?)", tx);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO entries (transaction_id, account_id, direction, amount) VALUES (?, ?, 'DEBIT', 0)",
                tx, account))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void accountsRejectInvalidTypeCurrencyAndNegativeOverdraft() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO accounts (id, name, currency, type) VALUES (?, 'a', 'USD', 'EQUITY')", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO accounts (id, name, currency, type) VALUES (?, 'a', 'usd', 'ASSET')", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO accounts (id, name, currency, type, overdraft_limit) VALUES (?, 'a', 'USD', 'ASSET', -1)",
                UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }
}
