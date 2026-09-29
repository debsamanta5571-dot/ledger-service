package com.ledger.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.AbstractIntegrationTest;
import com.ledger.TestJwks;
import com.ledger.account.AccountType;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The ledger accepts the identity service's JWTs (verified against its JWKS endpoint) and enforces one scope per
 * endpoint. The JWKS is served over real HTTP by {@link TestJwks}.
 */
class JwtScopeIT extends AbstractIntegrationTest {

    /** Owner id of the default test token's subject (see TestJwks.claims). */
    private static final String TOKEN_OWNER = "user:11111111-1111-1111-1111-111111111111";

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private ResultActions getWith(String path, String token) throws Exception {
        return plainMvc().perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private ResultActions createAccountWith(String token) throws Exception {
        return plainMvc().perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"scope-test\",\"currency\":\"USD\",\"type\":\"ASSET\"}"));
    }

    // ---- scopes are enforced per endpoint ------------------------------------------------------------------

    @Test
    void tokenWithTheRightScopeIsAccepted() throws Exception {
        getWith("/accounts", TestJwks.token("accounts:read")).andExpect(status().isOk());
    }

    @Test
    void insufficientScopeTokenIsRejectedWith403() throws Exception {
        // The headline case: a perfectly valid token that lacks transfers:write cannot move money.
        UUID from = newAccount(AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 1_000);

        String readOnly = TestJwks.token("accounts:read", "transfers:read");
        transferWith(plainMvc(), readOnly, from, to)
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:insufficient-scope"))
                .andExpect(jsonPath("$.status").value(403));

        org.assertj.core.api.Assertions.assertThat(entryCount(to)).isZero(); // nothing was posted
    }

    @Test
    void transfersWriteAloneCanTransferButCannotReadAccounts() throws Exception {
        UUID from = newAccountOwnedBy(TOKEN_OWNER, AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(from, AccountType.ASSET, 1_000);

        String writeOnly = TestJwks.token("transfers:write");
        transferWith(plainMvc(), writeOnly, from, to).andExpect(status().isCreated());
        getWith("/accounts", writeOnly).andExpect(status().isForbidden());
        getWith("/accounts/" + from, writeOnly).andExpect(status().isForbidden());
    }

    @Test
    void readScopeCannotCreateAccounts() throws Exception {
        createAccountWith(TestJwks.token("accounts:read")).andExpect(status().isForbidden());
        createAccountWith(TestJwks.token("accounts:write")).andExpect(status().isCreated());
    }

    @Test
    void statementNeedsTransfersRead() throws Exception {
        UUID account = newAccountOwnedBy(TOKEN_OWNER, AccountType.ASSET);
        getWith("/accounts/" + account + "/statement", TestJwks.token("accounts:read")).andExpect(status().isForbidden());
        getWith("/accounts/" + account + "/statement", TestJwks.token("transfers:read")).andExpect(status().isOk());
    }

    @Test
    void tokenWithNoScopeAtAllIsForbidden() throws Exception {
        getWith("/accounts", TestJwks.token()).andExpect(status().isForbidden());
    }

    @Test
    void unlistedEndpointsAreDeniedEvenForAnAllPowerfulToken() throws Exception {
        String all = TestJwks.token("accounts:read", "accounts:write", "transfers:read", "transfers:write", "users:admin");
        getWith("/admin/anything", all).andExpect(status().isForbidden());
        plainMvc().perform(get("/admin/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthAndApiDocsRemainPublic() throws Exception {
        plainMvc().perform(get("/health")).andExpect(status().isOk());
        plainMvc().perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    // ---- token validation -----------------------------------------------------------------------------------

    private void assertUnauthorized(String token) throws Exception {
        getWith("/accounts", token)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void garbageTokenIs401() throws Exception {
        assertUnauthorized("not-a-jwt");
    }

    @Test
    void expiredTokenIs401() throws Exception {
        Instant longAgo = Instant.now().minusSeconds(3600);
        assertUnauthorized(TestJwks.signWith(TestJwks.claims("accounts:read")
                .issueTime(Date.from(longAgo.minusSeconds(600))).expirationTime(Date.from(longAgo)), "at+jwt"));
    }

    @Test
    void wrongIssuerIs401() throws Exception {
        assertUnauthorized(TestJwks.signWith(TestJwks.claims("accounts:read").issuer("https://evil.example/"), "at+jwt"));
    }

    @Test
    void tokenForAnotherAudienceIs401() throws Exception {
        assertUnauthorized(TestJwks.signWith(TestJwks.claims("accounts:read").audience("some-other-api"), "at+jwt"));
    }

    @Test
    void tokenSignedByADifferentKeyIs401() throws Exception {
        assertUnauthorized(TestJwks.signedByImpostor(TestJwks.claims("accounts:read")));
    }

    @Test
    void unsignedTokenIs401() throws Exception {
        assertUnauthorized(TestJwks.unsigned(TestJwks.claims("accounts:read", "transfers:write")));
    }

    @Test
    void hs256TokenIs401() throws Exception {
        assertUnauthorized(TestJwks.hs256(TestJwks.claims("accounts:read")));
    }

    @Test
    void anIdTokenIsNotAnAccessToken() throws Exception {
        // ID tokens carry typ "JWT"; only typ "at+jwt" (RFC 9068) is accepted on the API.
        assertUnauthorized(TestJwks.signWith(TestJwks.claims("accounts:read"), "JWT"));
    }

    @Test
    void tamperedPayloadIs401() throws Exception {
        String token = TestJwks.token("accounts:read");
        String[] parts = token.split("\\.");
        JWTClaimsSet forged = TestJwks.claims("accounts:read", "transfers:write").build();
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forged.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertUnauthorized(parts[0] + "." + forgedPayload + "." + parts[2]);
    }

    @Test
    void missingBearerPrefixIs401() throws Exception {
        plainMvc().perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, TestJwks.token("accounts:read")))
                .andExpect(status().isUnauthorized());
    }

    // ---- idempotency is per caller --------------------------------------------------------------------------

    @Test
    void idempotencyKeysAreScopedPerCaller() throws Exception {
        UUID aliceFrom = newAccountOwnedBy("user:alice", AccountType.ASSET);
        UUID bobFrom = newAccountOwnedBy("user:bob", AccountType.ASSET);
        UUID to = newAccount(AccountType.ASSET);
        fund(aliceFrom, AccountType.ASSET, 10_000);
        fund(bobFrom, AccountType.ASSET, 10_000);
        String key = newKey();

        String alice = TestJwks.signWith(TestJwks.claims("transfers:write").subject("alice"), "at+jwt");
        String bob = TestJwks.signWith(TestJwks.claims("transfers:write").subject("bob"), "at+jwt");

        MockMvc mvc = plainMvc();
        transferWith(mvc, alice, aliceFrom, to, key, 100).andExpect(status().isCreated());
        transferWith(mvc, alice, aliceFrom, to, key, 100).andExpect(header().string("Idempotent-Replayed", "true"));
        // same key, different caller: a separate operation, not a replay of Alice's
        transferWith(mvc, bob, bobFrom, to, key, 100).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private ResultActions transferWith(MockMvc mvc, String token, UUID from, UUID to) throws Exception {
        return transferWith(mvc, token, from, to, newKey(), 100);
    }

    private ResultActions transferWith(MockMvc mvc, String token, UUID from, UUID to, String key, long amount)
            throws Exception {
        return mvc.perform(post("/transfers")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccountId":"%s","toAccountId":"%s","amount":%d,"currency":"USD","description":"jwt test"}"""
                        .formatted(from, to, amount)));
    }
}
