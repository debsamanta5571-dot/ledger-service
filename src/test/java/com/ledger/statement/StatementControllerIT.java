package com.ledger.statement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.AbstractIntegrationTest;
import com.ledger.account.AccountType;
import com.ledger.ledger.Direction;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class StatementControllerIT extends AbstractIntegrationTest {

    private static final Instant JAN_10 = Instant.parse("2024-01-10T12:00:00Z");
    private static final Instant JAN_15 = Instant.parse("2024-01-15T09:30:00Z");
    private static final Instant JAN_20_LATE = Instant.parse("2024-01-20T23:59:59Z");

    /** Asset account with history: +1000 (Jan 10), -200 (Jan 15), +50 (Jan 20 23:59:59). Returns the asset. */
    private UUID assetWithHistory() {
        UUID asset = newAccount(AccountType.ASSET);
        UUID counter = newAccount(AccountType.LIABILITY);
        posting(asset, Direction.DEBIT, counter, Direction.CREDIT, 1000, JAN_10);
        posting(asset, Direction.CREDIT, counter, Direction.DEBIT, 200, JAN_15);
        posting(asset, Direction.DEBIT, counter, Direction.CREDIT, 50, JAN_20_LATE);
        return asset;
    }

    @Test
    void fullHistoryShowsRunningBalancesInOrder() throws Exception {
        UUID asset = assetWithHistory();

        mvc.perform(get("/accounts/" + asset + "/statements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(asset.toString()))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.openingBalance").value(0))
                .andExpect(jsonPath("$.closingBalance").value(850))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(1000))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value(1000))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].balanceAfter").value(800))
                .andExpect(jsonPath("$.entries[2].balanceAfter").value(850))
                .andExpect(jsonPath("$.entries[0].description").value("seed"))
                .andExpect(jsonPath("$.entries[0].transactionId").exists());
    }

    @Test
    void dateRangeCarriesOpeningBalanceForwardAndIsInclusive() throws Exception {
        UUID asset = assetWithHistory();

        // Starts after the Jan 10 deposit; ends on Jan 20, which must include the 23:59:59 entry.
        mvc.perform(get("/accounts/" + asset + "/statements?from=2024-01-12&to=2024-01-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(1000))
                .andExpect(jsonPath("$.closingBalance").value(850))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value(800))
                .andExpect(jsonPath("$.entries[1].balanceAfter").value(850));

        // Range ends before the last entry.
        mvc.perform(get("/accounts/" + asset + "/statements?to=2024-01-19"))
                .andExpect(jsonPath("$.closingBalance").value(800))
                .andExpect(jsonPath("$.totalElements").value(2));

        // Range after all activity: no entries, but opening == closing == current balance.
        mvc.perform(get("/accounts/" + asset + "/statements?from=2024-02-01&to=2024-02-28"))
                .andExpect(jsonPath("$.openingBalance").value(850))
                .andExpect(jsonPath("$.closingBalance").value(850))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void paginationSplitsEntriesButKeepsRunningBalancesCorrect() throws Exception {
        UUID asset = assetWithHistory();

        mvc.perform(get("/accounts/" + asset + "/statements?size=2&page=0"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[1].balanceAfter").value(800));

        mvc.perform(get("/accounts/" + asset + "/statements?size=2&page=1"))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value(850))
                .andExpect(jsonPath("$.closingBalance").value(850));

        mvc.perform(get("/accounts/" + asset + "/statements?size=2&page=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void liabilityStatementIsReportedInCreditNormalTerms() throws Exception {
        UUID liability = newAccount(AccountType.LIABILITY);
        UUID counter = newAccount(AccountType.ASSET);
        posting(liability, Direction.CREDIT, counter, Direction.DEBIT, 1000, JAN_10);
        posting(liability, Direction.DEBIT, counter, Direction.CREDIT, 200, JAN_15);

        mvc.perform(get("/accounts/" + liability + "/statements"))
                .andExpect(jsonPath("$.closingBalance").value(800))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value(1000))
                .andExpect(jsonPath("$.entries[1].balanceAfter").value(800));
    }

    @Test
    void emptyAccountHasAnEmptyStatement() throws Exception {
        UUID asset = newAccount(AccountType.ASSET);

        mvc.perform(get("/accounts/" + asset + "/statements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(0))
                .andExpect(jsonPath("$.closingBalance").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void statementReflectsTransfersMadeThroughTheApi() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 500);
        postTransfer(newKey(), from, to, 120).andExpect(status().isCreated());

        mvc.perform(get("/accounts/" + from + "/statements"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(120))
                .andExpect(jsonPath("$.entries[1].description").value("test transfer"))
                .andExpect(jsonPath("$.entries[1].balanceAfter").value(380))
                .andExpect(jsonPath("$.closingBalance").value(380));
    }

    @Test
    void unknownAccountReturns404() throws Exception {
        mvc.perform(get("/accounts/" + UUID.randomUUID() + "/statements"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void badParametersReturn400ProblemJson() throws Exception {
        UUID asset = newAccount(AccountType.ASSET);
        String base = "/accounts/" + asset + "/statements";
        String[] badQueries = {
                "?from=2024-02-01&to=2024-01-01",
                "?size=0",
                "?size=101",
                "?page=-1",
                "?from=not-a-date",
                "?to=2024-13-45",
                "?size=abc"
        };
        for (String q : badQueries) {
            mvc.perform(get(base + q))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }
}
