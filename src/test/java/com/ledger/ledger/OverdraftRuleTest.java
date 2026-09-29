package com.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OverdraftRuleTest {

    @Test
    void allowsSpendingLessThanBalance() {
        assertThatCode(() -> OverdraftRule.check(1000, 0, 400)).doesNotThrowAnyException();
    }

    @Test
    void allowsSpendingExactlyTheBalance() {
        assertThatCode(() -> OverdraftRule.check(1000, 0, 1000)).doesNotThrowAnyException();
    }

    @Test
    void rejectsSpendingOneMinorUnitMoreThanBalanceWhenNoOverdraft() {
        assertThatThrownBy(() -> OverdraftRule.check(1000, 0, 1001))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void allowsDippingIntoTheOverdraftUpToItsLimit() {
        assertThatCode(() -> OverdraftRule.check(100, 500, 600)).doesNotThrowAnyException();
    }

    @Test
    void rejectsGoingOnePastTheOverdraftLimit() {
        assertThatThrownBy(() -> OverdraftRule.check(100, 500, 601))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void accountAlreadyInOverdraftOnlyHasTheRemainingHeadroom() {
        // balance -300 with a 500 limit leaves 200 available
        assertThatCode(() -> OverdraftRule.check(-300, 500, 200)).doesNotThrowAnyException();
        assertThatThrownBy(() -> OverdraftRule.check(-300, 500, 201))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void zeroBalanceZeroOverdraftCannotSpendAnything() {
        assertThatThrownBy(() -> OverdraftRule.check(0, 0, 1)).isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void exceptionReportsAvailableAmountIncludingOverdraft() {
        assertThatThrownBy(() -> OverdraftRule.check(100, 50, 200))
                .isInstanceOfSatisfying(InsufficientFundsException.class, e -> {
                    assertThat(e.available()).isEqualTo(150);
                    assertThat(e.getMessage()).contains("150").contains("200");
                });
    }
}
