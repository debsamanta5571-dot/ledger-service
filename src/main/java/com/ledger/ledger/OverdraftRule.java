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
        // Saturate instead of wrapping: a large balance plus a large limit must never overflow into a negative
        // "available" (which would wrongly refuse a valid transfer) or the reverse.
        long available;
        try {
            available = Math.addExact(balance, overdraftLimit);
        } catch (ArithmeticException overflow) {
            available = balance > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        if (decrease > available) {
            throw new InsufficientFundsException(available, decrease);
        }
    }
}
