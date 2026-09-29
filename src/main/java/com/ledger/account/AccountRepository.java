package com.ledger.account;

import com.ledger.api.Caller;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Methods named {@code ...Visible...} return an account only if the caller may act on it: they own it, or they are an
 * admin ({@code ledger:admin}). The check is in the SQL itself, so a normal caller asking for someone else's account
 * gets the same empty result as for an account that does not exist (the API answers 404 for both, so it never
 * confirms that another customer's account id is real), and no other customer's row is ever locked.
 */
@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, owner_id, owner_name, name, currency, type, overdraft_limit, created_at, closed_at";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account insert(Account account) {
        return jdbc.sql("""
                INSERT INTO accounts (id, owner_id, owner_name, name, currency, type, overdraft_limit)
                VALUES (:id, :owner, :ownerName, :name, :currency, :type, :overdraftLimit)
                RETURNING %s
                """.formatted(COLUMNS))
                .param("id", account.id())
                .param("owner", account.ownerId())
                .param("ownerName", account.ownerName())
                .param("name", account.name())
                .param("currency", account.currency())
                .param("type", account.type().name())
                .param("overdraftLimit", account.overdraftLimit())
                .query(AccountRepository::map)
                .single();
    }

    public Optional<Account> findVisible(UUID id, Caller caller) {
        return one("SELECT %s FROM accounts WHERE id = :id AND " + VISIBLE, id, caller);
    }

    public record AccountWithNet(Account account, long debitsMinusCredits, long entryCount) {
    }

    /**
     * Most recently created first, with derived balances. A normal caller gets their own accounts; an admin gets
     * everyone's unless {@code mineOnly}. Closed accounts only if asked for.
     */
    public List<AccountWithNet> findRecentVisibleWithNet(Caller caller, int limit, boolean includeClosed,
                                                         boolean mineOnly) {
        return jdbc.sql("""
                SELECT a.id, a.owner_id, a.owner_name, a.name, a.currency, a.type, a.overdraft_limit, a.created_at, a.closed_at,
                       COALESCE((SELECT SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount ELSE -e.amount END)
                                 FROM entries e WHERE e.account_id = a.id), 0) AS net,
                       (SELECT COUNT(*) FROM entries e WHERE e.account_id = a.id) AS entry_count
                FROM (SELECT * FROM accounts
                      WHERE (owner_id = :owner OR (:admin AND NOT :mineOnly))
                        AND (closed_at IS NULL OR :includeClosed)
                      ORDER BY created_at DESC, id LIMIT :limit) a
                ORDER BY a.created_at DESC, a.id
                """)
                .param("owner", caller.id())
                .param("admin", caller.admin())
                .param("mineOnly", mineOnly)
                .param("limit", limit)
                .param("includeClosed", includeClosed)
                .query((rs, n) -> new AccountWithNet(map(rs, n), rs.getLong("net"), rs.getLong("entry_count")))
                .list();
    }

    /**
     * Locks an account the caller may act on as the SOURCE of a transfer, until the surrounding transaction ends. Every
     * posting that could lower an account's balance takes this lock first, which serialises those postings.
     *
     * <p>{@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}: inserting an entry for the OTHER account takes a
     * {@code FOR KEY SHARE} lock on that account's row (foreign-key check). {@code FOR UPDATE} conflicts with it,
     * so A-to-B and B-to-A transfers deadlocked (found by ConcurrencyIT). {@code NO KEY UPDATE} still conflicts
     * with itself, so transfers on one source still queue, but it does not block foreign-key checks.
     */
    public Optional<Account> findVisibleForTransferSource(UUID id, Caller caller) {
        return one("SELECT %s FROM accounts WHERE id = :id AND " + VISIBLE + " FOR NO KEY UPDATE", id, caller);
    }

    /**
     * Share-locks a transfer's DESTINATION, which may belong to anyone (paying another customer is allowed), so it
     * cannot be closed or deleted until this transaction ends. Without it, a transfer could read "open", the account
     * could then be closed at balance 0, and the credit would land in a closed account. {@code FOR KEY SHARE}
     * conflicts only with the {@code FOR UPDATE} of {@link #findVisibleForClose}; it does not block other transfers.
     */
    public Optional<Account> findForTransferDestination(UUID id) {
        return jdbc.sql("SELECT %s FROM accounts WHERE id = :id FOR KEY SHARE".formatted(COLUMNS))
                .param("id", id)
                .query(AccountRepository::map)
                .optional();
    }

    /**
     * Exclusive lock for closing or deleting an account the caller may act on. {@code FOR UPDATE} conflicts with both locks
     * transfers take (NO KEY UPDATE on a source, KEY SHARE on a destination), so it waits for in-flight transfers
     * touching this account and blocks new ones until it commits. The balance read after it is therefore final.
     */
    public Optional<Account> findVisibleForClose(UUID id, Caller caller) {
        return one("SELECT %s FROM accounts WHERE id = :id AND " + VISIBLE + " FOR UPDATE", id, caller);
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

    /** Hard delete. Callers must hold {@link #findVisibleForClose}'s lock and have checked there are no entries. */
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

    /** The access rule, in SQL: the caller owns the row, or is an admin. */
    private static final String VISIBLE = "(owner_id = :owner OR :admin)";

    private Optional<Account> one(String sqlTemplate, UUID id, Caller caller) {
        return jdbc.sql(sqlTemplate.formatted(COLUMNS))
                .param("id", id)
                .param("owner", caller.id())
                .param("admin", caller.admin())
                .query(AccountRepository::map)
                .optional();
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("owner_id"),
                rs.getString("owner_name"),
                rs.getString("name"),
                rs.getString("currency"),
                AccountType.valueOf(rs.getString("type")),
                rs.getLong("overdraft_limit"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant());
    }
}
