package com.ledger.account;

import java.time.Instant;
import java.util.UUID;

/** {@code closedAt} is null while the account is open. */
public record Account(
        UUID id,
        String name,
        String currency,
        AccountType type,
        long overdraftLimit,
        Instant createdAt,
        Instant closedAt) {

    public boolean isClosed() {
        return closedAt != null;
    }
}
