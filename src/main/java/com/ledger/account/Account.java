package com.ledger.account;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code ownerId} is a {@link com.ledger.api.Caller} id and decides who may act on the account; {@code ownerName} is
 * only a readable label captured at creation. {@code closedAt} is null while the account is open.
 */
public record Account(
        UUID id,
        String ownerId,
        String ownerName,
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
