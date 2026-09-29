package com.ledger.account;

import java.util.UUID;

/** Closing needs a zero balance; otherwise the money would be stranded in an account nobody can use. */
public class AccountNotEmptyException extends RuntimeException {

    private final long balance;

    public AccountNotEmptyException(UUID id, long balance) {
        super("Account " + id + " has balance " + balance + "; move it to zero before closing");
        this.balance = balance;
    }

    public long balance() {
        return balance;
    }
}
