package com.ledger.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Methods named {@code ...Owned...} match on both id AND owner, in SQL. A caller asking for someone else's account
 * therefore gets the same empty result as for an account that does not exist (the API answers 404 for both, so it
 * never confirms that another customer's account id is real), and no other customer's row is ever locked.
 */
@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, owner_id, name, currency, type, overdraft_limit, created_at, closed_at";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account insert(Account account) {
        return jdbc.sql("""
                INSERT INTO accounts (id, owner_id, name, currency, type, overdraft_limit)
                VALUES (:id, :owner, :name, :currency, :type, :overdraftLimit)
                RETURNING %s
                """.formatted(COLUMNS))
                .param("id", account.id())
                .param("owner", account.ownerId())
                .param("name", account.name())
                .param("currency", account.currency())
                .param("type", account.type().name())
                .param("overdraftLimit", account.overdraftLimit())
                .query(AccountRepository::map)
                .single();
    }

    public Optional<Account> findOwned(UUID id, String owner) {
        return one("SELECT %s FROM accounts WHERE id = :id AND owner_id = :owner", id, owner);
    }

    public record AccountWithNet(Account account, long debitsMinusCredits, long entryCount) {
    }

    /** The owner's accounts, most recently created first, with derived balances. Closed ones only if asked for. */
    public List<AccountWithNet> findRecentOwnedWithNet(String owner, int limit, boolean includeClosed) {
        return jdbc.sql("""
                SELECT a.id, a.owner_id, a.name, a.currency, a.type, a.overdraft_limit, a.created_at, a.closed_at,
                       COALESCE((SELECT SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount ELSE -e.amount END)
                                 FROM entries e WHERE e.account_id = a.id), 0) AS net,
                       (SELECT COUNT(*) FROM entries e WHERE e.account_id = a.id) AS entry_count
                FROM (SELECT * FROM accounts
                      WHERE owner_id = :owner AND (closed_at IS NULL OR :includeClosed)
                      ORDER BY created_at DESC, id LIMIT :limit) a
                ORDER BY a.created_at DESC, a.id
                """)
                .param("owner", owner)
                .param("limit", limit)
                .param("includeClosed", includeClosed)
                .query((rs, n) -> new AccountWithNet(map(rs, n), rs.getLong("net"), rs.getLong("entry_count")))
                .list();
    }

    /**
     * Locks the caller's own account as the SOURCE of a transfer, until the surrounding transaction ends. Every
     * posting that could lower an account's balance takes this lock first, which serialises those postings.
     *
     * <p>{@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}: inserting an entry for the OTHER account takes a
     * {@code FOR KEY SHARE} lock on that account's row (foreign-key check). {@code FOR UPDATE} conflicts with it,
     * so A-to-B and B-to-A transfers deadlocked (found by ConcurrencyIT). {@code NO KEY UPDATE} still conflicts
     * with itself, so transfers on one source still queue, but it does not block foreign-key checks.
     */
    public Optional<Account> findOwnedForTransferSource(UUID id, String owner) {
        return one("SELECT %s FROM accounts WHERE id = :id AND owner_id = :owner FOR NO KEY UPDATE", id, owner);
    }

    /**
     * Share-locks a transfer's DESTINATION, which may belong to anyone (paying another customer is allowed), so it
     * cannot be closed or deleted until this transaction ends. Without it, a transfer could read "open", the account
     * could then be closed at balance 0, and the credit would land in a closed account. {@code FOR KEY SHARE}
     * conflicts only with the {@code FOR UPDATE} of {@link #findOwnedForClose}; it does not block other transfers.
     */
    public Optional<Account> findForTransferDestination(UUID id) {
        return jdbc.sql("SELECT %s FROM accounts WHERE id = :id FOR KEY SHARE".formatted(COLUMNS))
                .param("id", id)
                .query(AccountRepository::map)
                .optional();
    }

    /**
     * Exclusive lock for closing or deleting the caller's own account. {@code FOR UPDATE} conflicts with both locks
     * transfers take (NO KEY UPDATE on a source, KEY SHARE on a destination), so it waits for in-flight transfers
     * touching this account and blocks new ones until it commits. The balance read after it is therefore final.
     */
    public Optional<Account> findOwnedForClose(UUID id, String owner) {
        return one("SELECT %s FROM accounts WHERE id = :id AND owner_id = :owner FOR UPDATE", id, owner);
    }

    public void markClosed(UUID id) {
        jdbc.sql("UPDATE accounts SET closed_at = clock_timestamp() WHERE id = :id AND closed_at IS NULL")
                .param("id", id)
                .update();
    }

    public long entryCount(UUID accountId) {
        return jdbc.sql("SELECT COUNT(*) FROM entries WHERE account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    /** Hard delete. Callers must hold {@link #findOwnedForClose}'s lock and have checked there are no entries. */
    public void delete(UUID id) {
        jdbc.sql("DELETE FROM accounts WHERE id = :id").param("id", id).update();
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

    private Optional<Account> one(String sqlTemplate, UUID id, String owner) {
        return jdbc.sql(sqlTemplate.formatted(COLUMNS))
                .param("id", id)
                .param("owner", owner)
                .query(AccountRepository::map)
                .optional();
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("owner_id"),
                rs.getString("name"),
                rs.getString("currency"),
                AccountType.valueOf(rs.getString("type")),
                rs.getLong("overdraft_limit"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant());
    }
}
