package com.ledger.transfer;

import com.ledger.ledger.EntryDraft;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the transaction header and returns its creation time. */
    public Instant insertTransaction(UUID id, String description) {
        return jdbc.sql("INSERT INTO transactions (id, description) VALUES (:id, :description) RETURNING created_at")
                .param("id", id)
                .param("description", description)
                .query((rs, n) -> rs.getTimestamp("created_at").toInstant())
                .single();
    }

    public void insertEntries(UUID transactionId, List<EntryDraft> entries) {
        for (EntryDraft e : entries) {
            jdbc.sql("""
                    INSERT INTO entries (transaction_id, account_id, direction, amount)
                    VALUES (:tx, :account, :direction, :amount)
                    """)
                    .param("tx", transactionId)
                    .param("account", e.accountId())
                    .param("direction", e.direction().name())
                    .param("amount", e.amount())
                    .update();
        }
    }
}
