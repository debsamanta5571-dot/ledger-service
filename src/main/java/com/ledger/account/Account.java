package com.ledger.account;

import java.time.Instant;
import java.util.UUID;

public record Account(
        UUID id,
        String name,
        String currency,
        AccountType type,
        long overdraftLimit,
        Instant createdAt) {
}
