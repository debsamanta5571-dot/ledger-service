package com.ledger.ledger;

import java.util.List;

/** The core double-entry rule: a transaction's debits must equal its credits. */
public final class BalancingRule {

    private BalancingRule() {
    }

    public static void check(List<EntryDraft> entries) {
        if (entries == null || entries.size() < 2) {
            throw new UnbalancedTransactionException("A transaction needs at least two entries");
        }
        long debits = 0;
        long credits = 0;
        for (EntryDraft e : entries) {
            if (e.amount() <= 0) {
                throw new UnbalancedTransactionException("Entry amounts must be positive");
            }
            try {
                if (e.direction() == Direction.DEBIT) {
                    debits = Math.addExact(debits, e.amount());
                } else {
                    credits = Math.addExact(credits, e.amount());
                }
            } catch (ArithmeticException overflow) {
                throw new UnbalancedTransactionException("Transaction total is too large");
            }
        }
        if (debits != credits) {
            throw new UnbalancedTransactionException(
                    "Unbalanced transaction: debits %d != credits %d".formatted(debits, credits));
        }
    }
}
