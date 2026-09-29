package com.ledger.statement.dto;

import com.ledger.ledger.Direction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Balances are in the account's normal-balance terms (minor units). {@code openingBalance} is the balance at
 * the start of {@code from}; {@code closingBalance} is the balance at the end of {@code to}; both cover the
 * whole date range, not just the requested page.
 */
public record StatementResponse(
        UUID accountId,
        String currency,
        LocalDate from,
        LocalDate to,
        long openingBalance,
        long closingBalance,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<Line> entries) {

    public record Line(
            long entryId,
            UUID transactionId,
            Direction direction,
            long amount,
            long balanceAfter,
            String description,
            // Who initiated the transfer (null for entries recorded before this was tracked).
            String initiatedBy,
            Instant createdAt) {
    }
}
