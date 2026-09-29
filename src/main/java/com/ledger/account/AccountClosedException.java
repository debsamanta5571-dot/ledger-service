package com.ledger.account;

import java.util.UUID;

/** A transfer named a closed account. */
public class AccountClosedException extends RuntimeException {
    public AccountClosedException(UUID id) {
        super("Account " + id + " is closed");
    }
}
