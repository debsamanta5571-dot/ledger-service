package com.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountTypeTest {

    @Test
    void assetBalanceGrowsWithDebits() {
        assertThat(AccountType.ASSET.balanceFrom(500)).isEqualTo(500);
        assertThat(AccountType.ASSET.balanceFrom(-200)).isEqualTo(-200);
    }

    @Test
    void liabilityBalanceGrowsWithCredits() {
        assertThat(AccountType.LIABILITY.balanceFrom(-500)).isEqualTo(500);
        assertThat(AccountType.LIABILITY.balanceFrom(200)).isEqualTo(-200);
    }

    @Test
    void emptyJournalIsZeroForBothTypes() {
        assertThat(AccountType.ASSET.balanceFrom(0)).isZero();
        assertThat(AccountType.LIABILITY.balanceFrom(0)).isZero();
    }
}
