package com.ledger.reporting;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rolls the entry journal up into the {@code daily_balances} reporting table. */
@Service
public class DailyBalanceService {

    private static final Logger log = LoggerFactory.getLogger(DailyBalanceService.class);
    private static final long ADVISORY_LOCK_KEY = 727_001L;

    private final JdbcClient jdbc;

    public DailyBalanceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Brings the table up to date through {@code lastDay} (inclusive), processing every missing day since the
     * last rolled-up one, so a missed run is repaired by the next one. A transaction-scoped advisory lock
     * makes concurrent runs on several application instances safe: the loser returns immediately.
     *
     * @return number of daily_balances rows written
     */
    @Transactional
    public int rollUpThrough(LocalDate lastDay) {
        boolean locked = Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", ADVISORY_LOCK_KEY).query(Boolean.class).single());
        if (!locked) {
            log.info("Daily balance rollup already running elsewhere; skipping");
            return 0;
        }
        Optional<LocalDate> start = nextDayToProcess();
        int rows = 0;
        for (LocalDate day = start.orElse(lastDay.plusDays(1)); !day.isAfter(lastDay); day = day.plusDays(1)) {
            rows += rollUpDay(day);
        }
        log.info("Daily balance rollup through {} wrote {} rows", lastDay, rows);
        return rows;
    }

    /** Idempotent: re-running a day replaces that day's rows with freshly computed values. */
    @Transactional
    public int rollUpDay(LocalDate day) {
        OffsetDateTime start = day.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        return jdbc.sql("""
                WITH day_activity AS (
                    SELECT account_id,
                           COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS debits,
                           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits,
                           COUNT(*) AS cnt
                    FROM entries
                    WHERE created_at >= :start AND created_at < :end
                    GROUP BY account_id
                ), cumulative AS (
                    SELECT e.account_id,
                           SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount ELSE -e.amount END) AS net
                    FROM entries e
                    WHERE e.created_at < :end
                      AND e.account_id IN (SELECT account_id FROM day_activity)
                    GROUP BY e.account_id
                )
                INSERT INTO daily_balances (account_id, balance_date, debit_total, credit_total, entry_count,
                                            closing_balance)
                SELECT a.id, :day, d.debits, d.credits, d.cnt,
                       CASE a.type WHEN 'ASSET' THEN c.net ELSE -c.net END
                FROM day_activity d
                JOIN cumulative c ON c.account_id = d.account_id
                JOIN accounts a ON a.id = d.account_id
                ON CONFLICT (account_id, balance_date) DO UPDATE
                    SET debit_total = EXCLUDED.debit_total,
                        credit_total = EXCLUDED.credit_total,
                        entry_count = EXCLUDED.entry_count,
                        closing_balance = EXCLUDED.closing_balance,
                        computed_at = now()
                """)
                .param("start", start)
                .param("end", end)
                .param("day", day)
                .update();
    }

    private Optional<LocalDate> nextDayToProcess() {
        Optional<LocalDate> last = jdbc.sql("SELECT MAX(balance_date) FROM daily_balances")
                .query((rs, n) -> rs.getObject(1, LocalDate.class)).optional();
        if (last.isPresent()) {
            return last.map(d -> d.plusDays(1));
        }
        return jdbc.sql("SELECT (MIN(created_at) AT TIME ZONE 'UTC')::date FROM entries")
                .query((rs, n) -> rs.getObject(1, LocalDate.class)).optional();
    }
}
