package com.ledger.ledger;

import static com.ledger.ledger.Direction.CREDIT;
import static com.ledger.ledger.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BalancingRuleTest {

    private static EntryDraft entry(Direction d, long amount) {
        return new EntryDraft(UUID.randomUUID(), d, amount);
    }

    @Test
    void acceptsBalancedPair() {
        assertThatCode(() -> BalancingRule.check(List.of(entry(DEBIT, 100), entry(CREDIT, 100))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsBalancedSplitTransaction() {
        assertThatCode(() -> BalancingRule.check(
                List.of(entry(DEBIT, 100), entry(CREDIT, 60), entry(CREDIT, 40))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnbalancedTransactionAndReportsBothSides() {
        assertThatThrownBy(() -> BalancingRule.check(List.of(entry(DEBIT, 100), entry(CREDIT, 99))))
                .isInstanceOf(UnbalancedTransactionException.class)
                .hasMessageContaining("debits 100")
                .hasMessageContaining("credits 99");
    }

    @Test
    void rejectsAllDebits() {
        assertThatThrownBy(() -> BalancingRule.check(List.of(entry(DEBIT, 50), entry(DEBIT, 50))))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rejectsSingleEntryAndEmptyAndNull() {
        assertThatThrownBy(() -> BalancingRule.check(List.of(entry(DEBIT, 100))))
                .isInstanceOf(UnbalancedTransactionException.class);
        assertThatThrownBy(() -> BalancingRule.check(List.of()))
                .isInstanceOf(UnbalancedTransactionException.class);
        assertThatThrownBy(() -> BalancingRule.check(null))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> BalancingRule.check(List.of(entry(DEBIT, 0), entry(CREDIT, 0))))
                .isInstanceOf(UnbalancedTransactionException.class);
        // -50 + 150 would "balance" against a 100 credit if signs were not checked.
        assertThatThrownBy(() -> BalancingRule.check(List.of(entry(DEBIT, -50), entry(DEBIT, 150), entry(CREDIT, 100))))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rejectsOverflowInsteadOfWrappingAround() {
        assertThatThrownBy(() -> BalancingRule.check(
                List.of(entry(DEBIT, Long.MAX_VALUE), entry(DEBIT, 1), entry(CREDIT, 1))))
                .isInstanceOf(UnbalancedTransactionException.class)
                .hasMessageContaining("too large");
    }
}
