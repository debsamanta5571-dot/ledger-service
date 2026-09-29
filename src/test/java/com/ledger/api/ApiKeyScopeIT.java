package com.ledger.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** API keys carry only the configured scopes: with the production default (read-only) they cannot write. */
class ApiKeyScopeIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void readOnlyKeys(DynamicPropertyRegistry registry) {
        registry.add("ledger.auth.api-key-scopes", () -> "accounts:read,transfers:read");
    }

    @Test
    void apiKeyCanReadWithItsScopes() throws Exception {
        plainMvc().perform(get("/accounts").header(ApiKeyAuthFilter.HEADER, API_KEY)).andExpect(status().isOk());
    }

    @Test
    void apiKeyWithoutWriteScopesIsForbiddenFromWriting() throws Exception {
        plainMvc().perform(post("/accounts").header(ApiKeyAuthFilter.HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"currency\":\"USD\",\"type\":\"ASSET\"}"))
                .andExpect(status().isForbidden());
        plainMvc().perform(post("/transfers").header(ApiKeyAuthFilter.HEADER, API_KEY)
                        .header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
