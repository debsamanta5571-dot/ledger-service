package com.ledger.account;

import java.util.UUID;

/** Only a never-used account can be deleted permanently; one with entries can only be closed. */
public class AccountHasHistoryException extends RuntimeException {

    private final long entryCount;

    public AccountHasHistoryException(UUID id, long entryCount) {
        super("Account " + id + " has " + entryCount + " ledger entries, which can never be deleted; close it instead");
        this.entryCount = entryCount;
    }

    public long entryCount() {
        return entryCount;
    }
}
