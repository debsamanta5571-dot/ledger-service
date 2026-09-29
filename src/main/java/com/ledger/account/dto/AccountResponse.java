package com.ledger.account.dto;

import com.ledger.account.Account;
import com.ledger.account.AccountType;
import java.time.Instant;
import java.util.UUID;

/** Amounts are integers in minor units (e.g. cents). {@code closedAt} is null while the account is open. */
public record AccountResponse(
        UUID id,
        String name,
        String currency,
        AccountType type,
        long overdraftLimit,
        long balance,
        Instant createdAt,
        Instant closedAt) {

    public static AccountResponse of(Account a, long balance) {
        return new AccountResponse(a.id(), a.name(), a.currency(), a.type(), a.overdraftLimit(), balance, a.createdAt(),
                a.closedAt());
    }
}
