package com.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class AccountClosingIT extends AbstractIntegrationTest {

    private int close(UUID id) throws Exception {
        return mvc.perform(delete("/accounts/" + id)).andReturn().getResponse().getStatus();
    }

    @Test
    void emptyAccountClosesAndDisappearsFromTheListButKeepsItsHistory() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID acct = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 500);
        postTransfer(newKey(), from, acct, 200).andExpect(status().isCreated());
        postTransfer(newKey(), acct, from, 200).andExpect(status().isCreated()); // back to zero

        mvc.perform(delete("/accounts/" + acct)).andExpect(status().isNoContent());

        mvc.perform(get("/accounts/" + acct))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt", notNullValue()))
                .andExpect(jsonPath("$.balance").value(0));
        mvc.perform(get("/accounts?limit=100")).andExpect(jsonPath("$[*].id", not(hasItem(acct.toString()))));
        mvc.perform(get("/accounts?limit=100&includeClosed=true")).andExpect(jsonPath("$[*].id", hasItem(acct.toString())));
        mvc.perform(get("/accounts/" + acct + "/statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        assertThat(entryCount(acct)).as("closing deletes nothing").isEqualTo(2);
    }

    @Test
    void closingIsIdempotent() throws Exception {
        UUID acct = newAccount(AccountType.LIABILITY);
        assertThat(close(acct)).isEqualTo(204);
        assertThat(close(acct)).isEqualTo(204);
    }

    @Test
    void nonZeroBalanceIsRefusedWith409InBothDirections() throws Exception {
        UUID positive = newAccount(AccountType.ASSET);
        fund(positive, AccountType.ASSET, 150);
        mvc.perform(delete("/accounts/" + positive))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:account-not-empty"))
                .andExpect(jsonPath("$.balance").value(150));

        UUID overdrawn = newAccount(AccountType.ASSET, "USD", 1000);
        postTransfer(newKey(), overdrawn, newAccount(AccountType.ASSET), 40).andExpect(status().isCreated());
        mvc.perform(delete("/accounts/" + overdrawn))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.balance").value(-40));

        mvc.perform(get("/accounts/" + positive)).andExpect(jsonPath("$.closedAt").doesNotExist());
    }

    @Test
    void unknownAccountIs404() throws Exception {
        assertThat(close(UUID.randomUUID())).isEqualTo(404);
    }

    @Test
    void closedAccountsCanNeitherSendNorReceive() throws Exception {
        UUID open = newAccount(AccountType.ASSET);
        fund(open, AccountType.ASSET, 100);
        UUID closed = newAccount(AccountType.ASSET, "USD", 500); // overdraft, so only "closed" can refuse it
        assertThat(close(closed)).isEqualTo(204);

        postTransfer(newKey(), open, closed, 10)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:account-closed"));
        postTransfer(newKey(), closed, open, 10)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:account-closed"));

        assertThat(entryCount(closed)).isZero();
        assertThat(normalBalance(open)).isEqualTo(100);
    }

    /**
     * The race the locking exists for: transfers INTO an account racing with attempts to close it. Without the
     * destination lock a transfer could check "open", the close could commit at balance 0, and the credit would then
     * land in a closed account. Invariant: a closed account always has a zero balance.
     */
    @Test
    void aConcurrentTransferCanNeverLandInAnAccountBeingClosed() throws Exception {
        for (int round = 0; round < 10; round++) {
            UUID source = newAccount(AccountType.ASSET);
            fund(source, AccountType.ASSET, 1_000);
            UUID target = newAccount(AccountType.ASSET);

            int n = 20;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> transfers = new ArrayList<>();
            List<Future<Integer>> closes = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (i % 2 == 0) {
                    transfers.add(pool.submit(() -> {
                        go.await();
                        return postTransfer(newKey(), source, target, 10).andReturn().getResponse().getStatus();
                    }));
                } else {
                    closes.add(pool.submit(() -> {
                        go.await();
                        return close(target);
                    }));
                }
            }
            go.countDown();
            for (Future<Integer> f : transfers) {
                assertThat(f.get()).isIn(201, 422);
            }
            for (Future<Integer> f : closes) {
                assertThat(f.get()).isIn(204, 409);
            }
            pool.shutdown();

            boolean closed = jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM accounts WHERE id = ?",
                    Boolean.class, target);
            if (closed) {
                assertThat(normalBalance(target)).as("round %d: closed account holds money", round).isZero();
            }
            // Money is conserved either way: whatever left the source arrived in the target.
            assertThat(normalBalance(source) + normalBalance(target)).isEqualTo(1_000);
        }
    }
}
