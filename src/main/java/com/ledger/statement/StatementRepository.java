package com.ledger.statement;

import com.ledger.ledger.Direction;
import com.ledger.statement.dto.StatementResponse.Line;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StatementRepository {

    /** All values are (debits - credits) in minor units; the caller converts to normal-balance terms. */
    public record Totals(long openingNet, long inRangeNet, long count) {
    }

    private final JdbcClient jdbc;

    public StatementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One index-only pass: net before {@code from}, net within [from, to), and the row count within it. */
    public Totals totals(UUID accountId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(s) FILTER (WHERE created_at <  :from), 0) AS opening,
                       COALESCE(SUM(s) FILTER (WHERE created_at >= :from), 0) AS in_range,
                       COUNT(*)        FILTER (WHERE created_at >= :from)      AS cnt
                FROM (
                    SELECT created_at, CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END AS s
                    FROM entries
                    WHERE account_id = :id AND created_at < :to
                ) x
                """)
                .param("id", accountId)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new Totals(rs.getLong("opening"), rs.getLong("in_range"), rs.getLong("cnt")))
                .single();
    }

    /**
     * One page of the range, ordered by (created_at, id), each row carrying the running balance after it.
     * The window runs over the whole range and the page is cut afterwards so balances stay correct on
     * every page; cost therefore grows with the size of the date range, not the page number.
     *
     * @param sign          +1 for ASSET, -1 for LIABILITY (turns debit-minus-credit into the normal balance)
     * @param openingNormal balance at the start of the range in normal-balance terms
     */
    public List<Line> page(UUID accountId, OffsetDateTime from, OffsetDateTime to, int sign, long openingNormal,
                           int size, long offset) {
        return jdbc.sql("""
                SELECT id, transaction_id, direction, amount, description, created_at, balance_after
                FROM (
                    SELECT e.id, e.transaction_id, e.direction, e.amount, t.description, e.created_at,
                           CAST(:opening + SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount * :sign
                                                                ELSE -e.amount * :sign END)
                                OVER (ORDER BY e.created_at, e.id) AS BIGINT) AS balance_after
                    FROM entries e
                    JOIN transactions t ON t.id = e.transaction_id
                    WHERE e.account_id = :id AND e.created_at >= :from AND e.created_at < :to
                ) ranged
                ORDER BY created_at, id
                LIMIT :size OFFSET :offset
                """)
                .param("id", accountId)
                .param("from", from)
                .param("to", to)
                .param("sign", sign)
                .param("opening", openingNormal)
                .param("size", size)
                .param("offset", offset)
                .query((rs, n) -> new Line(
                        rs.getLong("id"),
                        rs.getObject("transaction_id", UUID.class),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getLong("amount"),
                        rs.getLong("balance_after"),
                        rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
