package com.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.AbstractIntegrationTest;
import com.ledger.account.AccountType;
import com.ledger.api.ApiKeyHasher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class IdempotencyIT extends AbstractIntegrationTest {

    private long transactionCountFor(UUID account) {
        return jdbc.queryForObject("SELECT COUNT(DISTINCT transaction_id) FROM entries WHERE account_id = ?",
                Long.class, account);
    }

    @Test
    void sameKeyTwiceProducesExactlyOnePostingAndTheSameResponse() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 1000);
        long fromEntriesBefore = entryCount(from);
        String key = newKey();

        MvcResult first = postTransfer(key, from, to, 250).andExpect(status().isCreated()).andReturn();
        MvcResult second = postTransfer(key, from, to, 250)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());

        assertThat(entryCount(from)).isEqualTo(fromEntriesBefore + 1);
        assertThat(entryCount(to)).isEqualTo(1);
        assertThat(normalBalance(from)).isEqualTo(750);
        assertThat(normalBalance(to)).isEqualTo(250);
    }

    @Test
    void sameKeyWithADifferentBodyReturns409AndDoesNotPost() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 1000);
        String key = newKey();

        postTransfer(key, from, to, 100).andExpect(status().isCreated());
        postTransfer(key, from, to, 200)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:idempotency-key-reuse"))
                .andExpect(jsonPath("$.status").value(409));

        assertThat(normalBalance(from)).isEqualTo(900);
        assertThat(normalBalance(to)).isEqualTo(100);
    }

    @Test
    void differentKeysWithTheSameBodyAreTwoSeparateTransfers() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 1000);

        postTransfer(newKey(), from, to, 100).andExpect(status().isCreated());
        postTransfer(newKey(), from, to, 100).andExpect(status().isCreated());

        assertThat(normalBalance(to)).isEqualTo(200);
    }

    @Test
    void replayStillReturnsTheOriginalResponseEvenIfFundsWouldNowBeInsufficient() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 100);
        String key = newKey();

        postTransfer(key, from, to, 100).andExpect(status().isCreated());
        // A fresh request for the same amount would now fail, but the retry must not re-evaluate anything.
        postTransfer(key, from, to, 100)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(normalBalance(from)).isZero();
    }

    @Test
    void keysAreScopedPerApiKey() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 1000);
        String otherKey = "other-client-" + UUID.randomUUID();
        UUID otherKeyId = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, name, key_hash, rate_limit_per_minute) VALUES (?, 'other', ?, 1000)",
                otherKeyId, ApiKeyHasher.sha256Hex(otherKey));
        // The other client can only send from an account it owns; it may pay into ours.
        UUID otherFrom = newAccountOwnedBy(otherKeyId.toString(), AccountType.ASSET);
        fund(otherFrom, AccountType.ASSET, 1000);
        MockMvc plain = plainMvc();
        String sharedIdempotencyKey = newKey();

        postWithApiKey(plain, API_KEY, sharedIdempotencyKey, from, to, 100).andExpect(status().isCreated());
        // Same Idempotency-Key from a different client is a brand new request, not a replay or a 409.
        postWithApiKey(plain, otherKey, sharedIdempotencyKey, otherFrom, to, 500)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        assertThat(normalBalance(to)).isEqualTo(600);
    }

    @Test
    void tenConcurrentRequestsWithTheSameKeyProduceExactlyOnePosting() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 1000);
        String key = newKey();

        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String[]>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<String[]> task = () -> {
                go.await();
                MvcResult r = postTransfer(key, from, to, 100).andReturn();
                return new String[] {String.valueOf(r.getResponse().getStatus()), r.getResponse().getContentAsString()};
            };
            futures.add(pool.submit(task));
        }
        go.countDown();
        Set<String> statuses = new java.util.HashSet<>();
        Set<String> transactionIds = new java.util.HashSet<>();
        for (Future<String[]> f : futures) {
            String[] result = f.get();
            statuses.add(result[0]);
            transactionIds.add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(result[1])
                    .get("transactionId").asText());
        }
        pool.shutdown();

        assertThat(statuses).containsExactly("201");
        assertThat(transactionIds).hasSize(1);
        assertThat(transactionCountFor(to)).isEqualTo(1);
        assertThat(normalBalance(to)).isEqualTo(100);
        assertThat(normalBalance(from)).isEqualTo(900);
    }

    private org.springframework.test.web.servlet.ResultActions postWithApiKey(
            MockMvc client, String apiKey, String idempotencyKey, UUID from, UUID to, long amount) throws Exception {
        String body = "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%d,\"currency\":\"USD\"}"
                .formatted(from, to, amount);
        return client.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/transfers")
                .header("X-API-Key", apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body));
    }
}
