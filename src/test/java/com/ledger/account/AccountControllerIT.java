package com.ledger.account;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AccountControllerIT extends AbstractIntegrationTest {

    @Autowired ObjectMapper json;

    private String createAccount(String body) throws Exception {
        String response = mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        return node.get("id").asText();
    }

    @Test
    void createReturns201WithLocationAndZeroBalance() throws Exception {
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Checking","currency":"USD","type":"ASSET","overdraftLimit":5000}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/accounts/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.overdraftLimit").value(5000));
    }

    @Test
    void createReturnsAccountWithDefaultOverdraftOfZero() throws Exception {
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Savings","currency":"EUR","type":"LIABILITY"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name").value("Savings"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.type").value("LIABILITY"))
                .andExpect(jsonPath("$.overdraftLimit").value(0))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void getReturnsCreatedAccount() throws Exception {
        String id = createAccount("""
                {"name":"Ops","currency":"GBP","type":"ASSET","overdraftLimit":100}""");

        mvc.perform(get("/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Ops"))
                .andExpect(jsonPath("$.overdraftLimit").value(100))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void getUnknownAccountReturns404ProblemJson() throws Exception {
        mvc.perform(get("/accounts/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Account not found"));
    }

    @Test
    void getWithMalformedIdReturns400ProblemJson() throws Exception {
        mvc.perform(get("/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void createRejectsInvalidPayloads() throws Exception {
        String[] bad = {
                "{\"currency\":\"USD\",\"type\":\"ASSET\"}",                       // missing name
                "{\"name\":\"  \",\"currency\":\"USD\",\"type\":\"ASSET\"}",        // blank name
                "{\"name\":\"x\",\"currency\":\"usd\",\"type\":\"ASSET\"}",         // lowercase currency
                "{\"name\":\"x\",\"currency\":\"USDX\",\"type\":\"ASSET\"}",        // wrong length
                "{\"name\":\"x\",\"type\":\"ASSET\"}",                              // missing currency
                "{\"name\":\"x\",\"currency\":\"USD\"}",                            // missing type
                "{\"name\":\"x\",\"currency\":\"USD\",\"type\":\"EQUITY\"}",        // unknown type
                "{\"name\":\"x\",\"currency\":\"USD\",\"type\":\"ASSET\",\"overdraftLimit\":-1}",
                // Audit: both of these used to be accepted (overflow in the overdraft check, unbounded storage).
                "{\"name\":\"x\",\"currency\":\"USD\",\"type\":\"ASSET\",\"overdraftLimit\":9223372036854775807}",
                "{\"name\":\"" + "x".repeat(201) + "\",\"currency\":\"USD\",\"type\":\"ASSET\"}",
                "not json"
        };
        for (String body : bad) {
            mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Test
    void balanceIsDerivedFromEntries() throws Exception {
        String asset = createAccount("""
                {"name":"Cash","currency":"USD","type":"ASSET"}""");
        String liability = createAccount("""
                {"name":"Customer deposit","currency":"USD","type":"LIABILITY"}""");

        // A balanced deposit posted straight into the journal: DR asset 1000 / CR liability 1000.
        UUID tx = UUID.randomUUID();
        jdbc.update("INSERT INTO transactions (id) VALUES (?)", tx);
        insertEntry(tx, asset, "DEBIT", 1000);
        insertEntry(tx, liability, "CREDIT", 1000);

        UUID tx2 = UUID.randomUUID();
        jdbc.update("INSERT INTO transactions (id) VALUES (?)", tx2);
        insertEntry(tx2, asset, "CREDIT", 300);
        insertEntry(tx2, liability, "DEBIT", 300);

        mvc.perform(get("/accounts/" + asset)).andExpect(jsonPath("$.balance").value(700));
        mvc.perform(get("/accounts/" + liability)).andExpect(jsonPath("$.balance").value(700));
    }

    @Test
    void listReturnsMostRecentAccountsFirstWithBalances() throws Exception {
        String id = createAccount("""
                {"name":"Listed","currency":"USD","type":"ASSET"}""");
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, name, currency, type) VALUES (?, 'counter', 'USD', 'LIABILITY')", counter);
        UUID tx = UUID.randomUUID();
        jdbc.update("INSERT INTO transactions (id) VALUES (?)", tx);
        insertEntry(tx, id, "DEBIT", 250);
        insertEntry(tx, counter.toString(), "CREDIT", 250);

        mvc.perform(get("/accounts?limit=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].balance", contains(250)))
                .andExpect(jsonPath("$[?(@.id == '" + counter + "')].balance", contains(250)));
    }

    @Test
    void listRejectsOutOfRangeLimit() throws Exception {
        mvc.perform(get("/accounts?limit=0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/accounts?limit=101")).andExpect(status().isBadRequest());
    }

    private void insertEntry(UUID tx, String accountId, String direction, long amount) {
        jdbc.update("INSERT INTO entries (transaction_id, account_id, direction, amount) VALUES (?, ?, ?, ?)",
                tx, UUID.fromString(accountId), direction, amount);
    }
}
