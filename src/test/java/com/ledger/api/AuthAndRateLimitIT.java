package com.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class AuthAndRateLimitIT extends AbstractIntegrationTest {

    private String registerKey(int limitPerMinute) {
        String plaintext = "key-" + UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, name, key_hash, rate_limit_per_minute) VALUES (?, 'test', ?, ?)",
                UUID.randomUUID(), ApiKeyHasher.sha256Hex(plaintext), limitPerMinute);
        return plaintext;
    }

    @Test
    void missingCredentialsIs401ProblemJson() throws Exception {
        plainMvc().perform(get("/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void unknownApiKeyIs401() throws Exception {
        plainMvc().perform(get("/accounts").header("X-API-Key", "definitely-not-a-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:invalid-api-key"));
    }

    @Test
    void revokedApiKeyIs401() throws Exception {
        String key = registerKey(100);
        jdbc.update("UPDATE api_keys SET active = FALSE WHERE key_hash = ?", ApiKeyHasher.sha256Hex(key));

        plainMvc().perform(get("/accounts").header("X-API-Key", key)).andExpect(status().isUnauthorized());
    }

    @Test
    void validApiKeyIsAccepted() throws Exception {
        plainMvc().perform(get("/accounts").header("X-API-Key", API_KEY)).andExpect(status().isOk());
    }

    @Test
    void healthAndApiDocsNeedNoKey() throws Exception {
        MockMvc plain = plainMvc();
        plain.perform(get("/health")).andExpect(status().isOk());
        plain.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void everyBusinessEndpointRejectsAnonymousCalls() throws Exception {
        MockMvc plain = plainMvc();
        plain.perform(get("/accounts/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        plain.perform(get("/accounts/" + UUID.randomUUID() + "/statement")).andExpect(status().isUnauthorized());
        plain.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/accounts")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        plain.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/transfers")
                .header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestsOverThePerKeyLimitGet429WithRetryAfter() throws Exception {
        String key = registerKey(3);
        MockMvc plain = plainMvc();

        plain.perform(get("/accounts").header("X-API-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "2"));
        plain.perform(get("/accounts").header("X-API-Key", key))
                .andExpect(header().string("X-RateLimit-Remaining", "1"));
        plain.perform(get("/accounts").header("X-API-Key", key))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));

        plain.perform(get("/accounts").header("X-API-Key", key))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:rate-limit-exceeded"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void rateLimitsAreIndependentPerKey() throws Exception {
        String limited = registerKey(1);
        MockMvc plain = plainMvc();

        plain.perform(get("/accounts").header("X-API-Key", limited)).andExpect(status().isOk());
        plain.perform(get("/accounts").header("X-API-Key", limited)).andExpect(status().isTooManyRequests());

        // The bootstrap key has its own (very large) budget and is unaffected.
        plain.perform(get("/accounts").header("X-API-Key", API_KEY)).andExpect(status().isOk());
    }

    @Test
    void retryAfterIsAtLeastOneSecond() throws Exception {
        String key = registerKey(1);
        MockMvc plain = plainMvc();
        plain.perform(get("/accounts").header("X-API-Key", key)).andExpect(status().isOk());

        String retryAfter = plain.perform(get("/accounts").header("X-API-Key", key))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isGreaterThanOrEqualTo(1);
    }
}
