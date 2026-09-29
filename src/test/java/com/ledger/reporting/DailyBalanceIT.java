package com.ledger.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.AbstractIntegrationTest;
import com.ledger.account.AccountType;
import com.ledger.ledger.Direction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DailyBalanceIT extends AbstractIntegrationTest {

    @Autowired DailyBalanceService service;

    private List<Map<String, Object>> rows(UUID account) {
        return jdbc.queryForList("""
                SELECT balance_date, debit_total, credit_total, entry_count, closing_balance
                FROM daily_balances WHERE account_id = ? ORDER BY balance_date
                """, account);
    }

    @Test
    void rollUpDayAggregatesThatDaysEntriesAndCarriesTheRunningBalance() {
        UUID asset = newAccount(AccountType.ASSET);
        UUID liability = newAccount(AccountType.LIABILITY);
        posting(asset, Direction.DEBIT, liability, Direction.CREDIT, 1000, Instant.parse("2020-01-14T10:00:00Z"));
        posting(asset, Direction.DEBIT, liability, Direction.CREDIT, 300, Instant.parse("2020-01-15T08:00:00Z"));
        posting(asset, Direction.CREDIT, liability, Direction.DEBIT, 100, Instant.parse("2020-01-15T20:00:00Z"));
        posting(asset, Direction.DEBIT, liability, Direction.CREDIT, 999, Instant.parse("2020-01-16T00:00:00Z"));

        int written = service.rollUpDay(LocalDate.parse("2020-01-15"));

        assertThat(written).isGreaterThanOrEqualTo(2);
        Map<String, Object> asset15 = rows(asset).get(0);
        assertThat(asset15.get("balance_date").toString()).isEqualTo("2020-01-15");
        assertThat(((Number) asset15.get("debit_total")).longValue()).isEqualTo(300);
        assertThat(((Number) asset15.get("credit_total")).longValue()).isEqualTo(100);
        assertThat(((Number) asset15.get("entry_count")).intValue()).isEqualTo(2);
        // 1000 (prior day) + 300 - 100; the entry at exactly 2020-01-16T00:00Z belongs to the next day.
        assertThat(((Number) asset15.get("closing_balance")).longValue()).isEqualTo(1200);

        // Liability balance is credit-normal, so the mirror-image entries give the same positive number.
        assertThat(((Number) rows(liability).get(0).get("closing_balance")).longValue()).isEqualTo(1200);
    }

    @Test
    void rollUpDayIsIdempotent() {
        UUID asset = newAccount(AccountType.ASSET);
        UUID liability = newAccount(AccountType.LIABILITY);
        posting(asset, Direction.DEBIT, liability, Direction.CREDIT, 500, Instant.parse("2020-03-01T12:00:00Z"));

        service.rollUpDay(LocalDate.parse("2020-03-01"));
        service.rollUpDay(LocalDate.parse("2020-03-01"));

        assertThat(rows(asset)).hasSize(1);
        assertThat(((Number) rows(asset).get(0).get("closing_balance")).longValue()).isEqualTo(500);
    }

    @Test
    void rollUpThroughFillsEveryDayWithActivityAndSkipsQuietDays() {
        // Start from an empty reporting table so "resume after the last rolled-up day" cannot depend on the
        // order in which the other tests in this class ran. daily_balances is derived data, so this is safe.
        jdbc.update("DELETE FROM daily_balances");
        UUID asset = newAccount(AccountType.ASSET);
        UUID liability = newAccount(AccountType.LIABILITY);
        posting(asset, Direction.DEBIT, liability, Direction.CREDIT, 100, Instant.parse("2020-02-01T12:00:00Z"));
        posting(asset, Direction.DEBIT, liability, Direction.CREDIT, 50, Instant.parse("2020-02-03T12:00:00Z"));

        service.rollUpThrough(LocalDate.parse("2020-02-03"));

        List<Map<String, Object>> rows = rows(asset);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("balance_date").toString()).isEqualTo("2020-02-01");
        assertThat(((Number) rows.get(0).get("closing_balance")).longValue()).isEqualTo(100);
        assertThat(rows.get(1).get("balance_date").toString()).isEqualTo("2020-02-03");
        assertThat(((Number) rows.get(1).get("closing_balance")).longValue()).isEqualTo(150);
    }

    @Test
    void lateRunPicksUpChangesToAlreadyRolledDaysOnlyWhenThatDayIsRerun() {
        UUID asset = newAccount(AccountType.ASSET);
        UUID liability = newAccount(AccountType.LIABILITY);
        posting(asset, Direction.DEBIT, liability, Direction.CREDIT, 100, Instant.parse("2020-04-01T12:00:00Z"));
        service.rollUpDay(LocalDate.parse("2020-04-01"));

        posting(asset, Direction.DEBIT, liability, Direction.CREDIT, 25, Instant.parse("2020-04-01T13:00:00Z"));
        assertThat(((Number) rows(asset).get(0).get("closing_balance")).longValue()).isEqualTo(100);

        service.rollUpDay(LocalDate.parse("2020-04-01"));
        assertThat(((Number) rows(asset).get(0).get("closing_balance")).longValue()).isEqualTo(125);
        assertThat(((Number) rows(asset).get(0).get("entry_count")).intValue()).isEqualTo(2);
    }
}
