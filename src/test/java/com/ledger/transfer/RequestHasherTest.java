package com.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.transfer.dto.TransferRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static TransferRequest req(UUID from, UUID to, long amount, String currency, String description) {
        return new TransferRequest(from, to, amount, currency, description);
    }

    @Test
    void identicalRequestsHashIdentically() {
        assertThat(RequestHasher.hash(req(A, B, 100, "USD", "rent")))
                .isEqualTo(RequestHasher.hash(req(A, B, 100, "USD", "rent")));
    }

    @Test
    void everyFieldAffectsTheHash() {
        String base = RequestHasher.hash(req(A, B, 100, "USD", "rent"));

        assertThat(RequestHasher.hash(req(B, B, 100, "USD", "rent"))).isNotEqualTo(base);
        assertThat(RequestHasher.hash(req(A, A, 100, "USD", "rent"))).isNotEqualTo(base);
        assertThat(RequestHasher.hash(req(A, B, 101, "USD", "rent"))).isNotEqualTo(base);
        assertThat(RequestHasher.hash(req(A, B, 100, "EUR", "rent"))).isNotEqualTo(base);
        assertThat(RequestHasher.hash(req(A, B, 100, "USD", "food"))).isNotEqualTo(base);
    }

    @Test
    void producesA64CharHexDigest() {
        assertThat(RequestHasher.hash(req(A, B, 1, "USD", null))).matches("[0-9a-f]{64}");
    }
}
