package com.ledger.account.dto;

import com.ledger.account.Account;
import com.ledger.account.AccountType;
import java.time.Instant;
import java.util.UUID;

/**
 * Amounts are integers in minor units (e.g. cents). {@code closedAt} is null while the account is open.
 * {@code entryCount} is how many journal entries the account has; only an account with none can be deleted.
 * {@code ownerId}/{@code ownerName} say whose account it is (useful in an admin's view of every account).
 */
public record AccountResponse(
        UUID id,
        String name,
        String currency,
        AccountType type,
        long overdraftLimit,
        long balance,
        Instant createdAt,
        Instant closedAt,
        long entryCount,
        String ownerId,
        String ownerName) {

    public static AccountResponse of(Account a, long balance, long entryCount) {
        return new AccountResponse(a.id(), a.name(), a.currency(), a.type(), a.overdraftLimit(), balance, a.createdAt(),
                a.closedAt(), entryCount, a.ownerId(), a.ownerName());
    }
}
