package com.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyHasherTest {

    @Test
    void producesTheKnownSha256OfAbc() {
        assertThat(ApiKeyHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void differentKeysHashDifferently() {
        assertThat(ApiKeyHasher.sha256Hex("key-1")).isNotEqualTo(ApiKeyHasher.sha256Hex("key-2"));
    }
}
