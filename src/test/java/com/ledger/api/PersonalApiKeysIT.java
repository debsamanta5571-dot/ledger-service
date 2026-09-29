package com.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.AbstractIntegrationTest;
import com.ledger.TestJwks;
import com.ledger.account.AccountType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Personal API keys act as their owner; creating accounts or keys for someone else is admin-only. */
class PersonalApiKeysIT extends AbstractIntegrationTest {

    private static final String[] LEDGER = {"accounts:read", "accounts:write", "transfers:read", "transfers:write"};

    @Autowired ObjectMapper json;
    @Autowired ApiKeyRepository keysRepository;

    private MockMvc client;
    private String userSub;
    private String user;
    private String admin;

    private static String token(String sub, String name, String... scopes) throws Exception {
        return TestJwks.signWith(TestJwks.claims(scopes).subject(sub).claim("name", name), "at+jwt");
    }

    @BeforeEach
    void setUp() throws Exception {
        client = plainMvc();
        userSub = "kay-" + UUID.randomUUID();
        user = token(userSub, "Kay Keyholder", LEDGER);
        String[] adminScopes = java.util.Arrays.copyOf(LEDGER, LEDGER.length + 1);
        adminScopes[LEDGER.length] = "ledger:admin";
        admin = token("root-" + UUID.randomUUID(), "Rita Root", adminScopes);
    }

    private ResultActions bearer(String token, MockHttpServletRequestBuilder request) throws Exception {
        return client.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions withKey(String key, MockHttpServletRequestBuilder request) throws Exception {
        return client.perform(request.header("X-API-Key", key));
    }

    private JsonNode createKey(String token, String body) throws Exception {
        String res = bearer(token, post("/api-keys").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    @Test
    void aPersonalKeyActsAsItsOwner() throws Exception {
        UUID mine = newAccountOwnedBy("user:" + userSub, AccountType.ASSET);
        UUID notMine = newAccount(AccountType.ASSET);
        JsonNode created = createKey(user, "{\"name\":\"my script\"}");
        String key = created.get("key").asText();

        assertThat(key).startsWith("lk_");
        assertThat(created.get("ownerId").asText()).isEqualTo("user:" + userSub);
        withKey(key, get("/accounts?limit=100")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(mine.toString())))
                .andExpect(jsonPath("$[*].ownerId", everyItem(startsWith("user:" + userSub))));
        withKey(key, get("/accounts/" + notMine)).andExpect(status().isNotFound());

        // Money moved with the key is attributed to the person, marked as done by key.
        UUID other = newAccount(AccountType.ASSET);
        fund(mine, AccountType.ASSET, 100);
        withKey(key, post("/transfers").header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":5,\"currency\":\"USD\"}"
                                .formatted(mine, other)))
                .andExpect(status().isCreated());
        bearer(user, get("/accounts/" + mine + "/statements"))
                .andExpect(jsonPath("$.entries[-1:].initiatedBy", hasItem("Kay Keyholder (API key)")));
    }

    @Test
    void theSecretIsShownOnceAndOnlyItsHashIsStored() throws Exception {
        JsonNode created = createKey(user, "{\"name\":\"once\"}");
        String key = created.get("key").asText();

        String listed = bearer(user, get("/api-keys")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).contains(created.get("id").asText()).doesNotContain(key);
        String stored = jdbc.queryForObject("SELECT key_hash FROM api_keys WHERE id = ?::uuid", String.class,
                created.get("id").asText());
        assertThat(stored).isEqualTo(ApiKeyHasher.sha256Hex(key)).isNotEqualTo(key);
    }

    @Test
    void anApiKeyCannotMintMoreKeys() throws Exception {
        String key = createKey(user, "{\"name\":\"parent\"}").get("key").asText();
        withKey(key, post("/api-keys").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"child\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:forbidden-operation"));
    }

    @Test
    void aKeyNeverCarriesAdminPowerEvenWhenAnAdminCreatesIt() throws Exception {
        UUID someoneElses = newAccount(AccountType.ASSET);
        bearer(admin, get("/accounts/" + someoneElses)).andExpect(status().isOk()); // the admin, signed in: yes
        String key = createKey(admin, "{\"name\":\"admin's script\"}").get("key").asText();
        withKey(key, get("/accounts/" + someoneElses)).andExpect(status().isNotFound()); // their key: no
    }

    @Test
    void revokedKeysStopWorkingAndOnlyTheOwnerOrAnAdminCanRevoke() throws Exception {
        JsonNode created = createKey(user, "{\"name\":\"short-lived\"}");
        String key = created.get("key").asText();
        String id = created.get("id").asText();
        String stranger = token("stranger-" + UUID.randomUUID(), "Stranger", LEDGER);

        bearer(stranger, delete("/api-keys/" + id)).andExpect(status().isNotFound());
        withKey(key, get("/accounts")).andExpect(status().isOk());

        bearer(user, delete("/api-keys/" + id)).andExpect(status().isNoContent());
        withKey(key, get("/accounts")).andExpect(status().isUnauthorized());

        JsonNode second = createKey(user, "{\"name\":\"second\"}");
        bearer(admin, delete("/api-keys/" + second.get("id").asText())).andExpect(status().isNoContent());
    }

    @Test
    void onlyAnAdminCanCreateAKeyForSomeoneElse() throws Exception {
        String body = "{\"name\":\"onboarding\",\"ownerId\":\"user:new-customer\",\"ownerName\":\"New Customer\"}";
        bearer(user, post("/api-keys").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        JsonNode created = createKey(admin, body);
        assertThat(created.get("ownerId").asText()).isEqualTo("user:new-customer");
        assertThat(created.get("ownerName").asText()).isEqualTo("New Customer");
    }

    @Test
    void onlyAnAdminCanOpenAnAccountForSomeoneElse() throws Exception {
        String body = ("{\"name\":\"For you\",\"currency\":\"USD\",\"type\":\"ASSET\","
                + "\"ownerId\":\"user:%s\",\"ownerName\":\"Kay Keyholder\"}")
                .formatted(userSub);
        bearer(user, post("/accounts").contentType(MediaType.APPLICATION_JSON).content(
                        body.replace(userSub, "somebody-else")))
                .andExpect(status().isForbidden());

        String res = bearer(admin, post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value("user:" + userSub))
                .andExpect(jsonPath("$.ownerName").value("Kay Keyholder"))
                .andReturn().getResponse().getContentAsString();
        // And it really is theirs: they see it, with their own sign-in.
        bearer(user, get("/accounts/" + json.readTree(res).get("id").asText())).andExpect(status().isOk());
    }

    @Test
    void ownerIdMustBeAnIdentityUser() throws Exception {
        bearer(admin, post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"currency\":\"USD\",\"type\":\"ASSET\",\"ownerId\":\""
                                + apiKeyOwner() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPersonalKeyNamedBootstrapIsNeverTouchedByBootstrapRotation() throws Exception {
        JsonNode created = createKey(user, "{\"name\":\"bootstrap\"}");
        keysRepository.replaceNamedKey("bootstrap", ApiKeyHasher.sha256Hex(API_KEY), 1_000_000);
        withKey(created.get("key").asText(), get("/accounts")).andExpect(status().isOk());
        bearer(user, get("/api-keys")).andExpect(jsonPath("$[?(@.name == 'bootstrap')].ownerId",
                everyItem(startsWith("user:"))));
        mvc.perform(get("/accounts")).andExpect(status().isOk()); // the real bootstrap key still works
    }

}
