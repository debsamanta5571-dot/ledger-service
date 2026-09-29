package com.ledger.transfer.dto;

import com.ledger.ledger.Direction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        UUID fromAccountId,
        UUID toAccountId,
        long amount,
        String currency,
        String description,
        Instant createdAt,
        List<EntryView> entries) {

    public record EntryView(UUID accountId, Direction direction, long amount) {
    }
}
