package com.ledger.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account insert(Account account) {
        return jdbc.sql("""
                INSERT INTO accounts (id, name, currency, type, overdraft_limit)
                VALUES (:id, :name, :currency, :type, :overdraftLimit)
                RETURNING id, name, currency, type, overdraft_limit, created_at, closed_at
                """)
                .param("id", account.id())
                .param("name", account.name())
                .param("currency", account.currency())
                .param("type", account.type().name())
                .param("overdraftLimit", account.overdraftLimit())
                .query(AccountRepository::map)
                .single();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, name, currency, type, overdraft_limit, created_at, closed_at
                FROM accounts WHERE id = :id
                """)
                .param("id", id)
                .query(AccountRepository::map)
                .optional();
    }

    public record AccountWithNet(Account account, long debitsMinusCredits) {
    }

    /** Most recently created accounts first, each with its derived balance. Closed ones only if asked for. */
    public java.util.List<AccountWithNet> findRecentWithNet(int limit, boolean includeClosed) {
        return jdbc.sql("""
                SELECT a.id, a.name, a.currency, a.type, a.overdraft_limit, a.created_at, a.closed_at,
                       COALESCE((SELECT SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount ELSE -e.amount END)
                                 FROM entries e WHERE e.account_id = a.id), 0) AS net
                FROM (SELECT * FROM accounts WHERE closed_at IS NULL OR :includeClosed
                      ORDER BY created_at DESC, id LIMIT :limit) a
                ORDER BY a.created_at DESC, a.id
                """)
                .param("limit", limit)
                .param("includeClosed", includeClosed)
                .query((rs, n) -> new AccountWithNet(map(rs, n), rs.getLong("net")))
                .list();
    }

    /**
     * Row-locks the account until the surrounding transaction ends. Every posting that could lower an
     * account's balance takes this lock first, which serialises those postings per account.
     *
     * <p>{@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}: inserting an entry for the OTHER account takes a
     * {@code FOR KEY SHARE} lock on that account's row (foreign-key check). {@code FOR UPDATE} conflicts with it,
     * so A-to-B and B-to-A transfers deadlocked (found by ConcurrencyIT). {@code NO KEY UPDATE} still conflicts
     * with itself, so transfers on one source still queue, but it does not block foreign-key checks.
     */
    public Optional<Account> findByIdForUpdate(UUID id) {
        return jdbc.sql("""
                SELECT id, name, currency, type, overdraft_limit, created_at, closed_at
                FROM accounts WHERE id = :id FOR NO KEY UPDATE
                """)
                .param("id", id)
                .query(AccountRepository::map)
                .optional();
    }

    /**
     * Share-locks the account so it cannot be closed until this transaction ends. Transfers take this on the
     * DESTINATION: without it, a transfer could read "open", then the account could be closed (balance 0), and then
     * the transfer's credit would land in a closed account. {@code FOR KEY SHARE} conflicts only with the
     * {@code FOR UPDATE} taken by {@link #findByIdForClose}; it does not block other transfers.
     */
    public Optional<Account> findByIdForKeyShare(UUID id) {
        return jdbc.sql("""
                SELECT id, name, currency, type, overdraft_limit, created_at, closed_at
                FROM accounts WHERE id = :id FOR KEY SHARE
                """)
                .param("id", id)
                .query(AccountRepository::map)
                .optional();
    }

    /**
     * Exclusive lock for closing. {@code FOR UPDATE} conflicts with both locks transfers take (NO KEY UPDATE on a
     * source, KEY SHARE on a destination), so closing waits for in-flight transfers touching this account and
     * blocks new ones until it commits. The balance read after this lock is therefore final.
     */
    public Optional<Account> findByIdForClose(UUID id) {
        return jdbc.sql("""
                SELECT id, name, currency, type, overdraft_limit, created_at, closed_at
                FROM accounts WHERE id = :id FOR UPDATE
                """)
                .param("id", id)
                .query(AccountRepository::map)
                .optional();
    }

    public void markClosed(UUID id) {
        jdbc.sql("UPDATE accounts SET closed_at = clock_timestamp() WHERE id = :id AND closed_at IS NULL")
                .param("id", id)
                .update();
    }

    /** sum(debits) - sum(credits) over the journal; the balance is always derived, never stored. */
    public long debitsMinusCredits(UUID accountId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
                FROM entries WHERE account_id = :id
                """)
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("currency"),
                AccountType.valueOf(rs.getString("type")),
                rs.getLong("overdraft_limit"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant());
    }
}
