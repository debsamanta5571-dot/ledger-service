package com.ledger.account;

import java.time.Instant;
import java.util.UUID;

/** {@code closedAt} is null while the account is open. {@code ownerId} is a {@link com.ledger.api.Caller} id. */
public record Account(
        UUID id,
        String ownerId,
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
