package com.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.AbstractIntegrationTest;
import com.ledger.TestJwks;
import com.ledger.api.ApiKeyHasher;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Accounts belong to whoever created them. Another caller cannot see, read, drain, close or delete them; to that
 * caller they do not exist (404, never 403, so account ids cannot be probed). Paying INTO someone else's account is
 * allowed, as at a real bank.
 */
class OwnershipIT extends AbstractIntegrationTest {

    @Autowired ObjectMapper json;

    private MockMvc client;
    private String mallory; // a second, fully-scoped API key
    private UUID alicesAccount; // owned by the default test key ("Alice")

    @BeforeEach
    void setUp() throws Exception {
        client = plainMvc();
        mallory = "mallory-" + UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, name, key_hash, rate_limit_per_minute) VALUES (?, 'mallory', ?, 1000)",
                UUID.randomUUID(), ApiKeyHasher.sha256Hex(mallory));

        String created = mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alice savings\",\"currency\":\"USD\",\"type\":\"ASSET\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        alicesAccount = UUID.fromString(json.readTree(created).get("id").asText());
        fund(alicesAccount, AccountType.ASSET, 1_000);
    }

    private ResultActions asMallory(MockHttpServletRequestBuilder request) throws Exception {
        return client.perform(request.header("X-API-Key", mallory));
    }

    private UUID malloryCreatesAccount() throws Exception {
        String body = asMallory(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mallory\",\"currency\":\"USD\",\"type\":\"ASSET\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    private String transferBody(UUID from, UUID to, long amount) {
        return "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%d,\"currency\":\"USD\"}"
                .formatted(from, to, amount);
    }

    @Test
    void ownerSeesAndReadsTheirAccount() throws Exception {
        mvc.perform(get("/accounts?limit=100")).andExpect(jsonPath("$[*].id", hasItem(alicesAccount.toString())));
        mvc.perform(get("/accounts/" + alicesAccount)).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1_000));
        mvc.perform(get("/accounts/" + alicesAccount + "/statements")).andExpect(status().isOk());
    }

    @Test
    void anotherCallerCannotSeeOrReadIt() throws Exception {
        asMallory(get("/accounts?limit=100&includeClosed=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(alicesAccount.toString()))));
        asMallory(get("/accounts/" + alicesAccount))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:account-not-found"));
        asMallory(get("/accounts/" + alicesAccount + "/statements")).andExpect(status().isNotFound());
    }

    @Test
    void someoneElsesAccountLooksExactlyLikeAMissingOne() throws Exception {
        String theirs = asMallory(get("/accounts/" + alicesAccount)).andReturn().getResponse().getContentAsString();
        UUID missing = UUID.randomUUID();
        String nobodys = asMallory(get("/accounts/" + missing)).andReturn().getResponse().getContentAsString();
        // Same status, type and title; only the echoed id differs. No way to tell "exists but not yours".
        assertThat(json.readTree(theirs).get("type")).isEqualTo(json.readTree(nobodys).get("type"));
        assertThat(json.readTree(theirs).get("title")).isEqualTo(json.readTree(nobodys).get("title"));
        assertThat(theirs.replace(alicesAccount.toString(), "ID")).isEqualTo(nobodys.replace(missing.toString(), "ID"));
    }

    @Test
    void anotherCallerCannotDrainIt() throws Exception {
        UUID mallorysAccount = malloryCreatesAccount();

        asMallory(post("/transfers").header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alicesAccount, mallorysAccount, 500)))
                .andExpect(status().isNotFound());

        assertThat(normalBalance(alicesAccount)).isEqualTo(1_000);
        assertThat(entryCount(mallorysAccount)).isZero();
    }

    @Test
    void anotherCallerCannotCloseOrDeleteIt() throws Exception {
        UUID empty = newAccount(AccountType.ASSET); // Alice's, zero balance, never used: closable and deletable

        asMallory(delete("/accounts/" + empty)).andExpect(status().isNotFound());
        asMallory(delete("/accounts/" + empty + "?permanent=true")).andExpect(status().isNotFound());

        mvc.perform(get("/accounts/" + empty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").doesNotExist());
    }

    @Test
    void anyoneCanPayIntoSomeoneElsesAccount() throws Exception {
        UUID mallorysAccount = malloryCreatesAccount();
        fund(mallorysAccount, AccountType.ASSET, 300);

        asMallory(post("/transfers").header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(mallorysAccount, alicesAccount, 200)))
                .andExpect(status().isCreated());

        mvc.perform(get("/accounts/" + alicesAccount)).andExpect(jsonPath("$.balance").value(1_200));
        asMallory(get("/accounts/" + mallorysAccount)).andExpect(jsonPath("$.balance").value(100));
    }

    @Test
    void newAccountsBelongToTheirCreator() throws Exception {
        UUID mallorysAccount = malloryCreatesAccount();
        mvc.perform(get("/accounts/" + mallorysAccount)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT owner_id FROM accounts WHERE id = ?", String.class, alicesAccount))
                .isEqualTo(apiKeyOwner());
    }

    @Test
    void identityServiceUsersOwnAccountsByTheirSubject() throws Exception {
        String carol = TestJwks.signWith(TestJwks.claims("accounts:read", "accounts:write").subject("carol"), "at+jwt");
        String dave = TestJwks.signWith(TestJwks.claims("accounts:read").subject("dave"), "at+jwt");

        String body = client.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + carol)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Carol\",\"currency\":\"USD\",\"type\":\"ASSET\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID carols = UUID.fromString(json.readTree(body).get("id").asText());

        assertThat(jdbc.queryForObject("SELECT owner_id FROM accounts WHERE id = ?", String.class, carols))
                .isEqualTo("user:carol");
        client.perform(get("/accounts/" + carols).header(HttpHeaders.AUTHORIZATION, "Bearer " + carol))
                .andExpect(status().isOk());
        client.perform(get("/accounts/" + carols).header(HttpHeaders.AUTHORIZATION, "Bearer " + dave))
                .andExpect(status().isNotFound());
        // The default API key is a different caller from Carol, so it cannot see her account either.
        mvc.perform(get("/accounts/" + carols)).andExpect(status().isNotFound());
    }
}
