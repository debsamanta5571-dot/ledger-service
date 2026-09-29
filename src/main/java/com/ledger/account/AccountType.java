package com.ledger.account;

import com.ledger.ledger.Direction;

/**
 * Normal-balance side of an account. An ASSET grows with debits, a LIABILITY
 * grows with credits. Balances are reported in the account's own normal-balance
 * terms, so a healthy account of either type shows a positive number.
 */
public enum AccountType {
    ASSET(1, Direction.DEBIT, Direction.CREDIT),
    LIABILITY(-1, Direction.CREDIT, Direction.DEBIT);

    private final int debitSign;
    private final Direction increaseSide;
    private final Direction decreaseSide;

    AccountType(int debitSign, Direction increaseSide, Direction decreaseSide) {
        this.debitSign = debitSign;
        this.increaseSide = increaseSide;
        this.decreaseSide = decreaseSide;
    }

    /** @param debitsMinusCredits sum(debit amounts) - sum(credit amounts), in minor units */
    public long balanceFrom(long debitsMinusCredits) {
        return debitSign * debitsMinusCredits;
    }

    /** The entry direction that raises this account's balance. */
    public Direction increaseSide() {
        return increaseSide;
    }

    /** The entry direction that lowers this account's balance. */
    public Direction decreaseSide() {
        return decreaseSide;
    }
}
