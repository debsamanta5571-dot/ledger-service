package com.ledger.ledger;

import java.util.UUID;

/** An entry that has been decided on but not yet persisted. Amount is in minor units and always positive. */
public record EntryDraft(UUID accountId, Direction direction, long amount) {
}
