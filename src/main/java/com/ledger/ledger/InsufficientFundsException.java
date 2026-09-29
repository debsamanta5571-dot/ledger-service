package com.ledger.ledger;

public class InsufficientFundsException extends RuntimeException {

    private final long available;

    public InsufficientFundsException(long available, long requested) {
        super("Insufficient funds: %d available (including overdraft), %d requested".formatted(available, requested));
        this.available = available;
    }

    /** Balance plus overdraft limit at the time of the check, in minor units. */
    public long available() {
        return available;
    }
}
