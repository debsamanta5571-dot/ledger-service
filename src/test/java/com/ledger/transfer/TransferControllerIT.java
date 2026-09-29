package com.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.AbstractIntegrationTest;
import com.ledger.account.AccountType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class TransferControllerIT extends AbstractIntegrationTest {

    @Test
    void assetTransferMovesMoneyAndPostsABalancedPair() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 1000);

        postTransfer(newKey(), from, to, 300)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/accounts/[0-9a-f-]{36}/statements")))
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.amount").value(300))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].accountId").value(from.toString()))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].accountId").value(to.toString()))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"));

        assertThat(normalBalance(from)).isEqualTo(700);
        assertThat(normalBalance(to)).isEqualTo(300);
        mvc.perform(get("/accounts/" + from)).andExpect(jsonPath("$.balance").value(700));
        mvc.perform(get("/accounts/" + to)).andExpect(jsonPath("$.balance").value(300));
    }

    @Test
    void liabilityTransferDebitsTheSourceAndCreditsTheDestination() throws Exception {
        UUID from = newAccount(AccountType.LIABILITY);
        UUID to = newAccount(AccountType.LIABILITY);
        fund(from, AccountType.LIABILITY, 1000);

        postTransfer(newKey(), from, to, 400)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"));

        assertThat(normalBalance(from)).isEqualTo(600);
        assertThat(normalBalance(to)).isEqualTo(400);
    }

    @Test
    void transferringTheExactBalanceIsAllowed() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 500);

        postTransfer(newKey(), from, to, 500).andExpect(status().isCreated());

        assertThat(normalBalance(from)).isZero();
    }

    @Test
    void overdraftLimitIsHonouredToTheLastUnit() throws Exception {
        UUID from = newAccount(AccountType.ASSET, "USD", 500);
        UUID to = newAccount(AccountType.ASSET);

        postTransfer(newKey(), from, to, 500).andExpect(status().isCreated());
        assertThat(normalBalance(from)).isEqualTo(-500);

        postTransfer(newKey(), from, to, 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:insufficient-funds"))
                .andExpect(jsonPath("$.available").value(0));
        assertThat(normalBalance(from)).isEqualTo(-500);
    }

    @Test
    void insufficientFundsReturns422ProblemJsonAndPostsNothing() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 100);

        postTransfer(newKey(), from, to, 101)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Insufficient funds"))
                .andExpect(jsonPath("$.available").value(100));

        assertThat(normalBalance(from)).isEqualTo(100);
        assertThat(entryCount(to)).isZero();
    }

    @Test
    void aFailedTransferDoesNotBurnItsIdempotencyKey() throws Exception {
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        String key = newKey();

        postTransfer(key, from, to, 100).andExpect(status().isUnprocessableEntity());
        fund(from, AccountType.ASSET, 100);

        // The failed attempt rolled back with its key, so the same key can be retried and now succeeds.
        postTransfer(key, from, to, 100).andExpect(status().isCreated());
        assertThat(normalBalance(to)).isEqualTo(100);
    }

    @Test
    void unknownSourceOrDestinationReturns404() throws Exception {
        UUID real = newAccount(AccountType.ASSET);
        UUID ghost = UUID.randomUUID();

        postTransfer(newKey(), ghost, real, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:account-not-found"));
        postTransfer(newKey(), real, ghost, 10).andExpect(status().isNotFound());
    }

    @Test
    void sameAccountIsRejected() throws Exception {
        UUID a = newAccount(AccountType.ASSET);
        fund(a, AccountType.ASSET, 100);

        postTransfer(newKey(), a, a, 10)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:invalid-transfer"));
    }

    @Test
    void currencyMismatchIsRejected() throws Exception {
        UUID usd = newAccount(AccountType.ASSET, "USD", 0);
        UUID eur = newAccount(AccountType.ASSET, "EUR", 0);
        fund(usd, AccountType.ASSET, 100);

        postTransfer(mvc, newKey(), usd, eur, 10, "USD").andExpect(status().isUnprocessableEntity());
        postTransfer(mvc, newKey(), usd, eur, 10, "EUR").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void differentAccountTypesAreRejected() throws Exception {
        UUID asset = newAccount(AccountType.ASSET);
        UUID liability = newAccount(AccountType.LIABILITY);
        fund(asset, AccountType.ASSET, 100);

        postTransfer(newKey(), asset, liability, 10)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:invalid-transfer"));
    }

    @Test
    void invalidBodiesReturn400WithFieldErrors() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String[] bad = {
                "{\"toAccountId\":\"%s\",\"amount\":10,\"currency\":\"USD\"}".formatted(b),                   // no from
                "{\"fromAccountId\":\"%s\",\"amount\":10,\"currency\":\"USD\"}".formatted(a),                 // no to
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"currency\":\"USD\"}".formatted(a, b),     // no amount
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":0,\"currency\":\"USD\"}".formatted(a, b),
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":-5,\"currency\":\"USD\"}".formatted(a, b),
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10,\"currency\":\"usd\"}".formatted(a, b),
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10}".formatted(a, b),            // no currency
                "{\"fromAccountId\":\"nope\",\"toAccountId\":\"%s\",\"amount\":10,\"currency\":\"USD\"}".formatted(b),
                "not json"
        };
        for (String body : bad) {
            mvc.perform(post("/transfers").header("Idempotency-Key", newKey())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }

        mvc.perform(post("/transfers").header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":0,\"currency\":\"USD\"}"
                                .formatted(a, b)))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    @Test
    void idempotencyKeyHeaderIsRequired() throws Exception {
        UUID a = newAccount(AccountType.ASSET);
        UUID b = newAccount(AccountType.ASSET);
        String body = "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10,\"currency\":\"USD\"}"
                .formatted(a, b);

        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/transfers").header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/transfers").header("Idempotency-Key", "k".repeat(256))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
