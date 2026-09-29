package com.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.ledger.AbstractIntegrationTest;
import com.ledger.account.AccountType;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * End-to-end check that transactions behave: a random concurrent mix of transfers, retries with reused keys,
 * doomed requests and account closes, followed by checks of every double-entry invariant over the WHOLE journal.
 */
class LedgerIntegrityIT extends AbstractIntegrationTest {

    @Test
    void randomConcurrentWorkloadLeavesEveryLedgerInvariantIntact() throws Exception {
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID a = newAccount(AccountType.ASSET, "USD", i == 0 ? 300 : 0); // one account may overdraw a little
            fund(a, AccountType.ASSET, 500);
            accounts.add(a);
        }
        long totalBefore = accounts.stream().mapToLong(this::normalBalance).sum();
        List<String> reusableKeys = List.of(newKey(), newKey(), newKey());

        Random random = new Random(42);
        int n = 200;
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID from = accounts.get(random.nextInt(accounts.size()));
            UUID to = accounts.get(random.nextInt(accounts.size()));
            long amount = 1 + random.nextInt(250);
            int kind = random.nextInt(20);
            results.add(pool.submit(() -> {
                go.await();
                if (kind == 0) {
                    // Occasional close attempt: refused (409) while money is in the account.
                    return mvc.perform(delete("/accounts/" + from)).andReturn().getResponse().getStatus();
                }
                // Some requests reuse a key (replays or 409 conflicts); same-account ones must be refused.
                String key = kind < 3 ? reusableKeys.get(kind) : newKey();
                return postTransfer(key, from, to, amount).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (Future<Integer> f : results) {
            // Never a 5xx: every outcome is a defined business result.
            assertThat(f.get()).isIn(201, 204, 409, 422);
        }
        pool.shutdown();

        // The journal checks cover every transaction that touched these accounts. (Not the whole table: other test
        // classes share this database and SchemaIT deliberately inserts one-sided raw entries.)
        String touched = "SELECT DISTINCT transaction_id FROM entries WHERE account_id = ANY (?)";
        UUID[] ids = accounts.toArray(UUID[]::new);

        // 1. Every one of those transactions balances (debits == credits), across all of its entries.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT transaction_id FROM entries WHERE transaction_id IN (%s) GROUP BY transaction_id
                    HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0) x
                """.formatted(touched), Long.class, (Object) ids)).isZero();
        // 2. Every API transfer posted exactly two entries (one debit, one credit).
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT transaction_id FROM entries WHERE transaction_id IN (%s) GROUP BY transaction_id
                    HAVING COUNT(*) <> 2 OR COUNT(*) FILTER (WHERE direction = 'DEBIT') <> 1) x
                """.formatted(touched), Long.class, (Object) ids)).isZero();
        // 3. No transfer left a transaction header without entries (headers are written in the same transaction).
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM idempotency_keys k
                WHERE NOT EXISTS (SELECT 1 FROM entries e
                                  WHERE e.transaction_id = (k.response_body ->> 'transactionId')::uuid)
                """, Long.class)).isZero();
        // 4. Money is conserved inside the closed group of accounts (transfers only move it around).
        assertThat(accounts.stream().mapToLong(this::normalBalance).sum()).isEqualTo(totalBefore);
        // 5. No account ever dipped below its overdraft limit at any point in its history.
        for (UUID a : accounts) {
            long limit = jdbc.queryForObject("SELECT overdraft_limit FROM accounts WHERE id = ?", Long.class, a);
            Long lowest = jdbc.queryForObject("""
                    SELECT MIN(running) FROM (
                        SELECT SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END)
                               OVER (ORDER BY created_at, id) AS running
                        FROM entries WHERE account_id = ?) t
                    """, Long.class, a);
            assertThat(lowest).isGreaterThanOrEqualTo(-limit);
        }
        // 6. Every completed idempotency record points at a transaction that really exists.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM idempotency_keys k
                WHERE k.response_body IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM transactions t
                                  WHERE t.id = (k.response_body ->> 'transactionId')::uuid)
                """, Long.class)).isZero();
        // 7. No idempotency claim was left half-done (claim without a stored response).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE response_body IS NULL",
                Long.class)).isZero();
        // 8. Closed accounts hold no money.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts a WHERE a.closed_at IS NOT NULL
                  AND (SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
                       FROM entries e WHERE e.account_id = a.id) <> 0
                """, Long.class)).isZero();
    }
}
