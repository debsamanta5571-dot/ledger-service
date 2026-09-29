package com.ledger.ledger;

/** A posting must not take an account's balance below {@code -overdraftLimit}. */
public final class OverdraftRule {

    private OverdraftRule() {
    }

    /**
     * @param balance        current balance in the account's normal-balance terms (minor units)
     * @param overdraftLimit how far below zero the account may go (>= 0)
     * @param decrease       how much the posting reduces the balance by (> 0)
     */
    public static void check(long balance, long overdraftLimit, long decrease) {
        long available = balance + overdraftLimit;
        if (decrease > available) {
            throw new InsufficientFundsException(available, decrease);
        }
    }
}
