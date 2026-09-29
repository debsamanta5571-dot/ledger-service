package com.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.AbstractIntegrationTest;
import com.ledger.account.AccountType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The balance invariant under contention. All requests are released at once from a start gate so they really
 * do overlap in the database.
 */
class ConcurrencyIT extends AbstractIntegrationTest {

    private static final int PARALLEL = 50;

    private interface Request {
        int send(int index) throws Exception;
    }

    private List<Integer> runInParallel(int count, Request request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return request.send(index);
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }
        pool.shutdown();
        return statuses;
    }

    private long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    /** Lowest balance the account ever had after any entry, in creation order. */
    private long lowestRunningBalance(UUID assetAccount) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MIN(running), 0) FROM (
                    SELECT SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END)
                           OVER (ORDER BY created_at, id) AS running
                    FROM entries WHERE account_id = ?
                ) t
                """, Long.class, assetAccount);
    }

    /** Every transaction touching either account must have debits == credits. */
    private long unbalancedTransactionsTouching(UUID a, UUID b) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT transaction_id FROM entries
                    WHERE transaction_id IN (SELECT transaction_id FROM entries WHERE account_id IN (?, ?))
                    GROUP BY transaction_id
                    HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0
                ) x
                """, Long.class, a, b);
    }

    @Test
    void fiftyParallelTransfersOnOneAccountNeverOverdrawIt() throws Exception {
        UUID source = newAccount(AccountType.ASSET);
        UUID dest = newAccount(AccountType.ASSET);
        fund(source, AccountType.ASSET, 1000);

        // 50 x 30 = 1500 requested against a balance of 1000: exactly 33 can succeed (990).
        List<Integer> statuses = runInParallel(PARALLEL, i -> postTransfer(newKey(), source, dest, 30)
                .andReturn().getResponse().getStatus());

        assertThat(count(statuses, 201)).isEqualTo(33);
        assertThat(count(statuses, 422)).isEqualTo(17);
        assertThat(statuses).allMatch(s -> s == 201 || s == 422);

        assertThat(normalBalance(source)).isEqualTo(10);
        assertThat(normalBalance(dest)).isEqualTo(990);
        assertThat(lowestRunningBalance(source)).isGreaterThanOrEqualTo(0);
        assertThat(unbalancedTransactionsTouching(source, dest)).isZero();
        assertThat(entryCount(dest)).isEqualTo(33);
    }

    @Test
    void concurrentTransfersRespectANonZeroOverdraftLimit() throws Exception {
        UUID source = newAccount(AccountType.ASSET, "USD", 200);
        UUID dest = newAccount(AccountType.ASSET);

        // Headroom is 200, so 6 x 30 = 180 fits and a 7th (210) does not.
        List<Integer> statuses = runInParallel(PARALLEL, i -> postTransfer(newKey(), source, dest, 30)
                .andReturn().getResponse().getStatus());

        assertThat(count(statuses, 201)).isEqualTo(6);
        assertThat(count(statuses, 422)).isEqualTo(44);
        assertThat(normalBalance(source)).isEqualTo(-180);
        assertThat(lowestRunningBalance(source)).isGreaterThanOrEqualTo(-200);
        assertThat(unbalancedTransactionsTouching(source, dest)).isZero();
    }

    @Test
    void transfersInBothDirectionsBetweenTwoAccountsConserveMoneyAndNeverDeadlock() throws Exception {
        UUID a = newAccount(AccountType.ASSET);
        UUID b = newAccount(AccountType.ASSET);
        fund(a, AccountType.ASSET, 300);
        fund(b, AccountType.ASSET, 300);

        List<Integer> statuses = runInParallel(PARALLEL, i -> (i % 2 == 0
                ? postTransfer(newKey(), a, b, 40)
                : postTransfer(newKey(), b, a, 40)).andReturn().getResponse().getStatus());

        assertThat(statuses).allMatch(s -> s == 201 || s == 422);
        assertThat(normalBalance(a) + normalBalance(b)).isEqualTo(600);
        assertThat(normalBalance(a)).isGreaterThanOrEqualTo(0);
        assertThat(normalBalance(b)).isGreaterThanOrEqualTo(0);
        assertThat(lowestRunningBalance(a)).isGreaterThanOrEqualTo(0);
        assertThat(lowestRunningBalance(b)).isGreaterThanOrEqualTo(0);
        assertThat(unbalancedTransactionsTouching(a, b)).isZero();
    }
}
